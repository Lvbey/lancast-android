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
import android.os.Handler;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import java.security.SecureRandom;
import java.util.Collections;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ReceiverActivity extends Activity implements SurfaceHolder.Callback {
    private static final int PORT = 53516;
    private static volatile boolean receiverVisible;
    private Surface surface;
    private SurfaceView videoView;
    private FrameLayout idlePanel;
    private TextView status;
    private LinearLayout volumePanel;
    private ProgressBar volumeBar;
    private TextView volumeText;
    private final Handler uiHandler = new Handler();
    private final Runnable hideVolume = new Runnable() {
        @Override public void run() {
            if (volumePanel != null) volumePanel.setVisibility(View.GONE);
        }
    };
    private volatile boolean running;
    private ServerSocket server;
    private Socket client;
    private Thread serverThread;
    private PowerManager.WakeLock wakeLock;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private String serviceName = "LanCast";
    private DlnaServer dlnaServer;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ReceiverKeepAliveService.start(this);
        serviceName = loadDeviceName();
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
        idlePanel = new FrameLayout(this);
        ImageView quickStart = new ImageView(this);
        quickStart.setImageResource(R.drawable.receiver_quick_start);
        quickStart.setScaleType(ImageView.ScaleType.CENTER_CROP);
        quickStart.setAlpha(0.22f);
        idlePanel.addView(quickStart, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(42), dp(24), dp(42), dp(22));
        idlePanel.addView(content, new FrameLayout.LayoutParams(-1, -1));

        TextView deviceName = makeText("设备名称：" + serviceName, 18, 0xFF71E5FF, Typeface.BOLD);
        content.addView(deviceName, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(25);
        status.setTypeface(Typeface.create("sans", Typeface.BOLD));
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(2);
        content.addView(status, statusParams);

        TextView intro = makeText(
                "请保持手机与投影设备连接同一 Wi-Fi，然后选择下方任一方式开始投屏",
                16, 0xFFD9E8F7, Typeface.NORMAL);
        intro.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(-1, -2);
        introParams.topMargin = dp(6);
        introParams.bottomMargin = dp(14);
        content.addView(intro, introParams);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cardsParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        content.addView(cards, cardsParams);

        LinearLayout mirrorCard = makeInstructionCard(
                "实时屏幕投送",
                "Android 手机 / 平板\n\n" +
                "1  安装并打开 LanCast Sender\n" +
                "2  在设备列表中选择“" + serviceName + "”\n" +
                "3  授权屏幕与声音，点击开始投送\n\n" +
                "适合：手机桌面、照片、演示和实时操作");
        LinearLayout.LayoutParams leftCard = new LinearLayout.LayoutParams(0, -1, 1f);
        leftCard.rightMargin = dp(10);
        cards.addView(mirrorCard, leftCard);

        LinearLayout dlnaCard = makeInstructionCard(
                "在线视频投屏 · 手机免安装",
                "优酷 / 腾讯视频 / 哔哩哔哩等\n\n" +
                "1  打开视频 App 并播放视频\n" +
                "2  点击播放器中的“TV”或“投屏”\n" +
                "3  在设备列表中选择“" + serviceName + "”\n\n" +
                "适合：支持 DLNA 投屏的在线视频 App");
        LinearLayout.LayoutParams rightCard = new LinearLayout.LayoutParams(0, -1, 1f);
        rightCard.leftMargin = dp(10);
        cards.addView(dlnaCard, rightCard);

        LinearLayout repositoryRow = new LinearLayout(this);
        repositoryRow.setOrientation(LinearLayout.HORIZONTAL);
        repositoryRow.setGravity(Gravity.CENTER);
        repositoryRow.setPadding(dp(12), dp(8), dp(12), 0);

        ImageView repositoryQr = new ImageView(this);
        repositoryQr.setImageResource(R.drawable.github_repository_qr);
        repositoryQr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        repositoryRow.addView(repositoryQr, new LinearLayout.LayoutParams(dp(70), dp(70)));

        TextView repositoryText = makeText(
                "扫码查看项目源码、版本与下载\n" +
                "github.com/Lvbey/lancast-android\n" +
                "准备就绪后保持本页面打开，播放时说明页会自动隐藏",
                13, 0xFFD5EDF7, Typeface.NORMAL);
        repositoryText.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams repositoryTextParams =
                new LinearLayout.LayoutParams(-2, -2);
        repositoryTextParams.leftMargin = dp(14);
        repositoryRow.addView(repositoryText, repositoryTextParams);

        LinearLayout.LayoutParams repositoryParams = new LinearLayout.LayoutParams(-1, -2);
        repositoryParams.topMargin = dp(4);
        content.addView(repositoryRow, repositoryParams);

        root.addView(idlePanel, new FrameLayout.LayoutParams(-1, -1));

        volumePanel = new LinearLayout(this);
        volumePanel.setOrientation(LinearLayout.VERTICAL);
        volumePanel.setGravity(Gravity.CENTER);
        volumePanel.setPadding(dp(24), dp(14), dp(24), dp(14));
        GradientDrawable volumeBackground = new GradientDrawable();
        volumeBackground.setColor(0xE61A2333);
        volumeBackground.setCornerRadius(dp(14));
        volumePanel.setBackgroundDrawable(volumeBackground);
        volumeText = new TextView(this);
        volumeText.setTextColor(Color.WHITE);
        volumeText.setTextSize(16);
        volumeText.setTypeface(Typeface.create("sans", Typeface.BOLD));
        volumeText.setGravity(Gravity.CENTER);
        volumeBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        volumeBar.setMax(100);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(260), dp(12));
        barParams.topMargin = dp(8);
        volumePanel.addView(volumeText, new LinearLayout.LayoutParams(-2, -2));
        volumePanel.addView(volumeBar, barParams);
        volumePanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams volumeParams = new FrameLayout.LayoutParams(-2, -2);
        volumeParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        volumeParams.bottomMargin = dp(56);
        root.addView(volumePanel, volumeParams);

        setContentView(root);
        status.setText("正在连接网络并启动投屏服务…");

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LanCast:Receiver");
        wakeLock.acquire();
    }

    static boolean isReceiverVisible() {
        return receiverVisible;
    }

    @Override protected void onStart() {
        super.onStart();
        receiverVisible = true;
    }

    @Override protected void onStop() {
        receiverVisible = false;
        super.onStop();
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
                    updateStatus("已连接网络：" + localIp() + "    投屏名称：" + serviceName);
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
        runOnUiThread(() -> idlePanel.setVisibility(View.GONE));
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
            runOnUiThread(() -> idlePanel.setVisibility(View.VISIBLE));
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
        dlnaServer = new DlnaServer(this, surface, localIp(), serviceName, new DlnaServer.Listener() {
            @Override public void onDlnaStatus(String message) {
                log(message);
            }
            @Override public void onDlnaPlaybackStarting() {
                closeClient();
                runOnUiThread(() -> idlePanel.setVisibility(View.GONE));
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

    private String loadDeviceName() {
        android.content.SharedPreferences preferences =
                getSharedPreferences("lancast_device", MODE_PRIVATE);
        String suffix = preferences.getString("name_suffix", "");
        if (suffix == null || suffix.length() != 4) {
            suffix = Integer.toString(new SecureRandom().nextInt(36 * 36 * 36 * 36), 36)
                    .toUpperCase(Locale.US);
            while (suffix.length() < 4) suffix = "0" + suffix;
            preferences.edit().putString("name_suffix", suffix).apply();
        }
        return "LanCast-" + suffix;
    }

    private TextView makeText(String text, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        view.setLineSpacing(dp(3), 1.05f);
        return view;
    }

    private LinearLayout makeInstructionCard(String heading, String instructions) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(26), dp(18), dp(26), dp(16));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xE629355A, 0xE61A2948});
        background.setCornerRadius(dp(22));
        background.setStroke(dp(1), 0x663EDBFF);
        card.setBackgroundDrawable(background);

        TextView title = makeText(heading, 22, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView body = makeText(instructions, 16, 0xFFE7F5FF, Typeface.NORMAL);
        body.setGravity(Gravity.LEFT);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(14);
        card.addView(body, bodyParams);
        return card;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void closeClient() { try { if (client != null) client.close(); } catch (Exception ignored) {} client = null; }
    private void closeServer() { try { if (server != null) server.close(); } catch (Exception ignored) {} server = null; }

    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        boolean volumeKey = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
        if (dlnaServer != null && dlnaServer.handleRemoteKey(keyCode)) {
            if (volumeKey) showVolume(dlnaServer.getVolume());
            return true;
        }
        if (volumeKey) {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            int direction = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                    ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showVolume(int percent) {
        volumeBar.setProgress(percent);
        volumeText.setText("音量  " + percent + "%");
        volumePanel.setVisibility(View.VISIBLE);
        volumePanel.bringToFront();
        uiHandler.removeCallbacks(hideVolume);
        uiHandler.postDelayed(hideVolume, 1800);
    }

    @Override protected void onDestroy() {
        receiverVisible = false;
        running = false;
        if (nsdManager != null && registrationListener != null) {
            try { nsdManager.unregisterService(registrationListener); } catch (Exception ignored) {}
        }
        if (dlnaServer != null) dlnaServer.stop();
        closeClient();
        closeServer();
        uiHandler.removeCallbacks(hideVolume);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
