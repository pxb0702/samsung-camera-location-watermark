package com.antigravity.locationwatermark;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
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
        public String deviceModel;
        public Uri savedUri;
        public String errorMessage;
    }

    /**
     * 获取配置或读取的机型名称
     */
    public static String getDeviceModel(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("WatermarkPrefs", Context.MODE_PRIVATE);
        String custom = prefs.getString("custom_device_model", "");
        if (custom != null && !custom.trim().isEmpty()) {
            return custom.trim();
        }
        String model = Build.MODEL;
        if (model != null && (model.contains("F9") || model.toLowerCase().contains("fold"))) {
            return "三星 Z fold8";
        }
        if (model != null && (model.contains("F7") || model.toLowerCase().contains("flip"))) {
            return "三星 Z flip";
        }
        if (model != null && !model.isEmpty()) {
            return "三星 " + model;
        }
        return "三星 Z fold8";
    }

    /**
     * 处理指定照片，清除原生底图水印，添加时间+机型与地点水印并保存
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

            // 获取机型
            String deviceModel = getDeviceModel(context);
            result.deviceModel = deviceModel;

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

            // 3. 擦除底层旧水印并绘制全新水印
            Bitmap watermarkedBitmap = drawWatermark(rotatedBitmap, timeDisplay, locationDisplay, deviceModel);
            if (watermarkedBitmap != rotatedBitmap) {
                rotatedBitmap.recycle();
            }

            // 4. 保存加水印后的照片到相册 (Pictures/Watermark)
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
     * 智能识别并擦除照片左下角区域的原生相机白字水印
     */
    private static void eraseOriginalWatermark(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // 原生三星水印通常位于左下角区域 (x: 2%~35%w, y: 84%~99%h)
        int roiLeft = (int) (width * 0.02f);
        int roiTop = (int) (height * 0.84f);
        int roiRight = Math.min(width - 1, (int) (width * 0.35f));
        int roiBottom = Math.min(height - 1, (int) (height * 0.99f));
        int roiW = roiRight - roiLeft;
        int roiH = roiBottom - roiTop;

        if (roiW <= 0 || roiH <= 0) return;

        int[] pixels = new int[roiW * roiH];
        bitmap.getPixels(pixels, 0, roiW, roiLeft, roiTop, roiW, roiH);

        // 识别高亮白色文字像素 (R, G, B > 185 且三通道色彩接近)
        boolean[] isText = new boolean[pixels.length];
        int textPixelCount = 0;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            if (r > 185 && g > 185 && b > 185 && Math.abs(r - g) < 25 && Math.abs(g - b) < 25) {
                isText[i] = true;
                textPixelCount++;
            }
        }

        // 如果检测到原片存在白字水印，进行邻近背景像素纹理修复
        if (textPixelCount > 40) {
            for (int y = 0; y < roiH; y++) {
                for (int x = 0; x < roiW; x++) {
                    int idx = y * roiW + x;
                    if (isText[idx]) {
                        int replacement = 0;
                        boolean found = false;
                        // 纵向优先寻找附近的非文字背景像素
                        for (int dy = 1; dy <= 24; dy++) {
                            if (y - dy >= 0 && !isText[(y - dy) * roiW + x]) {
                                replacement = pixels[(y - dy) * roiW + x];
                                found = true;
                                break;
                            } else if (y + dy < roiH && !isText[(y + dy) * roiW + x]) {
                                replacement = pixels[(y + dy) * roiW + x];
                                found = true;
                                break;
                            }
                        }
                        if (found) {
                            pixels[idx] = replacement;
                        }
                    }
                }
            }
            bitmap.setPixels(pixels, 0, roiW, roiLeft, roiTop, roiW, roiH);
            Log.d(TAG, "已成功擦除左下角原生旧水印文字: 共修复 " + textPixelCount + " 个文字像素");
        }
    }

    /**
     * 绘制新水印：
     * 第一行：时间 + 机型
     * 第二行：地点
     * 字体变小一号，整体下移一行
     */
    private static Bitmap drawWatermark(Bitmap src, String timeText, String addressText, String deviceModel) {
        int width = src.getWidth();
        int height = src.getHeight();

        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(src, 0, 0, null);

        // 1. 自动擦除底层可能残留的原水印文字
        eraseOriginalWatermark(result);

        // 2. 自适应字体大小（比原先 0.024f 变小一号，改为 0.0185f）
        float textSize = Math.max(24f, width * 0.0185f);
        float lineSpacing = textSize * 0.45f;
        float padding = textSize * 0.75f;
        float cornerRadius = textSize * 0.35f;

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        textPaint.setShadowLayer(4f, 2f, 2f, Color.argb(180, 0, 0, 0));

        // 第一行：时间 + 机型
        String line1 = "🕒 " + timeText + "   " + deviceModel;
        // 第二行：地址（维持现状）
        String line2 = "📍 " + addressText;

        // 如果文本过长，自动适配截断
        float maxAllowedTextWidth = width * 0.88f;
        if (textPaint.measureText(line1) > maxAllowedTextWidth) {
            while (textPaint.measureText(line1 + "...") > maxAllowedTextWidth && line1.length() > 5) {
                line1 = line1.substring(0, line1.length() - 1);
            }
            line1 += "...";
        }
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

        // 3. 水印位置整体下移一行（marginBottom 从 0.04f 改为 0.020f）
        float marginLeft = width * 0.035f;
        float marginBottom = height * 0.020f;
        float cardLeft = marginLeft;
        float cardBottom = height - marginBottom;
        float cardTop = cardBottom - cardHeight;
        float cardRight = cardLeft + cardWidth;

        // 4. 绘制半透明圆角背景胶囊
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(140, 20, 20, 25));
        RectF bgRect = new RectF(cardLeft, cardTop, cardRight, cardBottom);
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint);

        // 5. 绘制文字
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
