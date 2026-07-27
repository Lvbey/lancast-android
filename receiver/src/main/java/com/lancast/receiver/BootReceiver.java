package com.lancast.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        Log.i("LanCast-Boot", "Received boot action: " + action);
        try {
            ReceiverKeepAliveService.start(context);
        } catch (Exception e) {
            Log.e("LanCast-Boot", "Unable to auto-start receiver service", e);
        }
    }
}
