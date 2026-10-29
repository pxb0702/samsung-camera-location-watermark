package com.antigravity.locationwatermark;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WatermarkService extends Service {
    private static final String TAG = "WatermarkService";
    private static final String CHANNEL_ID = "watermark_monitor_channel";
    private static final int NOTIFICATION_ID = 1001;

    private ContentObserver photoObserver;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<Long> processedImageIds = new HashSet<>();
    private static boolean isServiceRunning = false;

    public static boolean isRunning() {
        return isServiceRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification("自动水印监听服务运行中", "正在监听三星相机新拍照片..."));
        registerPhotoObserver();
        Log.d(TAG, "WatermarkService 已启动并注册监听");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void registerPhotoObserver() {
        photoObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                // 收到媒体库变动通知，在后台线程检查最新照片
                executor.execute(() -> checkAndProcessLatestPhoto());
            }
        };

        getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                photoObserver
        );
    }

    private synchronized void checkAndProcessLatestPhoto() {
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATA
        };

        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                String displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME));
                long dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED));

                // 避免自身死循环：如果是本应用生成的 WATERMARK_ 开头的照片，则跳过
                if (displayName != null && displayName.startsWith("WATERMARK_")) {
                    return;
                }

                // 避免重复处理同一张照片
                if (processedImageIds.contains(id)) {
                    return;
                }

                // 只处理最近 60 秒内新增的照片（防止启动时对相册历史照片全部重打水印）
                long currentTimeSec = System.currentTimeMillis() / 1000;
                if (Math.abs(currentTimeSec - dateAdded) > 60) {
                    return;
                }

                processedImageIds.add(id);
                Uri photoUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

                Log.d(TAG, "检测到新拍照片: " + displayName + ", 开始合成地点时间水印...");

                // 稍作延迟等待三星相机完成 EXIF 写入
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {}

                WatermarkProcessor.WatermarkResult res = WatermarkProcessor.processPhoto(this, photoUri);
                if (res.success) {
                    notifyWatermarkSuccess(res.address, res.savedUri);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "检查最新照片异常", e);
        }
    }

    private void notifyWatermarkSuccess(String address, Uri savedUri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(savedUri, "image/jpeg");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                (int) System.currentTimeMillis(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("已自动生成地点水印照片")
                .setContentText("📍 " + address)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify((int) System.currentTimeMillis(), notification);
        }
    }

    private Notification buildForegroundNotification(String title, String content) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "三星相机水印监控服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于在后台监听三星相机拍摄并自动叠加 GPS 地点和时间水印");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        if (photoObserver != null) {
            getContentResolver().unregisterContentObserver(photoObserver);
        }
        executor.shutdown();
        Log.d(TAG, "WatermarkService 已停止");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
