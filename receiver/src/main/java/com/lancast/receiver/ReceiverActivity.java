package com.lancast.receiver;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Build;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ReceiverActivity extends Activity implements SurfaceHolder.Callback {
    private static final int PORT = 53516;
    private Surface surface;
    private SurfaceView videoView;
    private TextView status;
    private volatile boolean running;
    private ServerSocket server;
    private Socket client;
    private Thread serverThread;
    private PowerManager.WakeLock wakeLock;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private String serviceName = "AOC A2 Pro";
    private DlnaServer dlnaServer;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        videoView = new SurfaceView(this);
        videoView.getHolder().addCallback(this);
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(-1, -1);
        videoParams.gravity = Gravity.CENTER;
        root.addView(videoView, videoParams);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(22);
        status.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        status.setGravity(Gravity.CENTER);
        status.setCompoundDrawablesWithIntrinsicBounds(0, R.mipmap.ic_launcher, 0, 0);
        status.setCompoundDrawablePadding((int) (18 * getResources().getDisplayMetrics().density));
        status.setLineSpacing(8, 1.08f);
        int horizontalPadding = (int) (36 * getResources().getDisplayMetrics().density);
        status.setPadding(horizontalPadding, 20, horizontalPadding, 20);
        GradientDrawable idleBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF020817, 0xFF071A36, 0xFF071022});
        status.setBackgroundDrawable(idleBackground);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        status.setText("LanCast 投屏接收器\n\n正在启动局域网投屏与 DLNA 服务…");

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LanCast:Receiver");
        wakeLock.acquire();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        startServer();
        registerDiscoveryService();
        startDlna();
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int d) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surface = null; }

    private void startServer() {
        if (serverThread != null) return;
        running = true;
        serverThread = new Thread(() -> {
            while (running) {
                try {
                    updateStatus("接收器已启动\nIP：" + localIp() + "\n端口：" + PORT +
                            "\n投屏名称：" + serviceName + "\n可在手机发送端直接搜索");
                    server = new ServerSocket(PORT);
                    log("监听 TCP " + PORT + "，服务名 " + serviceName);
                    client = server.accept();
                    if (dlnaServer != null) dlnaServer.stopPlayback();
                    client.setTcpNoDelay(true);
                    log("手机已连接：" + client.getInetAddress().getHostAddress());
                    updateStatus("手机已连接，等待画面…");
                    decode(client);
                } catch (Exception e) {
                    if (running) {
                        log("连接失败/断开：" + e.getClass().getSimpleName() + "：" + e.getMessage());
                        updateStatus("连接已断开，正在等待重连…\n" +
                                e.getClass().getSimpleName() + "：" + e.getMessage());
                    }
                } finally {
                    closeClient();
                    closeServer();
                }
            }
        }, "cast-server");
        serverThread.start();
    }

    private void decode(Socket socket) throws Exception {
        int[] capability = decoderCapability();
        log("报告解码能力：" + capability[0] + "×" + capability[1]);
        DataOutputStream hello = new DataOutputStream(socket.getOutputStream());
        hello.writeInt(0x4C434153);
        hello.writeInt(3);
        hello.writeInt(capability[0]);
        hello.writeInt(capability[1]);
        hello.flush();
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 256 * 1024));
        if (in.readInt() != 0x4C434153 || in.readInt() != 3) throw new Exception("协议不兼容");
        int width = in.readInt();
        int height = in.readInt();
        boolean audioEnabled = in.readInt() == 1;
        int sampleRate = in.readInt();
        int channels = in.readInt();
        log("发送端请求：" + width + "×" + height);
        updateVideoAspect(width, height);
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
        MediaCodec decoder = MediaCodec.createDecoderByType("video/avc");
        log("解码器：" + (Build.VERSION.SDK_INT >= 18 ? CodecName18.get(decoder) : "video/avc"));
        decoder.configure(format, surface, null, 0);
        decoder.start();
        MediaCodec audioDecoder = null;
        AudioTrack audioTrack = null;
        if (audioEnabled) {
            MediaFormat audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioDecoder = MediaCodec.createDecoderByType("audio/mp4a-latm");
            audioDecoder.configure(audioFormat, null, null, 0);
            audioDecoder.start();
            int channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT);
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer * 2, 32768),
                    AudioTrack.MODE_STREAM);
            audioTrack.play();
            log("AAC 音频解码已启动：" + sampleRate + " Hz，" + channels + " 声道");
        } else {
            log("发送端未启用内部音频");
        }
        updateStatus("");
        runOnUiThread(() -> status.setVisibility(View.GONE));
        try {
            boolean firstFrame = true;
            while (running && !socket.isClosed()) {
                int type;
                try { type = in.readInt(); } catch (EOFException end) { break; }
                int length;
                length = in.readInt();
                int flags = in.readInt();
                long pts = in.readLong();
                if (length <= 0 || length > 2 * 1024 * 1024) throw new Exception("无效音视频包");
                byte[] packet = new byte[length];
                in.readFully(packet);
                if (type == 0 && firstFrame) {
                    firstFrame = false;
                    log("收到首个 H.264 数据包：" + length + " 字节，flags=" + flags);
                }
                if (type == 0) {
                    queueDecoder(decoder, packet, flags, pts);
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    int out;
                    while ((out = decoder.dequeueOutputBuffer(info, 0)) >= 0)
                        decoder.releaseOutputBuffer(out, true);
                } else if (type == 1 && audioDecoder != null && audioTrack != null) {
                    queueDecoder(audioDecoder, packet, flags, pts);
                    MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
                    int out;
                    while ((out = audioDecoder.dequeueOutputBuffer(audioInfo, 0)) >= 0) {
                        ByteBuffer pcm = codecOutputBuffer(audioDecoder, out);
                        if (pcm != null && audioInfo.size > 0) {
                            pcm.position(audioInfo.offset);
                            pcm.limit(audioInfo.offset + audioInfo.size);
                            byte[] samples = new byte[audioInfo.size];
                            pcm.get(samples);
                            audioTrack.write(samples, 0, samples.length);
                        }
                        audioDecoder.releaseOutputBuffer(out, false);
                    }
                }
            }
        } finally {
            try { decoder.stop(); } catch (Exception ignored) {}
            decoder.release();
            if (audioDecoder != null) {
                try { audioDecoder.stop(); } catch (Exception ignored) {}
                audioDecoder.release();
            }
            if (audioTrack != null) {
                try { audioTrack.stop(); } catch (Exception ignored) {}
                audioTrack.release();
            }
            runOnUiThread(() -> status.setVisibility(View.VISIBLE));
        }
    }

    private void queueDecoder(MediaCodec codec, byte[] packet, int flags, long pts) {
        int index = codec.dequeueInputBuffer(10000);
        if (index >= 0) {
            ByteBuffer buffer = codecInputBuffer(codec, index);
            buffer.clear();
            buffer.put(packet);
            codec.queueInputBuffer(index, 0, packet.length, pts, flags);
        }
    }

    @SuppressWarnings("deprecation")
    private ByteBuffer codecInputBuffer(MediaCodec codec, int index) {
        if (Build.VERSION.SDK_INT >= 21) return CodecBuffers21.input(codec, index);
        return codec.getInputBuffers()[index];
    }

    @SuppressWarnings("deprecation")
    private ByteBuffer codecOutputBuffer(MediaCodec codec, int index) {
        if (Build.VERSION.SDK_INT >= 21) return CodecBuffers21.output(codec, index);
        return codec.getOutputBuffers()[index];
    }

    @android.annotation.TargetApi(21)
    private static final class CodecBuffers21 {
        static ByteBuffer input(MediaCodec codec, int index) { return codec.getInputBuffer(index); }
        static ByteBuffer output(MediaCodec codec, int index) { return codec.getOutputBuffer(index); }
    }

    @android.annotation.TargetApi(18)
    private static final class CodecName18 {
        static String get(MediaCodec codec) { return codec.getName(); }
    }

    private void updateStatus(String text) { runOnUiThread(() -> status.setText(text)); }

    private void updateVideoAspect(int videoWidth, int videoHeight) {
        runOnUiThread(() -> {
            android.graphics.Point screen = new android.graphics.Point();
            getWindowManager().getDefaultDisplay().getRealSize(screen);
            float videoRatio = videoWidth / (float) videoHeight;
            float screenRatio = screen.x / (float) screen.y;
            int width;
            int height;
            if (videoRatio > screenRatio) {
                width = screen.x;
                height = Math.max(1, Math.round(width / videoRatio));
            } else {
                height = screen.y;
                width = Math.max(1, Math.round(height * videoRatio));
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
            params.gravity = Gravity.CENTER;
            videoView.setLayoutParams(params);
            log("显示区域：" + width + "×" + height + "，保持原始比例");
        });
    }

    private void startDlna() {
        if (dlnaServer != null) return;
        dlnaServer = new DlnaServer(this, surface, localIp(), "AOC A2 Pro", new DlnaServer.Listener() {
            @Override public void onDlnaStatus(String message) {
                log(message);
            }
            @Override public void onDlnaPlaybackStarting() {
                closeClient();
                runOnUiThread(() -> status.setVisibility(View.GONE));
            }
            @Override public void onDlnaVideoSize(int width, int height) {
                log("DLNA 视频尺寸：" + width + "×" + height);
                updateVideoAspect(width, height);
            }
        });
        dlnaServer.start();
    }

    private void log(String message) {
        android.util.Log.i("LanCast-Receiver", message);
        try (FileWriter writer = new FileWriter(new java.io.File(getFilesDir(), "lancast.log"), true)) {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            writer.write(time + " " + message + "\n");
        } catch (Exception ignored) {}
    }

    private int[] decoderCapability() {
        if (Build.VERSION.SDK_INT < 21) return new int[]{1920, 1080};
        return DecoderCapabilities21.query();
    }

    @android.annotation.TargetApi(21)
    private static final class DecoderCapabilities21 {
        static int[] query() {
        int maxWidth = 1920, maxHeight = 1080;
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!"video/avc".equalsIgnoreCase(type)) continue;
                    MediaCodecInfo.VideoCapabilities caps =
                            info.getCapabilitiesForType(type).getVideoCapabilities();
                    maxWidth = Math.max(maxWidth, Math.min(3840, caps.getSupportedWidths().getUpper()));
                    maxHeight = Math.max(maxHeight, Math.min(2160, caps.getSupportedHeights().getUpper()));
                }
            }
        } catch (Exception ignored) {}
        return new int[]{maxWidth, maxHeight};
        }
    }

    private void registerDiscoveryService() {
        if (registrationListener != null) return;
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        NsdServiceInfo service = new NsdServiceInfo();
        String ip = localIp();
        String suffix = ip.contains(".") ? ip.substring(ip.lastIndexOf('.') + 1) : "Receiver";
        serviceName = "AOC A2 Pro-" + suffix;
        service.setServiceName(serviceName);
        service.setServiceType("_lancast._tcp.");
        service.setPort(PORT);
        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo info) {
                serviceName = info.getServiceName();
            }
            @Override public void onRegistrationFailed(NsdServiceInfo info, int code) {}
            @Override public void onServiceUnregistered(NsdServiceInfo info) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo info, int code) {}
        };
        try { nsdManager.registerService(service, NsdManager.PROTOCOL_DNS_SD, registrationListener); }
        catch (Exception ignored) {}
    }

    private String localIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int ip = wm.getConnectionInfo().getIpAddress();
        return (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255);
    }

    private void closeClient() { try { if (client != null) client.close(); } catch (Exception ignored) {} client = null; }
    private void closeServer() { try { if (server != null) server.close(); } catch (Exception ignored) {} server = null; }

    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (dlnaServer != null && dlnaServer.handleRemoteKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        running = false;
        if (nsdManager != null && registrationListener != null) {
            try { nsdManager.unregisterService(registrationListener); } catch (Exception ignored) {}
        }
        if (dlnaServer != null) dlnaServer.stop();
        closeClient();
        closeServer();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
