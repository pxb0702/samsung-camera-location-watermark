package com.antigravity.locationwatermark;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {

            SharedPreferences prefs = context.getSharedPreferences("WatermarkPrefs", Context.MODE_PRIVATE);
            boolean enabled = prefs.getBoolean("service_enabled", true);

            if (enabled) {
                Log.d(TAG, "开机或应用更新，自启动水印监听服务...");
                Intent serviceIntent = new Intent(context, WatermarkService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
