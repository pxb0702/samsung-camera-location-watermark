package com.antigravity.locationwatermark;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

public class GeoLocationHelper {
    private static final String TAG = "GeoLocationHelper";

    /**
     * 将经纬度逆编码为格式化的中文详细地址
     */
    public static String getAddress(Context context, double latitude, double longitude) {
        // 1. 尝试使用 Android 系统内置 Geocoder（三星国行/港版系统通常由内置地图引擎提供快速准确地址）
        try {
            if (Geocoder.isPresent()) {
                Geocoder geocoder = new Geocoder(context, Locale.CHINA);
                List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address addr = addresses.get(0);
                    StringBuilder sb = new StringBuilder();
                    if (addr.getAdminArea() != null) sb.append(addr.getAdminArea());
                    if (addr.getLocality() != null && !addr.getLocality().equals(addr.getAdminArea())) {
                        sb.append(addr.getLocality());
                    }
                    if (addr.getSubLocality() != null) sb.append(addr.getSubLocality());
                    if (addr.getThoroughfare() != null) sb.append(addr.getThoroughfare());
                    if (addr.getSubThoroughfare() != null) sb.append(addr.getSubThoroughfare());
                    if (addr.getFeatureName() != null && !sb.toString().contains(addr.getFeatureName())) {
                        sb.append(addr.getFeatureName());
                    }

                    String result = sb.toString().trim();
                    if (!result.isEmpty()) {
                        Log.d(TAG, "Geocoder 解析成功: " + result);
                        return result;
                    }
                    if (addr.getAddressLine(0) != null) {
                        return addr.getAddressLine(0);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Geocoder 失败，尝试网络接口逆编码: " + e.getMessage());
        }

        // 2. 备选方案：调用 OpenStreetMap Nominatim 逆地理编码
        try {
            String urlStr = String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&accept-language=zh-CN",
                    latitude, longitude);
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "SamsungWatermarkApp/1.0");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(response.toString());
                if (json.has("display_name")) {
                    String displayName = json.getString("display_name");
                    Log.d(TAG, "OSM 逆编码成功: " + displayName);
                    return displayName;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "OSM 逆编码失败: " + e.getMessage());
        }

        // 3. 兜底方案：返回格式化的高精度经纬度坐标
        return formatCoordinates(latitude, longitude);
    }

    /**
     * 将十进制经纬度转换为度分秒格式 (例如: 22°32'45"N, 114°03'12"E)
     */
    public static String formatCoordinates(double lat, double lon) {
        String latStr = convertDMS(Math.abs(lat)) + (lat >= 0 ? "N" : "S");
        String lonStr = convertDMS(Math.abs(lon)) + (lon >= 0 ? "E" : "W");
        return latStr + ", " + lonStr;
    }

    private static String convertDMS(double val) {
        int d = (int) val;
        val = (val - d) * 60;
        int m = (int) val;
        val = (val - m) * 60;
        int s = (int) Math.round(val);
        return String.format(Locale.US, "%d°%02d'%02d\"", d, m, s);
    }
}
