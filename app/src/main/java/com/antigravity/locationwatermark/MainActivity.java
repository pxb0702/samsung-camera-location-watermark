package com.antigravity.locationwatermark;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQ_CODE = 200;

    private SwitchCompat switchService;
    private TextView tvStatusDesc;
    private TextView tvLocationHint;
    private EditText etDeviceModel;
    private EditText etAmapKey;
    private Button btnGrantPermissions;
    private Button btnPickPhotoTest;
    private ImageView imgPreview;
    private TextView tvResultInfo;
    private ProgressBar progressBar;
    private RadioGroup rgInterval;
    private RadioButton rbIntervalAlways;
    private RadioButton rbInterval1m;
    private RadioButton rbInterval2m;
    private RadioButton rbInterval5m;
    private RadioButton rbInterval10m;

    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> pickPhotoLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    processTestPhoto(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("WatermarkPrefs", Context.MODE_PRIVATE);

        initViews();
        checkAndApplyPermissions();
        updateServiceState();
    }

    private void initViews() {
        switchService = findViewById(R.id.switch_service);
        tvStatusDesc = findViewById(R.id.tv_status_desc);
        tvLocationHint = findViewById(R.id.tv_location_hint);
        etDeviceModel = findViewById(R.id.et_device_model);
        etAmapKey = findViewById(R.id.et_amap_key);
        btnGrantPermissions = findViewById(R.id.btn_grant_permissions);
        btnPickPhotoTest = findViewById(R.id.btn_pick_photo_test);
        imgPreview = findViewById(R.id.img_preview);
        tvResultInfo = findViewById(R.id.tv_result_info);
        progressBar = findViewById(R.id.progress_bar);

        rgInterval = findViewById(R.id.rg_interval);
        rbIntervalAlways = findViewById(R.id.rb_interval_always);
        rbInterval1m = findViewById(R.id.rb_interval_1m);
        rbInterval2m = findViewById(R.id.rb_interval_2m);
        rbInterval5m = findViewById(R.id.rb_interval_5m);
        rbInterval10m = findViewById(R.id.rb_interval_10m);

        int savedInterval = prefs.getInt("watermark_interval_seconds", 0);
        if (savedInterval == 60) {
            rbInterval1m.setChecked(true);
        } else if (savedInterval == 120) {
            rbInterval2m.setChecked(true);
        } else if (savedInterval == 300) {
            rbInterval5m.setChecked(true);
        } else if (savedInterval == 600) {
            rbInterval10m.setChecked(true);
        } else {
            rbIntervalAlways.setChecked(true);
        }

        rgInterval.setOnCheckedChangeListener((group, checkedId) -> {
            int seconds = 0;
            if (checkedId == R.id.rb_interval_1m) {
                seconds = 60;
            } else if (checkedId == R.id.rb_interval_2m) {
                seconds = 120;
            } else if (checkedId == R.id.rb_interval_5m) {
                seconds = 300;
            } else if (checkedId == R.id.rb_interval_10m) {
                seconds = 600;
            } else {
                seconds = 0;
            }
            prefs.edit().putInt("watermark_interval_seconds", seconds).apply();
            notifyServiceIntervalChanged();
            updateServiceState();
        });

        etDeviceModel.setText(WatermarkProcessor.getDeviceModel(this));
        etDeviceModel.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                prefs.edit().putString("custom_device_model", s.toString().trim()).apply();
            }
        });

        String savedAmapKey = prefs.getString("amap_api_key", "");
        etAmapKey.setText(savedAmapKey);
        etAmapKey.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                prefs.edit().putString("amap_api_key", s.toString().trim()).apply();
            }
        });

        switchService.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!hasRequiredPermissions()) {
                    Toast.makeText(MainActivity.this, "请先授予必要权限", Toast.LENGTH_SHORT).show();
                    switchService.setChecked(false);
                    requestAppPermissions();
                    return;
                }
                startWatermarkService();
            } else {
                stopWatermarkService();
            }
        });

        btnGrantPermissions.setOnClickListener(v -> requestAppPermissions());

        btnPickPhotoTest.setOnClickListener(v -> {
            if (!hasRequiredPermissions()) {
                requestAppPermissions();
                return;
            }
            pickPhotoLauncher.launch("image/*");
        });
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAppPermissions() {
        List<String> list = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES);
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            list.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        }

        list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        ActivityCompat.requestPermissions(this, list.toArray(new String[0]), PERMISSION_REQ_CODE);
    }

    private void checkAndApplyPermissions() {
        if (hasRequiredPermissions()) {
            btnGrantPermissions.setVisibility(View.GONE);
            tvLocationHint.setText("✅ 权限已就绪，三星相机开启“位置标签”即可自动叠加详细地址与时间！");
        } else {
            btnGrantPermissions.setVisibility(View.VISIBLE);
            tvLocationHint.setText("⚠️ 需要授予媒体与地理位置权限，以读取照片的 GPS 坐标并解析中文地址。");
        }
    }

    private void updateServiceState() {
        boolean isRunning = WatermarkService.isRunning();
        switchService.setChecked(isRunning);
        if (isRunning) {
            int interval = prefs.getInt("watermark_interval_seconds", 0);
            String modeDesc;
            if (interval == 60) {
                modeDesc = "1分钟间隔";
            } else if (interval == 120) {
                modeDesc = "2分钟间隔";
            } else if (interval == 300) {
                modeDesc = "5分钟间隔";
            } else if (interval == 600) {
                modeDesc = "10分钟间隔";
            } else {
                modeDesc = "常开模式";
            }
            tvStatusDesc.setText("状态：已开启 (" + modeDesc + " 监听中)");
        } else {
            tvStatusDesc.setText("状态：未运行 (请开启服务以自动添加水印)");
        }
    }

    private void notifyServiceIntervalChanged() {
        if (WatermarkService.isRunning()) {
            Intent intent = new Intent(this, WatermarkService.class);
            intent.setAction(WatermarkService.ACTION_UPDATE_INTERVAL);
            try {
                startService(intent);
            } catch (Exception ignored) {}
        }
    }

    private void startWatermarkService() {
        Intent intent = new Intent(this, WatermarkService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        prefs.edit().putBoolean("service_enabled", true).apply();
        updateServiceState();
        Toast.makeText(this, "水印监听服务已开启", Toast.LENGTH_SHORT).show();
    }

    private void stopWatermarkService() {
        Intent intent = new Intent(this, WatermarkService.class);
        stopService(intent);
        prefs.edit().putBoolean("service_enabled", false).apply();
        updateServiceState();
        Toast.makeText(this, "水印监听服务已停止", Toast.LENGTH_SHORT).show();
    }

    private void processTestPhoto(Uri uri) {
        progressBar.setVisibility(View.VISIBLE);
        tvResultInfo.setText("正在解析 EXIF GPS 并逆编码生成地点水印...");

        executor.execute(() -> {
            WatermarkProcessor.WatermarkResult result = WatermarkProcessor.processPhoto(this, uri);
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                if (result.success) {
                    imgPreview.setImageURI(result.savedUri);
                    tvResultInfo.setText("✅ 水印添加成功！已保存至相册\n🕒 第一行：" + result.timeString + "   " + result.deviceModel + "\n📍 第二行：" + result.address);
                } else {
                    tvResultInfo.setText("❌ 处理失败: " + result.errorMessage);
                }
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        checkAndApplyPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndApplyPermissions();
        updateServiceState();
    }
}
