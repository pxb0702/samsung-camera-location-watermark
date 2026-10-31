package com.antigravity.locationwatermark;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GeoLocationHelper {
    private static final String TAG = "GeoLocationHelper";

    // 内置默认高德 LBS Web 服务 Key（支持国内住宅小区/AOI/航站楼/公园高精度毫秒级识别）
    private static final String DEFAULT_AMAP_KEY = "893aa291ba8fd5ceea01973a6162f182";

    /**
     * 将经纬度逆编码为格式化的中文详细地址（优先展示 小区/建筑/航站楼/公园/酒店 等具体 POI 地标）
     */
    public static String getAddress(Context context, double latitude, double longitude) {
        if (latitude == 0.0 && longitude == 0.0) {
            return "未知地点 (经纬度无效)";
        }

        // 1. 优先使用高德地图 Web 服务接口（国内最全的小区/AOI/航站楼/园区数据库）
        SharedPreferences prefs = context.getSharedPreferences("WatermarkPrefs", Context.MODE_PRIVATE);
        String customAmapKey = prefs.getString("amap_api_key", "");
        String amapKey = (customAmapKey != null && !customAmapKey.trim().isEmpty()) ? customAmapKey.trim() : DEFAULT_AMAP_KEY;

        String amapAddress = queryAmap(latitude, longitude, amapKey);
        if (amapAddress != null && !amapAddress.isEmpty()) {
            Log.d(TAG, "高德逆编码成功 (含小区/地标): " + amapAddress);
            return amapAddress;
        }

        String osmResult = null;
        String geocoderResult = null;

        // 2. 备选方案：尝试 OpenStreetMap Nominatim 逆地理编码
        try {
            osmResult = queryOsmNominatim(latitude, longitude);
        } catch (Exception e) {
            Log.w(TAG, "OSM 逆编码异常: " + e.getMessage());
        }

        // 3. 备选方案：尝试 Android 系统内置 Geocoder（通过 POI 优先组合器格式化）
        try {
            if (Geocoder.isPresent()) {
                Geocoder geocoder = new Geocoder(context, Locale.CHINA);
                List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    geocoderResult = formatAndroidAddress(addresses.get(0));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "系统 Geocoder 异常: " + e.getMessage());
        }

        // 4. 多源择优评估：比较 OSM 与系统 Geocoder，优先选择包含建筑/航站楼/公园/小区等地标信息的更丰富地址
        int osmScore = evaluateRichness(osmResult);
        int geocoderScore = evaluateRichness(geocoderResult);

        Log.d(TAG, "逆地理编码评分: OSM=" + osmScore + " (" + osmResult + ") vs Geocoder=" + geocoderScore + " (" + geocoderResult + ")");

        if (osmScore > 0 && osmScore >= geocoderScore) {
            return osmResult;
        }
        if (geocoderScore > 0) {
            return geocoderResult;
        }
        if (osmResult != null && !osmResult.isEmpty()) {
            return osmResult;
        }

        // 5. 兜底方案：返回高精度度分秒格式 (例如: 30°13'48"N, 120°26'12"E)
        return formatCoordinates(latitude, longitude);
    }

    /**
     * 调用高德开放平台 Web 服务逆地理编码接口（超精准提取国内 小区、航站楼、写字楼、园区、公园等）
     */
    private static String queryAmap(double latitude, double longitude, String apiKey) {
        try {
            // WGS84 转 GCJ02 火星坐标系（高德在中国大陆必备转换，否则偏移约 500 米）
            double[] gcj = wgs84ToGcj02(longitude, latitude);
            String urlStr = String.format(Locale.US,
                    "https://restapi.amap.com/v3/geocode/regeo?key=%s&location=%.6f,%.6f&extensions=all&radius=1000",
                    apiKey, gcj[0], gcj[1]);
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(3800);
            conn.setReadTimeout(3800);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject root = new JSONObject(response.toString());
                if ("1".equals(root.optString("status"))) {
                    JSONObject regeocode = root.optJSONObject("regeocode");
                    if (regeocode != null) {
                        List<String> parts = new ArrayList<>();

                        // 1. 优先提取所在 小区/园区/面状兴趣区 (AOI: Area of Interest，如 中海首开拾光里、杭州萧山国际机场)
                        JSONArray aois = regeocode.optJSONArray("aois");
                        String aoiName = null;
                        if (aois != null && aois.length() > 0) {
                            String rawAoi = aois.getJSONObject(0).optString("name", "").trim();
                            if (!rawAoi.isEmpty()) {
                                aoiName = rawAoi;
                            }
                        }

                        // 2. 检查 POI（具体建筑、大厦、场馆）
                        JSONArray pois = regeocode.optJSONArray("pois");
                        String poiName = null;
                        if (pois != null && pois.length() > 0) {
                            for (int i = 0; i < Math.min(pois.length(), 6); i++) {
                                JSONObject p = pois.getJSONObject(i);
                                String name = p.optString("name", "").trim();
                                // 过滤掉微小的服务设施（如充电桩、插座、洗手间、门、停车场）
                                if (name.contains("插座") || name.contains("充电") || name.contains("厕所") ||
                                    name.contains("卫生间") || name.contains("服务台") || name.contains("ATM") ||
                                    name.contains("停车场") || name.contains("东门") || name.contains("西门") ||
                                    name.contains("南门") || name.contains("北门")) {
                                    continue;
                                }
                                poiName = name;
                                break;
                            }
                        }

                        // 决定主地标名称：AOI小区优先！
                        String mainPlace = null;
                        if (aoiName != null) {
                            if (poiName != null && poiName.contains(aoiName)) {
                                mainPlace = poiName; // 比如包含更具体的楼栋/航站楼
                            } else {
                                mainPlace = aoiName; // 比如：中海首开拾光里、杭州萧山国际机场
                            }
                        } else if (poiName != null) {
                            mainPlace = poiName;
                        }

                        if (mainPlace != null) {
                            parts.add(mainPlace);
                        }

                        // 3. 提取门牌与道路 (如 门牌号、旧河湾西路、翔天路)
                        JSONObject ac = regeocode.optJSONObject("addressComponent");
                        String street = null;
                        if (ac != null) {
                            JSONObject sn = ac.optJSONObject("streetNumber");
                            if (sn != null) {
                                String num = sn.optString("number", "").trim();
                                String str = sn.optString("street", "").trim();
                                if (!num.isEmpty()) parts.add(num);
                                if (!str.isEmpty()) {
                                    parts.add(str);
                                    street = str;
                                }
                            }
                        }

                        // 如果门牌中未标明路名，尝试从周围路网（roads）中提取道路名（如 旧河湾西路）
                        if (street == null) {
                            JSONArray roads = regeocode.optJSONArray("roads");
                            if (roads != null && roads.length() > 0) {
                                String rName = roads.getJSONObject(0).optString("name", "").trim();
                                if (!rName.isEmpty() && !parts.contains(rName)) {
                                    parts.add(rName);
                                }
                            }
                        }

                        // 4. 提取乡镇/街道、区县、城市、省份
                        if (ac != null) {
                            String township = ac.optString("township", "").trim();
                            if (!township.isEmpty() && !parts.contains(township)) {
                                parts.add(township);
                            }

                            String district = ac.optString("district", "").trim();
                            if (!district.isEmpty() && !parts.contains(district)) {
                                parts.add(district);
                            }

                            String city = ac.optString("city", "").trim();
                            if (city.isEmpty() || "[]".equals(city)) {
                                city = ac.optString("province", "").trim();
                            } else {
                                if (!parts.contains(city)) {
                                    parts.add(city);
                                }
                                String prov = ac.optString("province", "").trim();
                                if (!prov.isEmpty() && !prov.equals(city) && !parts.contains(prov)) {
                                    parts.add(prov);
                                }
                            }
                        }

                        if (!parts.isEmpty()) {
                            return TextUtils.join(", ", parts);
                        }

                        String formatted = regeocode.optString("formatted_address", "");
                        if (!formatted.isEmpty()) {
                            return formatted;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "高德逆编码失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 调用 OpenStreetMap Nominatim 逆地理编码
     */
    private static String queryOsmNominatim(double latitude, double longitude) {
        try {
            String urlStr = String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&accept-language=zh-CN",
                    latitude, longitude);
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "SamsungWatermarkApp/1.4 (pxb0702)");
            conn.setConnectTimeout(3800);
            conn.setReadTimeout(3800);

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
                    String cleaned = cleanOsmDisplayName(displayName);
                    if (!cleaned.isEmpty()) {
                        Log.d(TAG, "OSM 逆编码成功: " + cleaned);
                        return cleaned;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "OSM 请求失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 清理并格式化 OSM display_name（移除末尾的国家“中国”与纯数字邮编，保留具体的建筑、门牌、道路、商圈与省市区）
     */
    public static String cleanOsmDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "";
        String[] rawParts = displayName.split(",");
        List<String> parts = new ArrayList<>();
        for (String p : rawParts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }

        // 移除末尾的国家名称 (中国 / China / CN)
        if (!parts.isEmpty()) {
            String last = parts.get(parts.size() - 1);
            if ("中国".equals(last) || "China".equalsIgnoreCase(last) || "CN".equalsIgnoreCase(last)) {
                parts.remove(parts.size() - 1);
            }
        }

        // 移除末尾的 5~6 位纯数字邮政编码 (如 311223, 310026)
        if (!parts.isEmpty()) {
            String last = parts.get(parts.size() - 1);
            if (last.matches("^\\d{5,6}$")) {
                parts.remove(parts.size() - 1);
            }
        }

        return TextUtils.join(", ", parts);
    }

    /**
     * 针对 Android 系统 Address 对象进行 POI 优先组合（建筑/机构排在最前，随后连接街道、区县与城市）
     */
    public static String formatAndroidAddress(Address addr) {
        if (addr == null) return null;

        String feature = addr.getFeatureName();
        String premises = addr.getPremises();
        String thoroughfare = addr.getThoroughfare();
        String subThoroughfare = addr.getSubThoroughfare();
        String subLocality = addr.getSubLocality();
        String locality = addr.getLocality();
        String admin = addr.getAdminArea();

        List<String> list = new ArrayList<>();

        // 1. POI / 建筑 / 机构 (优先排在最前)
        String poi = null;
        if (premises != null && !premises.trim().isEmpty()) {
            poi = premises.trim();
        } else if (feature != null && !feature.trim().isEmpty()) {
            String f = feature.trim();
            // 排除 featureName 仅仅是道路名或纯门牌号的情况
            if (!f.equals(thoroughfare) && !f.matches("^\\d+[号弄幢栋室]?$")) {
                poi = f;
            }
        }
        if (poi != null) {
            list.add(poi);
        }

        // 2. 门牌号
        if (subThoroughfare != null && !subThoroughfare.trim().isEmpty()) {
            list.add(subThoroughfare.trim());
        }

        // 3. 道路名
        if (thoroughfare != null && !thoroughfare.trim().isEmpty()) {
            list.add(thoroughfare.trim());
        }

        // 4. 区县 / 街道
        if (subLocality != null && !subLocality.trim().isEmpty()) {
            list.add(subLocality.trim());
        }

        // 5. 城市
        if (locality != null && !locality.trim().isEmpty() && !locality.equals(admin)) {
            list.add(locality.trim());
        }

        // 6. 省份
        if (admin != null && !admin.trim().isEmpty()) {
            list.add(admin.trim());
        }

        if (list.size() >= 2) {
            return TextUtils.join(", ", list);
        }

        // 回退到系统完整 addressLine(0)
        if (addr.getAddressLine(0) != null && !addr.getAddressLine(0).trim().isEmpty()) {
            return addr.getAddressLine(0).trim();
        }
        return null;
    }

    /**
     * 评估地址信息的丰富度（优先奖励 建筑、酒店、航站楼、公园、小区等具体 POI 词汇）
     */
    private static int evaluateRichness(String addr) {
        if (addr == null || addr.trim().isEmpty()) return 0;
        int score = addr.split(",").length * 3;
        String[] poiKeywords = {
                "酒店", "宾馆", "航站楼", "机场", "公园", "小区", "里", "大厦", "商厦",
                "商场", "中心", "广场", "医院", "学校", "大学", "中学", "小学",
                "火车站", "高铁站", "地铁站", "客运站", "馆", "园", "阁", "楼",
                "店", "院", "府", "城", "谷", "坊", "港", "基地", "大厦", "门", "湾"
        };
        for (String kw : poiKeywords) {
            if (addr.contains(kw)) {
                score += 15;
            }
        }
        return score;
    }

    /**
     * WGS84 坐标系转 GCJ02 火星坐标系（高德/腾讯标准算法）
     */
    public static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double dlat = transformLat(lng - 105.0, lat - 35.0);
        double dlng = transformLng(lng - 105.0, lat - 35.0);
        double radlat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radlat);
        magic = 1 - 0.00669342162296594323 * magic * magic;
        double sqrtmagic = Math.sqrt(magic);
        dlat = (dlat * 180.0) / ((6378245.0 * (1 - 0.00669342162296594323)) / (magic * sqrtmagic) * Math.PI);
        dlng = (dlng * 180.0) / (6378245.0 / sqrtmagic * Math.cos(radlat) * Math.PI);
        return new double[]{lng + dlng, lat + dlat};
    }

    private static boolean outOfChina(double lng, double lat) {
        return (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271);
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
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
