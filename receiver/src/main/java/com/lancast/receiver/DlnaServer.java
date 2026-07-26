package com.lancast.receiver;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.Surface;
import android.view.KeyEvent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class DlnaServer {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    interface Listener {
        void onDlnaStatus(String message);
        void onDlnaPlaybackStarting();
        void onDlnaVideoSize(int width, int height);
    }

    private static final int HTTP_PORT = 53517;
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RCS = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String CMS = "urn:schemas-upnp-org:service:ConnectionManager:1";
    private final Context context;
    private final Surface surface;
    private final Listener listener;
    private final String ip;
    private final String name;
    private final String uuid;
    private volatile boolean running;
    private volatile boolean playRequested;
    private volatile boolean prepared;
    private ServerSocket httpServer;
    private MulticastSocket ssdpSocket;
    private WifiManager.MulticastLock multicastLock;
    private MediaPlayer player;
    private String currentUri = "";
    private int savedPosition;
    private int resumePosition;
    private int dlnaVolume = 50;
    private int lastControllerVolume = -1;
    private int volumeBeforeMute = 50;
    private Thread httpThread;
    private Thread ssdpThread;

    DlnaServer(Context context, Surface surface, String ip, String name, Listener listener) {
        this.context = context.getApplicationContext();
        this.surface = surface;
        this.listener = listener;
        this.ip = ip;
        this.name = name;
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        this.uuid = "uuid:" + stableId(androidId == null ? ip : androidId);
    }

    void start() {
        if (running) return;
        running = true;
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("LanCast-DLNA");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
        httpThread = new Thread(this::runHttp, "dlna-http");
        ssdpThread = new Thread(this::runSsdp, "dlna-ssdp");
        httpThread.start();
        ssdpThread.start();
        status("DLNA 已启动：" + name);
    }

    void stopPlayback() {
        playRequested = false;
        prepared = false;
        if (player != null) {
            savedPosition = safePosition();
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    void stop() {
        running = false;
        stopPlayback();
        try { if (httpServer != null) httpServer.close(); } catch (Exception ignored) {}
        try { if (ssdpSocket != null) ssdpSocket.close(); } catch (Exception ignored) {}
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }

    private void runSsdp() {
        try {
            ssdpSocket = new MulticastSocket(null);
            ssdpSocket.setReuseAddress(true);
            ssdpSocket.bind(new InetSocketAddress(1900));
            InetAddress group = InetAddress.getByName("239.255.255.250");
            ssdpSocket.joinGroup(group);
            sendAlive(group);
            byte[] buffer = new byte[8192];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                ssdpSocket.receive(packet);
                String request = new String(packet.getData(), packet.getOffset(), packet.getLength(), UTF8);
                if (!request.toUpperCase(Locale.US).startsWith("M-SEARCH")) continue;
                String st = headerValue(request, "ST");
                if (st == null) continue;
                if (st.equals("ssdp:all") || st.equals("upnp:rootdevice") ||
                        st.contains("MediaRenderer") || st.contains("AVTransport") ||
                        st.contains("RenderingControl") || st.contains("ConnectionManager")) {
                    sendSearchResponse(packet.getAddress(), packet.getPort(), st);
                }
            }
        } catch (Exception e) {
            if (running) status("DLNA 发现服务异常：" + e.getMessage());
        }
    }

    private void sendAlive(InetAddress group) {
        String[] nts = {"upnp:rootdevice", uuid,
                "urn:schemas-upnp-org:device:MediaRenderer:1", AVT, RCS, CMS};
        for (String nt : nts) {
            String usn = nt.equals(uuid) ? uuid : uuid + "::" + nt;
            String msg = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\nLOCATION: " + location() + "\r\n" +
                    "NT: " + nt + "\r\nNTS: ssdp:alive\r\nSERVER: Android/9 UPnP/1.0 LanCast/3.0\r\n" +
                    "USN: " + usn + "\r\n\r\n";
            try {
                byte[] data = msg.getBytes(UTF8);
                ssdpSocket.send(new DatagramPacket(data, data.length, group, 1900));
            } catch (Exception ignored) {}
        }
    }

    private void sendSearchResponse(InetAddress address, int port, String requestedSt) {
        String st = requestedSt.equals("ssdp:all") ? "urn:schemas-upnp-org:device:MediaRenderer:1" : requestedSt;
        String usn = st.equals(uuid) ? uuid : uuid + "::" + st;
        String msg = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\n" +
                "DATE: " + new Date() + "\r\nEXT:\r\nLOCATION: " + location() + "\r\n" +
                "SERVER: Android/9 UPnP/1.0 LanCast/3.0\r\nST: " + st + "\r\nUSN: " + usn + "\r\n\r\n";
        try {
            byte[] data = msg.getBytes(UTF8);
            ssdpSocket.send(new DatagramPacket(data, data.length, address, port));
        } catch (Exception ignored) {}
    }

    private void runHttp() {
        try {
            httpServer = new ServerSocket(HTTP_PORT);
            while (running) {
                Socket socket = httpServer.accept();
                new Thread(() -> handleHttp(socket), "dlna-http-client").start();
            }
        } catch (Exception e) {
            if (running) status("DLNA HTTP 服务异常：" + e.getMessage());
        }
    }

    private void handleHttp(Socket socket) {
        try (Socket ignored = socket) {
            socket.setSoTimeout(10000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int state = 0, b;
            while ((b = in.read()) >= 0 && headerBytes.size() < 65536) {
                headerBytes.write(b);
                state = (state == 0 && b == '\r') ? 1 :
                        (state == 1 && b == '\n') ? 2 :
                        (state == 2 && b == '\r') ? 3 :
                        (state == 3 && b == '\n') ? 4 : 0;
                if (state == 4) break;
            }
            String headersText = headerBytes.toString("UTF-8");
            String[] lines = headersText.split("\\r?\\n");
            if (lines.length == 0) return;
            String[] first = lines[0].split(" ");
            String method = first[0];
            String path = first.length > 1 ? first[1] : "/";
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim());
            }
            int length = parseInt(headers.get("content-length"), 0);
            byte[] bodyBytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = in.read(bodyBytes, offset, length - offset);
                if (count < 0) break;
                offset += count;
            }
            String body = new String(bodyBytes, 0, offset, UTF8);
            if ("GET".equals(method) && "/device.xml".equals(path)) {
                respond(socket, 200, "text/xml; charset=\"utf-8\"", deviceDescription());
            } else if ("GET".equals(method) && path.endsWith("scpd.xml")) {
                respond(socket, 200, "text/xml; charset=\"utf-8\"", scpd(path));
            } else if ("POST".equals(method) && path.startsWith("/control/")) {
                String actionHeader = headers.get("soapaction");
                String cleanedAction = actionHeader == null ? "" : actionHeader.replace("\"", "");
                String action = cleanedAction.substring(cleanedAction.lastIndexOf('#') + 1);
                String response = handleSoap(path, action, body);
                respond(socket, 200, "text/xml; charset=\"utf-8\"", response);
            } else if ("SUBSCRIBE".equals(method) || "UNSUBSCRIBE".equals(method)) {
                respond(socket, 200, "text/plain", "");
            } else {
                respond(socket, 404, "text/plain", "Not found");
            }
        } catch (Exception e) {
            status("DLNA 请求异常：" + e.getMessage());
        }
    }

    private String handleSoap(String path, String action, String body) throws Exception {
        String service = path.contains("avtransport") ? AVT : path.contains("rendering") ? RCS : CMS;
        String result = "";
        status("DLNA 控制：" + action);
        if ("SetAVTransportURI".equals(action)) {
            String uri = xmlValue(body, "CurrentURI");
            String incomingUri = unescapeXml(uri);
            boolean same = sameMedia(incomingUri, currentUri);
            if (player != null && same) {
                status("DLNA 忽略重复媒体地址，保留进度：" + formatTime(safePosition()));
            } else {
                resumePosition = same ? savedPosition : 0;
                if (!same) savedPosition = 0;
                currentUri = incomingUri;
                prepare(currentUri);
                status("DLNA 收到媒体：" + shorten(currentUri));
            }
        } else if ("Play".equals(action)) {
            playRequested = true;
            if (player != null && prepared) {
                try {
                    player.start();
                    status("DLNA 开始播放");
                } catch (Exception e) {
                    status("DLNA Play 延迟执行：" + e.getMessage());
                }
            }
        } else if ("Pause".equals(action)) {
            playRequested = false;
            if (player != null && prepared) {
                try { if (player.isPlaying()) player.pause(); } catch (Exception ignored) {}
                savedPosition = safePosition();
            }
            status("DLNA 已暂停：" + formatTime(savedPosition));
        } else if ("Stop".equals(action)) {
            playRequested = false;
            if (player != null && prepared) {
                try { if (player.isPlaying()) player.pause(); } catch (Exception ignored) {}
                savedPosition = safePosition();
            }
            status("DLNA Stop 已转换为保留进度暂停：" + formatTime(savedPosition));
        } else if ("Seek".equals(action)) {
            String target = xmlValue(body, "Target");
            savedPosition = parseTime(target);
            if (player != null) player.seekTo(savedPosition);
            status("DLNA 跳转：" + formatTime(savedPosition));
        } else if ("GetTransportInfo".equals(action)) {
            String state = safeIsPlaying() ? "PLAYING" :
                    (player != null ? "PAUSED_PLAYBACK" : "STOPPED");
            result = "<CurrentTransportState>" + state + "</CurrentTransportState>" +
                    "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>";
        } else if ("GetPositionInfo".equals(action)) {
            int position = player == null ? 0 : safePosition();
            int duration = player == null ? 0 : safeDuration();
            result = "<Track>1</Track><TrackDuration>" + formatTime(duration) + "</TrackDuration>" +
                    "<TrackMetaData></TrackMetaData><TrackURI>" + escapeXml(currentUri) + "</TrackURI>" +
                    "<RelTime>" + formatTime(position) + "</RelTime><AbsTime>" + formatTime(position) +
                    "</AbsTime><RelCount>0</RelCount><AbsCount>0</AbsCount>";
        } else if ("GetMediaInfo".equals(action)) {
            result = "<NrTracks>1</NrTracks><MediaDuration>" + formatTime(safeDuration()) +
                    "</MediaDuration><CurrentURI>" + escapeXml(currentUri) +
                    "</CurrentURI><CurrentURIMetaData></CurrentURIMetaData><NextURI></NextURI>" +
                    "<NextURIMetaData></NextURIMetaData><PlayMedium>NETWORK</PlayMedium>" +
                    "<RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>";
        } else if ("GetVolume".equals(action)) {
            result = "<CurrentVolume>" + dlnaVolume + "</CurrentVolume>";
        } else if ("SetVolume".equals(action)) {
            int desired = parseInt(xmlValue(body, "DesiredVolume"), 50);
            desired = Math.max(0, Math.min(100, desired));
            if (lastControllerVolume < 0 || Math.abs(desired - lastControllerVolume) > 5) {
                dlnaVolume = Math.round(desired / 5f) * 5;
            } else if (desired > lastControllerVolume) {
                dlnaVolume = Math.min(100, dlnaVolume + 5);
            } else if (desired < lastControllerVolume) {
                dlnaVolume = Math.max(0, dlnaVolume - 5);
            }
            lastControllerVolume = desired;
            applyPlayerVolume();
            status("DLNA 设置音量：" + dlnaVolume + "%");
        } else if ("GetMute".equals(action)) {
            result = "<CurrentMute>" + (dlnaVolume == 0 ? "1" : "0") + "</CurrentMute>";
        } else if ("SetMute".equals(action)) {
            boolean mute = "1".equals(xmlValue(body, "DesiredMute")) ||
                    "true".equalsIgnoreCase(xmlValue(body, "DesiredMute"));
            if (mute) {
                if (dlnaVolume > 0) volumeBeforeMute = dlnaVolume;
                dlnaVolume = 0;
            } else if (dlnaVolume == 0) {
                dlnaVolume = Math.max(5, volumeBeforeMute);
            }
            applyPlayerVolume();
            status("DLNA 静音：" + mute);
        } else if ("GetProtocolInfo".equals(action)) {
            result = "<Source></Source><Sink>http-get:*:video/mp4:*,http-get:*:video/*:*," +
                    "http-get:*:audio/*:*,http-get:*:application/vnd.apple.mpegurl:*</Sink>";
        } else if ("GetCurrentConnectionIDs".equals(action)) {
            result = "<ConnectionIDs>0</ConnectionIDs>";
        }
        return soapResponse(service, action, result);
    }

    private void prepare(String uri) throws Exception {
        listener.onDlnaPlaybackStarting();
        prepared = false;
        if (player != null) {
            savedPosition = safePosition();
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        playRequested = false;
        player = new MediaPlayer();
        player.setSurface(surface);
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        applyPlayerVolume();
        player.setOnPreparedListener(mp -> {
            prepared = true;
            status("DLNA 媒体已就绪：" + mp.getVideoWidth() + "×" + mp.getVideoHeight());
            if (mp.getVideoWidth() > 0 && mp.getVideoHeight() > 0)
                listener.onDlnaVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
            if (resumePosition > 0) {
                int target = resumePosition;
                resumePosition = 0;
                mp.seekTo(target);
                status("DLNA 恢复进度：" + formatTime(target));
            }
            if (playRequested) mp.start();
        });
        player.setOnVideoSizeChangedListener((mp, width, height) -> {
            if (width > 0 && height > 0) listener.onDlnaVideoSize(width, height);
        });
        player.setOnErrorListener((mp, what, extra) -> {
            prepared = false;
            status("DLNA 播放错误：what=" + what + " extra=" + extra);
            return true;
        });
        player.setDataSource(context, Uri.parse(uri));
        player.prepareAsync();
    }

    private String deviceDescription() {
        return "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\">" +
                "<specVersion><major>1</major><minor>0</minor></specVersion><URLBase>http://" + ip +
                ":" + HTTP_PORT + "/</URLBase><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
                "<friendlyName>" + escapeXml(name) + "</friendlyName><manufacturer>LanCast</manufacturer>" +
                "<modelName>LanCast DLNA Receiver</modelName><modelNumber>3.0</modelNumber><UDN>" + uuid +
                "</UDN><serviceList>" + serviceXml(AVT, "AVTransport", "avtransport") +
                serviceXml(RCS, "RenderingControl", "rendering") +
                serviceXml(CMS, "ConnectionManager", "connection") +
                "</serviceList></device></root>";
    }

    private String serviceXml(String type, String id, String path) {
        return "<service><serviceType>" + type + "</serviceType><serviceId>urn:upnp-org:serviceId:" + id +
                "</serviceId><SCPDURL>/" + path + "/scpd.xml</SCPDURL><controlURL>/control/" + path +
                "</controlURL><eventSubURL>/event/" + path + "</eventSubURL></service>";
    }

    private String scpd(String path) {
        String actions;
        String states;
        if (path.contains("avtransport")) {
            actions =
                    action("SetAVTransportURI", arg("InstanceID","in","A_ARG_TYPE_InstanceID") +
                            arg("CurrentURI","in","AVTransportURI") +
                            arg("CurrentURIMetaData","in","AVTransportURIMetaData")) +
                    action("Play", arg("InstanceID","in","A_ARG_TYPE_InstanceID") +
                            arg("Speed","in","TransportPlaySpeed")) +
                    action("Pause", arg("InstanceID","in","A_ARG_TYPE_InstanceID")) +
                    action("Stop", arg("InstanceID","in","A_ARG_TYPE_InstanceID")) +
                    action("Seek", arg("InstanceID","in","A_ARG_TYPE_InstanceID") +
                            arg("Unit","in","A_ARG_TYPE_SeekMode") + arg("Target","in","A_ARG_TYPE_SeekTarget")) +
                    action("GetTransportInfo", arg("InstanceID","in","A_ARG_TYPE_InstanceID") +
                            arg("CurrentTransportState","out","TransportState") +
                            arg("CurrentTransportStatus","out","TransportStatus") +
                            arg("CurrentSpeed","out","TransportPlaySpeed")) +
                    action("GetPositionInfo", arg("InstanceID","in","A_ARG_TYPE_InstanceID") +
                            arg("Track","out","CurrentTrack") + arg("TrackDuration","out","CurrentTrackDuration") +
                            arg("TrackMetaData","out","CurrentTrackMetaData") + arg("TrackURI","out","CurrentTrackURI") +
                            arg("RelTime","out","RelativeTimePosition") + arg("AbsTime","out","AbsoluteTimePosition") +
                            arg("RelCount","out","RelativeCounterPosition") + arg("AbsCount","out","AbsoluteCounterPosition")) +
                    action("GetMediaInfo", arg("InstanceID","in","A_ARG_TYPE_InstanceID") +
                            arg("NrTracks","out","NumberOfTracks") + arg("MediaDuration","out","CurrentMediaDuration") +
                            arg("CurrentURI","out","AVTransportURI") + arg("CurrentURIMetaData","out","AVTransportURIMetaData") +
                            arg("NextURI","out","NextAVTransportURI") + arg("NextURIMetaData","out","NextAVTransportURIMetaData") +
                            arg("PlayMedium","out","PlaybackStorageMedium") + arg("RecordMedium","out","RecordStorageMedium") +
                            arg("WriteStatus","out","RecordMediumWriteStatus"));
            states = state("A_ARG_TYPE_InstanceID","ui4") + state("AVTransportURI","uri") +
                    state("AVTransportURIMetaData","string") + state("NextAVTransportURI","uri") +
                    state("NextAVTransportURIMetaData","string") + state("TransportState","string") +
                    state("TransportStatus","string") + state("TransportPlaySpeed","string") +
                    state("A_ARG_TYPE_SeekMode","string") + state("A_ARG_TYPE_SeekTarget","string") +
                    state("CurrentTrack","ui4") + state("CurrentTrackDuration","string") +
                    state("CurrentTrackMetaData","string") + state("CurrentTrackURI","uri") +
                    state("RelativeTimePosition","string") + state("AbsoluteTimePosition","string") +
                    state("RelativeCounterPosition","i4") + state("AbsoluteCounterPosition","i4") +
                    state("NumberOfTracks","ui4") + state("CurrentMediaDuration","string") +
                    state("PlaybackStorageMedium","string") + state("RecordStorageMedium","string") +
                    state("RecordMediumWriteStatus","string");
        } else if (path.contains("rendering")) {
            actions = action("GetVolume", arg("InstanceID","in","A_ARG_TYPE_InstanceID") +
                            arg("Channel","in","A_ARG_TYPE_Channel") + arg("CurrentVolume","out","Volume")) +
                    action("SetVolume", arg("InstanceID","in","A_ARG_TYPE_InstanceID") +
                            arg("Channel","in","A_ARG_TYPE_Channel") + arg("DesiredVolume","in","Volume")) +
                    action("GetMute", arg("InstanceID","in","A_ARG_TYPE_InstanceID") +
                            arg("Channel","in","A_ARG_TYPE_Channel") + arg("CurrentMute","out","Mute")) +
                    action("SetMute", arg("InstanceID","in","A_ARG_TYPE_InstanceID") +
                            arg("Channel","in","A_ARG_TYPE_Channel") + arg("DesiredMute","in","Mute"));
            states = state("A_ARG_TYPE_InstanceID","ui4") + state("A_ARG_TYPE_Channel","string") +
                    state("Volume","ui2") + state("Mute","boolean");
        } else {
            actions = action("GetProtocolInfo", arg("Source","out","SourceProtocolInfo") +
                            arg("Sink","out","SinkProtocolInfo")) +
                    action("GetCurrentConnectionIDs", arg("ConnectionIDs","out","CurrentConnectionIDs"));
            states = state("SourceProtocolInfo","string") + state("SinkProtocolInfo","string") +
                    state("CurrentConnectionIDs","string");
        }
        return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion>" +
                "<major>1</major><minor>0</minor></specVersion><actionList>" + actions +
                "</actionList><serviceStateTable>" + states + "</serviceStateTable></scpd>";
    }

    private static String action(String name, String arguments) {
        return "<action><name>" + name + "</name><argumentList>" + arguments + "</argumentList></action>";
    }
    private static String arg(String name, String direction, String related) {
        return "<argument><name>" + name + "</name><direction>" + direction +
                "</direction><relatedStateVariable>" + related + "</relatedStateVariable></argument>";
    }
    private static String state(String name, String type) {
        return "<stateVariable sendEvents=\"no\"><name>" + name + "</name><dataType>" + type +
                "</dataType></stateVariable>";
    }

    private String soapResponse(String service, String action, String result) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope " +
                "xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"" +
                "http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + "Response xmlns:u=\"" +
                service + "\">" + result + "</u:" + action + "Response></s:Body></s:Envelope>";
    }

    private void respond(Socket socket, int code, String contentType, String body) throws Exception {
        byte[] bytes = body.getBytes(UTF8);
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String header = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") +
                "\r\nContent-Type: " + contentType + "\r\nContent-Length: " + bytes.length +
                "\r\nConnection: close\r\nServer: Android/9 UPnP/1.0 LanCast/3.0\r\n\r\n";
        out.write(header.getBytes(UTF8));
        out.write(bytes);
        out.flush();
    }

    private String location() { return "http://" + ip + ":" + HTTP_PORT + "/device.xml"; }
    private void applyPlayerVolume() {
        if (player == null) return;
        float value = dlnaVolume / 100f;
        try { player.setVolume(value, value); } catch (Exception ignored) {}
    }
    private void status(String message) {
        android.util.Log.i("LanCast-DLNA", message);
        listener.onDlnaStatus(message);
        try (FileWriter writer = new FileWriter(new java.io.File(context.getFilesDir(), "dlna.log"), true)) {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            writer.write(time + " " + message + "\n");
        } catch (Exception ignored) {}
    }
    private static String headerValue(String text, String name) {
        for (String line : text.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name))
                return line.substring(colon + 1).trim();
        }
        return null;
    }
    private static String xmlValue(String xml, String tag) {
        String close = "</" + tag + ">";
        int end = xml.indexOf(close);
        if (end < 0) return "";
        int start = xml.lastIndexOf('>', end - 1);
        return start < 0 ? "" : xml.substring(start + 1, end);
    }
    private static String unescapeXml(String value) {
        return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
    }
    private static String escapeXml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception e) { return fallback; }
    }
    private static int parseTime(String value) {
        try {
            String[] p = value.split(":");
            return (Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 +
                    (int) Double.parseDouble(p[2])) * 1000;
        } catch (Exception e) { return 0; }
    }
    private static String formatTime(int millis) {
        int total = Math.max(0, millis / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, total / 60 % 60, total % 60);
    }
    private int safeDuration() { try { return player == null ? 0 : player.getDuration(); } catch (Exception e) { return 0; } }
    private int safePosition() { try { return player == null ? 0 : player.getCurrentPosition(); } catch (Exception e) { return 0; } }
    private boolean safeIsPlaying() {
        try { return player != null && prepared && player.isPlaying(); }
        catch (Exception e) { return false; }
    }

    boolean handleRemoteKey(int keyCode) {
        if (player == null) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (safeIsPlaying()) {
                    playRequested = false;
                    try { player.pause(); } catch (Exception ignored) {}
                    savedPosition = safePosition();
                    status("遥控器暂停：" + formatTime(savedPosition));
                } else {
                    playRequested = true;
                    if (prepared) try { player.start(); } catch (Exception ignored) {}
                    status("遥控器播放");
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                playRequested = true;
                if (prepared) try { player.start(); } catch (Exception ignored) {}
                status("遥控器播放");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                playRequested = false;
                if (safeIsPlaying()) try { player.pause(); } catch (Exception ignored) {}
                savedPosition = safePosition();
                status("遥控器暂停：" + formatTime(savedPosition));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(10000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-30000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(30000);
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
                dlnaVolume = Math.min(100, dlnaVolume + 5);
                applyPlayerVolume();
                status("遥控器音量：" + dlnaVolume + "%");
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                dlnaVolume = Math.max(0, dlnaVolume - 5);
                applyPlayerVolume();
                status("遥控器音量：" + dlnaVolume + "%");
                return true;
            default:
                return false;
        }
    }

    int getVolume() {
        return dlnaVolume;
    }

    private void seekBy(int deltaMs) {
        if (!prepared || player == null) return;
        int duration = safeDuration();
        int target = Math.max(0, safePosition() + deltaMs);
        if (duration > 0) target = Math.min(duration, target);
        savedPosition = target;
        try { player.seekTo(target); } catch (Exception ignored) {}
        status("遥控器跳转：" + formatTime(target));
    }
    private static String shorten(String value) { return value.length() > 100 ? value.substring(0, 100) + "…" : value; }
    private static boolean sameMedia(String first, String second) {
        if (first == null || second == null || first.isEmpty() || second.isEmpty()) return false;
        if (first.equals(second)) return true;
        try {
            Uri a = Uri.parse(first);
            Uri b = Uri.parse(second);
            return safeEquals(a.getScheme(), b.getScheme()) &&
                    safeEquals(a.getHost(), b.getHost()) &&
                    safeEquals(a.getPath(), b.getPath());
        } catch (Exception e) {
            int aq = first.indexOf('?');
            int bq = second.indexOf('?');
            return first.substring(0, aq < 0 ? first.length() : aq)
                    .equals(second.substring(0, bq < 0 ? second.length() : bq));
        }
    }
    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
    private static String stableId(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(UTF8));
            StringBuilder s = new StringBuilder();
            for (byte b : hash) s.append(String.format(Locale.US, "%02x", b));
            return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" +
                    s.substring(16, 20) + "-" + s.substring(20);
        } catch (Exception e) { return "7b9acafe-0000-4000-8000-000000000001"; }
    }
}
