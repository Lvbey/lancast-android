package com.lancast.sender;

import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public class SenderActivity extends Activity {
    private static final int REQUEST_CAPTURE = 42;
    private static final int REQUEST_AUDIO = 43;
    private EditText ip;
    private Spinner devices;
    private Spinner quality;
    private MediaProjectionManager projectionManager;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private final Map<String, String> receivers = new LinkedHashMap<>();
    private ArrayAdapter<String> deviceAdapter;
    private TextView castStatus;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            if (message != null) castStatus.setText("状态：" + message);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("局域网投屏");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("\n先在投影仪打开“局域网投屏接收器”，\n然后输入接收器显示的 IP 地址。\n");
        hint.setTextSize(17);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        ip = new EditText(this);
        ip.setHint("例如 192.168.0.101");
        ip.setText("192.168.0.101");
        ip.setSingleLine(true);
        root.addView(ip, new LinearLayout.LayoutParams(-1, -2));

        devices = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new ArrayList<String>());
        deviceAdapter.add("正在搜索局域网投屏设备…");
        devices.setAdapter(deviceAdapter);
        devices.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v,
                                                  int position, long id) {
                String host = receivers.get(deviceAdapter.getItem(position));
                if (host != null) ip.setText(host);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        root.addView(devices, new LinearLayout.LayoutParams(-1, -2));

        quality = new Spinner(this);
        quality.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"自动（推荐）", "720p", "1080p", "2K", "4K"}));
        root.addView(quality, new LinearLayout.LayoutParams(-1, -2));

        castStatus = new TextView(this);
        castStatus.setText("状态：等待操作");
        castStatus.setTextSize(15);
        castStatus.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(castStatus, new LinearLayout.LayoutParams(-1, -2));

        Button start = new Button(this);
        start.setText("开始投屏");
        start.setOnClickListener(v -> requestCapture());
        root.addView(start, new LinearLayout.LayoutParams(-1, -2));

        Button stop = new Button(this);
        stop.setText("停止投屏");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CastService.class));
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        startDiscovery();
    }

    @Override protected void onStart() {
        super.onStart();
        registerReceiver(statusReceiver, new IntentFilter(CastService.ACTION_STATUS));
    }

    @Override protected void onStop() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    private void requestCapture() {
        String host = ip.getText().toString().trim();
        if (!host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            ip.setError("请输入正确的 IPv4 地址");
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("ip", host).apply();
        if (android.os.Build.VERSION.SDK_INT >= 29 && !PermissionApi23.hasRecordAudio(this)) {
            PermissionApi23.requestRecordAudio(this, REQUEST_AUDIO);
            return;
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @android.annotation.TargetApi(23)
    private static final class PermissionApi23 {
        static boolean hasRecordAudio(Activity activity) {
            return activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        static void requestRecordAudio(Activity activity, int requestCode) {
            activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, requestCode);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_AUDIO) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestCapture();
            else
                Toast.makeText(this, "未授予录音权限，将无法传输手机内部声音", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ip.setText(getPreferences(MODE_PRIVATE).getString("ip", "192.168.0.101"));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_CAPTURE && result == RESULT_OK && data != null) {
            Intent service = new Intent(this, CastService.class);
            service.putExtra("host", ip.getText().toString().trim());
            service.putExtra("resultCode", result);
            service.putExtra("resultData", data);
            service.putExtra("maxLongEdge", new int[]{0, 1280, 1920, 2560, 3840}[quality.getSelectedItemPosition()]);
            startService(service);
            Toast.makeText(this, "正在连接投影仪", Toast.LENGTH_SHORT).show();
        }
    }

    private void startDiscovery() {
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onServiceFound(NsdServiceInfo service) {
                if (!"_lancast._tcp.".equals(service.getServiceType())) return;
                try {
                    nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo s, int code) {}
                        @Override public void onServiceResolved(NsdServiceInfo s) {
                            if (s.getHost() == null) return;
                            runOnUiThread(() -> {
                                receivers.put(s.getServiceName(), s.getHost().getHostAddress());
                                deviceAdapter.clear();
                                deviceAdapter.addAll(receivers.keySet());
                                deviceAdapter.notifyDataSetChanged();
                            });
                        }
                    });
                } catch (Exception ignored) {}
            }
            @Override public void onServiceLost(NsdServiceInfo service) {
                runOnUiThread(() -> {
                    receivers.remove(service.getServiceName());
                    deviceAdapter.clear();
                    if (receivers.isEmpty()) deviceAdapter.add("未发现设备，可手动输入 IP");
                    else deviceAdapter.addAll(receivers.keySet());
                    deviceAdapter.notifyDataSetChanged();
                });
            }
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int code) {
                try { nsdManager.stopServiceDiscovery(this); } catch (Exception ignored) {}
            }
            @Override public void onStopDiscoveryFailed(String type, int code) {}
        };
        try { nsdManager.discoverServices("_lancast._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener); }
        catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        if (nsdManager != null && discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
