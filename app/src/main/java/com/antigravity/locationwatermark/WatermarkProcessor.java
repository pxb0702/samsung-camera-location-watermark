package com.antigravity.locationwatermark;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WatermarkProcessor {
    private static final String TAG = "WatermarkProcessor";

    public static class WatermarkResult {
        public boolean success;
        public String address;
        public String timeString;
        public Uri savedUri;
        public String errorMessage;
    }

    /**
     * 处理指定照片，添加地点与时间水印并保存
     */
    public static WatermarkResult processPhoto(Context context, Uri imageUri) {
        WatermarkResult result = new WatermarkResult();
        ContentResolver resolver = context.getContentResolver();

        try {
            // 1. 读取 EXIF 元数据
            float[] latLong = new float[2];
            boolean hasLatLong = false;
            String dateTimeStr = null;
            int orientation = ExifInterface.ORIENTATION_NORMAL;

            try (InputStream in = resolver.openInputStream(imageUri)) {
                if (in != null) {
                    ExifInterface exif = new ExifInterface(in);
                    hasLatLong = exif.getLatLong(latLong);
                    dateTimeStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                    if (dateTimeStr == null) {
                        dateTimeStr = exif.getAttribute(ExifInterface.TAG_DATETIME);
                    }
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                }
            } catch (Exception e) {
                Log.w(TAG, "读取 EXIF 失败: " + e.getMessage());
            }

            // 格式化时间
            String timeDisplay;
            if (dateTimeStr != null && !dateTimeStr.trim().isEmpty()) {
                // EXIF 格式通常为 "yyyy:MM:dd HH:mm:ss"
                try {
                    SimpleDateFormat exifFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault());
                    Date date = exifFormat.parse(dateTimeStr);
                    SimpleDateFormat displayFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    timeDisplay = displayFormat.format(date);
                } catch (Exception e) {
                    timeDisplay = dateTimeStr.replace(':', '-');
                }
            } else {
                timeDisplay = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            }
            result.timeString = timeDisplay;

            // 获取地点
            String locationDisplay;
            if (hasLatLong) {
                locationDisplay = GeoLocationHelper.getAddress(context, latLong[0], latLong[1]);
            } else {
                locationDisplay = "未知地点 (照片未包含GPS信息)";
            }
            result.address = locationDisplay;

            // 2. 解码 Bitmap
            Bitmap originalBitmap;
            try (InputStream in = resolver.openInputStream(imageUri)) {
                originalBitmap = BitmapFactory.decodeStream(in);
            }
            if (originalBitmap == null) {
                result.success = false;
                result.errorMessage = "无法解码原始照片";
                return result;
            }

            // 根据 EXIF 方向旋转照片
            Bitmap rotatedBitmap = rotateBitmap(originalBitmap, orientation);
            if (rotatedBitmap != originalBitmap) {
                originalBitmap.recycle();
            }

            // 3. 绘制水印
            Bitmap watermarkedBitmap = drawWatermark(rotatedBitmap, timeDisplay, locationDisplay);
            if (watermarkedBitmap != rotatedBitmap) {
                rotatedBitmap.recycle();
            }

            // 4. 保存加水印后的照片到相册 (DCIM/Watermarked)
            Uri savedUri = saveToGallery(context, watermarkedBitmap, "WATERMARK_" + System.currentTimeMillis() + ".jpg");
            watermarkedBitmap.recycle();

            result.success = (savedUri != null);
            result.savedUri = savedUri;
            return result;

        } catch (Exception e) {
            Log.e(TAG, "处理水印发生错误", e);
            result.success = false;
            result.errorMessage = e.getMessage();
            return result;
        }
    }

    private static Bitmap rotateBitmap(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            default:
                return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * 在 Bitmap 左下角叠加设计美观的时间与地址水印
     */
    private static Bitmap drawWatermark(Bitmap src, String timeText, String addressText) {
        int width = src.getWidth();
        int height = src.getHeight();

        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(src, 0, 0, null);

        // 自适应字体大小（基于照片宽度，通常在 2.2% 左右）
        float textSize = Math.max(32f, width * 0.024f);
        float lineSpacing = textSize * 0.45f;
        float padding = textSize * 0.8f;
        float cornerRadius = textSize * 0.4f;

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        textPaint.setShadowLayer(4f, 2f, 2f, Color.argb(180, 0, 0, 0));

        Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        iconPaint.setColor(Color.WHITE);
        iconPaint.setTextSize(textSize * 0.9f);

        String line1 = "🕒 " + timeText;
        String line2 = "📍 " + addressText;

        // 如果地址过长，自动截断或缩小
        float maxAllowedTextWidth = width * 0.85f;
        if (textPaint.measureText(line2) > maxAllowedTextWidth) {
            while (textPaint.measureText(line2 + "...") > maxAllowedTextWidth && line2.length() > 5) {
                line2 = line2.substring(0, line2.length() - 1);
            }
            line2 += "...";
        }

        float textWidth1 = textPaint.measureText(line1);
        float textWidth2 = textPaint.measureText(line2);
        float maxTextWidth = Math.max(textWidth1, textWidth2);

        float cardWidth = maxTextWidth + (padding * 2);
        float cardHeight = (textSize * 2) + lineSpacing + (padding * 2);

        // 水印位于左下角（留出 3% 边距）
        float marginLeft = width * 0.04f;
        float marginBottom = height * 0.04f;
        float cardLeft = marginLeft;
        float cardBottom = height - marginBottom;
        float cardTop = cardBottom - cardHeight;
        float cardRight = cardLeft + cardWidth;

        // 绘制半透明圆角背景胶囊（提升在各种复杂背景下的可读性，贴近三星 One UI 风格）
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(140, 20, 20, 25));
        RectF bgRect = new RectF(cardLeft, cardTop, cardRight, cardBottom);
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint);

        // 绘制文字
        float textX = cardLeft + padding;
        float textY1 = cardTop + padding + textSize;
        float textY2 = textY1 + textSize + lineSpacing;

        canvas.drawText(line1, textX, textY1, textPaint);
        canvas.drawText(line2, textX, textY2, textPaint);

        return result;
    }

    /**
     * 将图片插入 Android MediaStore 相册
     */
    private static Uri saveToGallery(Context context, Bitmap bitmap, String fileName) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Watermark");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;

        try {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            }
            return uri;
        } catch (Exception e) {
            Log.e(TAG, "保存相册失败: " + e.getMessage());
            resolver.delete(uri, null, null);
            return null;
        }
    }
}
