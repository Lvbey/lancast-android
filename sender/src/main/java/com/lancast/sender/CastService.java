package com.lancast.sender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CastService extends Service {
    public static final String ACTION_STATUS = "com.lancast.sender.STATUS";
    private static final int PORT = 53516;
    private static final String CHANNEL = "cast";
    private volatile boolean running;
    private volatile boolean orientationChanged;
    private volatile boolean sessionLandscape;
    private MediaProjection projection;
    private MediaCodec encoder;
    private VirtualDisplay virtualDisplay;
    private Socket socket;
    private Surface inputSurface;
    private AudioRecord audioRecord;
    private MediaCodec audioEncoder;
    private Thread audioThread;
    private volatile boolean sessionActive;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "投屏状态",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        startForeground(7, builder.setContentTitle("正在投屏")
                .setContentText("手机画面正在发送到投影仪")
                .setSmallIcon(android.R.drawable.presence_video_online).build());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (running || intent == null) return START_NOT_STICKY;
        String host = intent.getStringExtra("host");
        int code = intent.getIntExtra("resultCode", 0);
        int maxLongEdge = intent.getIntExtra("maxLongEdge", 0);
        Intent data = intent.getParcelableExtra("resultData");
        projection = ((MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE))
                .getMediaProjection(code, data);
        running = true;
        registerOrientationListener();
        new Thread(() -> castLoop(host, maxLongEdge), "cast-encoder").start();
        return START_NOT_STICKY;
    }

    private void registerOrientationListener() {
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) {}
            @Override public void onDisplayRemoved(int id) {}
            @Override public void onDisplayChanged(int id) {
                if (id != Display.DEFAULT_DISPLAY || !running || virtualDisplay == null) return;
                Point size = screenSize();
                boolean landscape = size.x >= size.y;
                if (landscape != sessionLandscape) {
                    orientationChanged = true;
                    report("检测到方向变化，正在重建 " + (landscape ? "横屏" : "竖屏") + " 编码会话");
                    try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                }
            }
        };
        displayManager.registerDisplayListener(displayListener, null);
    }

    private void castLoop(String host, int requestedLongEdge) {
        try {
            while (running) {
                orientationChanged = false;
                try {
                    castSession(host, requestedLongEdge);
                    if (!orientationChanged) break;
                } catch (Exception e) {
                    if (!orientationChanged && running) {
                        report("投屏失败：" + e.getClass().getSimpleName() + "：" + e.getMessage());
                        android.util.Log.e("LanCast-Sender", "cast failed", e);
                        break;
                    }
                } finally {
                    releaseSession();
                }
                if (orientationChanged && running) {
                    report("方向已变化，重新连接接收端");
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                }
            }
        } finally {
            running = false;
            if (displayManager != null && displayListener != null) {
                try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {}
            }
            try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
            stopForeground(true);
            stopSelf();
        }
    }

    private void castSession(String host, int requestedLongEdge) throws Exception {
        report("正在连接 " + host + ":" + PORT);
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, PORT), 5000);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        socket.setSendBufferSize(1024 * 1024);
        DataInputStream hello = new DataInputStream(socket.getInputStream());
        int magic = hello.readInt();
        int version = hello.readInt();
        if (magic != 0x4C434153 || version != 3) throw new Exception("接收器协议版本不匹配");
        int receiverMaxWidth = hello.readInt();
        int receiverMaxHeight = hello.readInt();
        socket.setSoTimeout(0);
        report("接收端能力 " + receiverMaxWidth + "×" + receiverMaxHeight);

        Point source = screenSize();
        sessionLandscape = source.x >= source.y;
        int[] output = chooseSize(source.x, source.y, requestedLongEdge,
                receiverMaxWidth, receiverMaxHeight);
        int width = output[0], height = output[1];
        report("选定" + (sessionLandscape ? "横屏" : "竖屏") + "分辨率 " + width + "×" + height);

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        long pixels = (long) width * height;
        int bitrate = pixels >= 7_000_000 ? 25_000_000 :
                pixels >= 3_000_000 ? 14_000_000 :
                pixels >= 1_500_000 ? 8_000_000 : 4_000_000;
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encoder = MediaCodec.createEncoderByType("video/avc");
        report("编码器 " + encoder.getName() + "，码率 " + (bitrate / 1_000_000) + " Mbps");
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = encoder.createInputSurface();
        encoder.start();

        int density = getResources().getDisplayMetrics().densityDpi;
        virtualDisplay = projection.createVirtualDisplay("LanCast", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null);

        DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream(), 256 * 1024));
        boolean audioEnabled = prepareAudio();
        out.writeInt(0x4C434153);
        out.writeInt(3);
        out.writeInt(width);
        out.writeInt(height);
        out.writeInt(audioEnabled ? 1 : 0);
        out.writeInt(48000);
        out.writeInt(2);
        out.flush();
        sessionActive = true;
        if (audioEnabled) startAudioThread(out);
        report(audioEnabled ? "内部音频通道已启动：AAC 48 kHz 双声道" : "内部音频不可用，仅发送画面");

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean firstFrame = true;
        while (running && !orientationChanged) {
            int index = encoder.dequeueOutputBuffer(info, 10000);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index < 0) continue;
            ByteBuffer buffer = encoder.getOutputBuffer(index);
            if (buffer != null && info.size > 0) {
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                byte[] packet = new byte[info.size];
                buffer.get(packet);
                writePacket(out, 0, packet, info.flags, info.presentationTimeUs);
                if (firstFrame) {
                    firstFrame = false;
                    report("首个 H.264 数据包已发送：" + packet.length + " 字节");
                }
            }
            encoder.releaseOutputBuffer(index, false);
        }
    }

    private Point screenSize() {
        Point size = new Point();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
        return size;
    }

    private void releaseSession() {
        sessionActive = false;
        try { if (audioRecord != null) audioRecord.stop(); } catch (Exception ignored) {}
        try { if (audioThread != null) audioThread.join(500); } catch (Exception ignored) {}
        try { if (audioRecord != null) audioRecord.release(); } catch (Exception ignored) {}
        try { if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); } } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        encoder = null;
        socket = null;
        inputSurface = null;
        audioRecord = null;
        audioEncoder = null;
        audioThread = null;
    }

    @android.annotation.SuppressLint("MissingPermission")
    private boolean prepareAudio() {
        if (Build.VERSION.SDK_INT < 29 || !PermissionApi23.hasRecordAudio(this)) return false;
        try {
            AudioPlaybackCaptureConfiguration capture =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            AudioFormat pcm = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord.Builder()
                    .setAudioFormat(pcm)
                    .setBufferSizeInBytes(Math.max(min * 2, 32768))
                    .setAudioPlaybackCaptureConfig(capture)
                    .build();
            MediaFormat aac = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2);
            aac.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            aac.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            aac.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            audioEncoder.configure(aac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
            audioRecord.startRecording();
            return audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
        } catch (Exception e) {
            report("内部音频初始化失败：" + e.getClass().getSimpleName() + "：" + e.getMessage());
            try { if (audioRecord != null) audioRecord.release(); } catch (Exception ignored) {}
            try { if (audioEncoder != null) audioEncoder.release(); } catch (Exception ignored) {}
            audioRecord = null;
            audioEncoder = null;
            return false;
        }
    }

    private void startAudioThread(DataOutputStream out) {
        audioThread = new Thread(() -> {
            byte[] pcm = new byte[8192];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (running && sessionActive && !orientationChanged) {
                    int read = audioRecord.read(pcm, 0, pcm.length);
                    if (read > 0) {
                        int input = audioEncoder.dequeueInputBuffer(10000);
                        if (input >= 0) {
                            ByteBuffer buffer = audioEncoder.getInputBuffer(input);
                            buffer.clear();
                            buffer.put(pcm, 0, read);
                            audioEncoder.queueInputBuffer(input, 0, read,
                                    System.nanoTime() / 1000L, 0);
                        }
                    }
                    int output;
                    while ((output = audioEncoder.dequeueOutputBuffer(info, 0)) >= 0) {
                        ByteBuffer encoded = audioEncoder.getOutputBuffer(output);
                        if (encoded != null && info.size > 0) {
                            encoded.position(info.offset);
                            encoded.limit(info.offset + info.size);
                            byte[] packet = new byte[info.size];
                            encoded.get(packet);
                            writePacket(out, 1, packet, info.flags, info.presentationTimeUs);
                        }
                        audioEncoder.releaseOutputBuffer(output, false);
                    }
                }
            } catch (Exception e) {
                if (sessionActive) report("音频传输停止：" + e.getMessage());
            }
        }, "cast-audio");
        audioThread.start();
    }

    private void writePacket(DataOutputStream out, int type, byte[] packet, int flags, long pts)
            throws java.io.IOException {
        synchronized (out) {
            out.writeInt(type);
            out.writeInt(packet.length);
            out.writeInt(flags);
            out.writeLong(pts);
            out.write(packet);
            out.flush();
        }
    }

    @android.annotation.TargetApi(23)
    private static final class PermissionApi23 {
        static boolean hasRecordAudio(Service service) {
            return service.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }

    private int[] chooseSize(int sourceWidth, int sourceHeight, int requestedLongEdge,
                             int receiverMaxWidth, int receiverMaxHeight) throws Exception {
        int sourceLong = Math.max(sourceWidth, sourceHeight);
        int wanted = requestedLongEdge == 0 ? sourceLong : requestedLongEdge;
        int[] candidates = {3840, 2560, 1920, 1280, 960, 720, 640};
        int receiverLong = Math.max(receiverMaxWidth, receiverMaxHeight);
        int receiverShort = Math.min(receiverMaxWidth, receiverMaxHeight);
        MediaCodecInfo.VideoCapabilities encoderCaps = null;
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if ("video/avc".equalsIgnoreCase(type)) {
                    encoderCaps = info.getCapabilitiesForType(type).getVideoCapabilities();
                    break;
                }
            }
            if (encoderCaps != null) break;
        }
        for (int edge : candidates) {
            if (edge > wanted) continue;
            int width, height;
            if (sourceWidth >= sourceHeight) {
                width = edge;
                height = align16(Math.round(edge * (sourceHeight / (float) sourceWidth)));
            } else {
                height = edge;
                width = align16(Math.round(edge * (sourceWidth / (float) sourceHeight)));
            }
            if (Math.max(width, height) > receiverLong || Math.min(width, height) > receiverShort) continue;
            if (encoderCaps == null || encoderCaps.isSizeSupported(width, height) ||
                    encoderCaps.isSizeSupported(height, width)) return new int[]{width, height};
        }
        throw new Exception("手机编码器与投影仪没有共同支持的分辨率");
    }

    private int align16(int value) { return Math.max(16, (value / 16) * 16); }

    private void report(String message) {
        android.util.Log.i("LanCast-Sender", message);
        Intent status = new Intent(ACTION_STATUS);
        status.setPackage(getPackageName());
        status.putExtra("message", message);
        sendBroadcast(status);
        try (FileWriter writer = new FileWriter(new java.io.File(getFilesDir(), "lancast.log"), true)) {
            writer.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) +
                    " " + message + "\n");
        } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
