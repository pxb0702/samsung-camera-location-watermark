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
     * 智能识别并采用多向加权偏微分扩散算法（带形态学边缘膨胀与8向逆距离加权平滑）擦除照片左下角区域的原生相机白字水印
     */
    private static void eraseOriginalWatermark(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // 竖屏与横屏的原生三星水印区域自适应范围 (支持覆盖 portrait 与 landscape)
        float topRatio = (height > width) ? 0.74f : 0.80f;
        int roiLeft = (int) (width * 0.02f);
        int roiTop = (int) (height * topRatio);
        int roiRight = Math.min(width - 1, (int) (width * 0.48f));
        int roiBottom = Math.min(height - 1, (int) (height * 0.99f));
        int roiW = roiRight - roiLeft;
        int roiH = roiBottom - roiTop;

        if (roiW <= 0 || roiH <= 0) return;

        int[] pixels = new int[roiW * roiH];
        bitmap.getPixels(pixels, 0, roiW, roiLeft, roiTop, roiW, roiH);

        // 识别高亮白色文字像素（兼容中性纯白与有色反差背景下的字体边缘）
        boolean[] rawMask = new boolean[pixels.length];
        int textPixelCount = 0;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int sum = r + g + b;
            int maxC = Math.max(r, Math.max(g, b));
            int minC = Math.min(r, Math.min(g, b));

            // 条件1：中性高亮白字 (夜景/普通背景，色彩平衡且高亮)
            boolean isWhiteText = (r > 160 && g > 160 && b > 160 && (maxC - minC) < 38);
            // 条件2：浅色字体在有色织物/深色表面 (如深蓝外套上的反走样白字)
            boolean isFabricText = (sum > 320 && (r > 90 && g > 100));

            if (isWhiteText || isFabricText) {
                rawMask[i] = true;
                textPixelCount++;
            }
        }

        // 如果检测到原片存在文字水印，进行形态学膨胀（Dilation），把字体的半透明反走样边缘也完整包含，避免残留文字光晕
        if (textPixelCount > 30) {
            boolean[] dilatedMask = new boolean[pixels.length];
            for (int y = 0; y < roiH; y++) {
                int yOffset = y * roiW;
                for (int x = 0; x < roiW; x++) {
                    if (rawMask[yOffset + x]) {
                        for (int dy = -1; dy <= 1; dy++) {
                            int ny = y + dy;
                            if (ny >= 0 && ny < roiH) {
                                int nyOffset = ny * roiW;
                                for (int dx = -1; dx <= 1; dx++) {
                                    int nx = x + dx;
                                    if (nx >= 0 && nx < roiW) {
                                        dilatedMask[nyOffset + nx] = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 8 维邻近方向向量 (避免单向垂直复制产生的条形码拉丝伪影)
            final int[][] DIRS = {
                    {-1, 0}, {1, 0}, {0, -1}, {0, 1},
                    {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
            };

            // 8 维距离加权纹理重建（Inverse Distance Weighted Laplacian Inpainting）
            for (int y = 0; y < roiH; y++) {
                int yOffset = y * roiW;
                for (int x = 0; x < roiW; x++) {
                    int idx = yOffset + x;
                    if (dilatedMask[idx]) {
                        float totalWeight = 0f;
                        float accumR = 0f, accumG = 0f, accumB = 0f;

                        for (int[] d : DIRS) {
                            int dy = d[0], dx = d[1];
                            for (int step = 1; step <= 32; step++) {
                                int ny = y + dy * step;
                                int nx = x + dx * step;
                                if (ny >= 0 && ny < roiH && nx >= 0 && nx < roiW) {
                                    int nIdx = ny * roiW + nx;
                                    if (!dilatedMask[nIdx]) {
                                        // 逆距离衰减权重 (1 / step^1.2)
                                        float weight = 1.0f / (float) Math.pow(step, 1.2);
                                        int nc = pixels[nIdx];
                                        accumR += ((nc >> 16) & 0xFF) * weight;
                                        accumG += ((nc >> 8) & 0xFF) * weight;
                                        accumB += (nc & 0xFF) * weight;
                                        totalWeight += weight;
                                        break; // 找到此方向上最近的非文字背景像素
                                    }
                                } else {
                                    break;
                                }
                            }
                        }

                        if (totalWeight > 0f) {
                            int nr = Math.min(255, (int) (accumR / totalWeight));
                            int ng = Math.min(255, (int) (accumG / totalWeight));
                            int nb = Math.min(255, (int) (accumB / totalWeight));
                            pixels[idx] = (0xFF << 24) | (nr << 16) | (ng << 8) | nb;
                        }
                    }
                }
            }
            bitmap.setPixels(pixels, 0, roiW, roiLeft, roiTop, roiW, roiH);
            Log.d(TAG, "已成功采用多向偏微分平滑消除旧水印: 共修复 " + textPixelCount + " 个文字核心像素及其反走样边缘");
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

        // 3. 水印位置整体下移一行（自适应竖屏与横屏）
        float marginLeft = width * 0.035f;
        float marginBottom = (height > width) ? height * 0.025f : height * 0.020f;
        float cardLeft = marginLeft;
        float cardBottom = height - marginBottom;
        float cardTop = cardBottom - cardHeight;
        float cardRight = cardLeft + cardWidth;

        // 4. 绘制高质感微黑圆角背景胶囊（提升不透明度至约 80%，彻底杜绝底层旧水印透光穿帮）
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(205, 18, 18, 22));
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
