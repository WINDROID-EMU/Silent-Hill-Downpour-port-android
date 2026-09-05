package com.downpour;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_DRIVER_ZIP = 2001;

    private SwitchCompat switchUseTurnip;
    private SwitchCompat switchTurboMode;
    private TextView tvDriverStatus;
    private Button btnInstallDriverZip;
    private Button btnResetSystemDriver;

    private Spinner spinnerResScale;
    private Spinner spinnerFpsLimit;
    private SwitchCompat switchVsync;
    private Spinner spinnerPresentEffect;
    private Button btnApplyQuickSettings;

    private SwitchCompat switchVirtualController;
    private Spinner spinnerControllerOpacity;

    private EditText etTomlContent;
    private Button btnSaveToml;
    private Button btnReloadToml;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        initViews();
        setupDriverSection();
        setupControllerSection();
        setupTomlSection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MenuMusicManager.getInstance().onActivityResumed(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        MenuMusicManager.getInstance().onActivityPaused();
    }

    private void initViews() {
        View btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        switchUseTurnip = findViewById(R.id.switch_use_turnip);
        switchTurboMode = findViewById(R.id.switch_turbo_mode);
        tvDriverStatus = findViewById(R.id.tv_settings_driver_name);
        btnInstallDriverZip = findViewById(R.id.btn_install_driver_zip);
        btnResetSystemDriver = findViewById(R.id.btn_reset_system_driver);

        spinnerResScale = findViewById(R.id.spinner_resolution_scale);
        spinnerFpsLimit = findViewById(R.id.spinner_fps_limit);
        switchVsync = findViewById(R.id.switch_vsync);
        spinnerPresentEffect = findViewById(R.id.spinner_present_effect);
        btnApplyQuickSettings = findViewById(R.id.btn_apply_quick_settings);

        etTomlContent = findViewById(R.id.et_toml_content);
        btnSaveToml = findViewById(R.id.btn_save_toml);
        btnReloadToml = findViewById(R.id.btn_reload_toml);

        switchVirtualController = findViewById(R.id.switch_virtual_controller);
        spinnerControllerOpacity = findViewById(R.id.spinner_controller_opacity);
    }

    private void setupDriverSection() {
        boolean useTurnip = GameConfigManager.isTurnipEnabled(this);
        boolean turbo = GameConfigManager.isTurboEnabled(this);

        switchUseTurnip.setChecked(useTurnip);
        switchTurboMode.setChecked(turbo);
        updateDriverStatusText();

        switchUseTurnip.setOnCheckedChangeListener((buttonView, isChecked) -> {
            GameConfigManager.setTurnipEnabled(this, isChecked);
            updateDriverStatusText();
            String msg = isChecked ? "Driver Turnip ativado para o próximo início!" : "Driver do Sistema Qualcomm ativado!";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        switchTurboMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            GameConfigManager.setTurboEnabled(this, isChecked);
        });

        btnInstallDriverZip.setOnClickListener(v -> launchDriverPicker());

        btnResetSystemDriver.setOnClickListener(v -> {
            GameConfigManager.setTurnipEnabled(this, false);
            switchUseTurnip.setChecked(false);
            updateDriverStatusText();
            Toast.makeText(this, "Driver do Sistema (Qualcomm OEM) definido como padrão.", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateDriverStatusText() {
        if (tvDriverStatus != null) {
            tvDriverStatus.setText(GameConfigManager.getActiveDriverDescription(this));
            boolean active = GameConfigManager.isTurnipEnabled(this) && GameConfigManager.hasCustomDriverInstalled(this);
            tvDriverStatus.setTextColor(active ? 0xFF7EE787 : 0xFF8B949E);
        }
    }

    private void launchDriverPicker() {
        Toast.makeText(this, "Selecione o arquivo ZIP do driver Turnip (AdrenoTools)", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_DRIVER_ZIP);
    }

    private void setupControllerSection() {
        android.content.SharedPreferences prefs = getSharedPreferences(GameConfigManager.PREF_NAME, MODE_PRIVATE);
        boolean showVc = prefs.getBoolean("show_virtual_controller", true);
        switchVirtualController.setChecked(showVc);

        String[] opacities = new String[] { "70% (Padrão)", "100% (Totalmente Visível)", "50% (Sutil)", "25% (Muito Transparente)" };
        ArrayAdapter<String> opAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, opacities);
        spinnerControllerOpacity.setAdapter(opAdapter);

        int currentOp = prefs.getInt("controller_opacity", 70);
        if (currentOp == 100) spinnerControllerOpacity.setSelection(1);
        else if (currentOp == 50) spinnerControllerOpacity.setSelection(2);
        else if (currentOp == 25) spinnerControllerOpacity.setSelection(3);
        else spinnerControllerOpacity.setSelection(0);

        switchVirtualController.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("show_virtual_controller", isChecked).apply();
            Toast.makeText(this, isChecked ? "Controles virtuais ativados no jogo." : "Controles virtuais desativados.", Toast.LENGTH_SHORT).show();
        });

        spinnerControllerOpacity.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int op = (position == 1) ? 100 : (position == 2 ? 50 : (position == 3 ? 25 : 70));
                prefs.edit().putInt("controller_opacity", op).apply();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_DRIVER_ZIP && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            Toast.makeText(this, "Extraindo pacote de driver Turnip...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    String driverName = GameConfigManager.extractDriverZip(this, uri);
                    runOnUiThread(() -> {
                        if (driverName != null) {
                            switchUseTurnip.setChecked(true);
                            updateDriverStatusText();
                            Toast.makeText(this, "Driver Turnip '" + driverName + "' instalado com sucesso!", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "Nenhuma biblioteca Vulkan .so encontrada no arquivo ZIP.", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Erro ao extrair driver: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }
    }

    private void setupTomlSection() {
        // Populate Spinners
        String[] resScales = new String[] { "1x (720p - Recomendado Mobile)", "2x (1440p)" };
        ArrayAdapter<String> resAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, resScales);
        spinnerResScale.setAdapter(resAdapter);

        String[] fpsLimits = new String[] { "60 FPS (Padrão)", "30 FPS (Economia)", "Ilimitado" };
        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fpsLimits);
        spinnerFpsLimit.setAdapter(fpsAdapter);

        String[] presentEffects = new String[] { "fsr3 (AMD FidelityFX)", "fxaa (Anti-Aliasing Rápido)", "none (Nenhum)" };
        ArrayAdapter<String> presentAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presentEffects);
        spinnerPresentEffect.setAdapter(presentAdapter);

        switchVsync.setChecked(true);

        // Load TOML Content into EditText
        String currentToml = GameConfigManager.loadTomlContent(this);
        etTomlContent.setText(currentToml);

        // Save Raw TOML
        btnSaveToml.setOnClickListener(v -> {
            String newContent = etTomlContent.getText().toString();
            boolean ok = GameConfigManager.saveTomlContent(this, newContent);
            if (ok) {
                Toast.makeText(this, "Arquivo downpour.toml salvo com sucesso!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Falha ao salvar downpour.toml.", Toast.LENGTH_LONG).show();
            }
        });

        // Reload / Reset Default TOML
        btnReloadToml.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("Restaurar Configurações Padrão")
                .setMessage("Deseja substituir as alterações atuais pelo arquivo padrão otimizado?")
                .setPositiveButton("Sim, Restaurar", (dialog, which) -> {
                    String def = GameConfigManager.getDefaultTomlContent(this);
                    etTomlContent.setText(def);
                    GameConfigManager.saveTomlContent(this, def);
                    Toast.makeText(this, "Configurações padrão restauradas!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
        });

        // Quick Settings Apply Button
        btnApplyQuickSettings.setOnClickListener(v -> {
            String toml = etTomlContent.getText().toString();

            // Resolution Scale
            int resScaleVal = spinnerResScale.getSelectedItemPosition() == 1 ? 2 : 1;
            toml = GameConfigManager.updateOrAddTomlKey(toml, "resolution_scale", String.valueOf(resScaleVal));

            // FPS Limit
            int fpsPos = spinnerFpsLimit.getSelectedItemPosition();
            if (fpsPos == 0) {
                toml = GameConfigManager.updateOrAddTomlKey(toml, "d3d12_present_frame_limiter", "true");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "d3d12_present_frame_limiter_fps", "60.0");
            } else if (fpsPos == 1) {
                toml = GameConfigManager.updateOrAddTomlKey(toml, "d3d12_present_frame_limiter", "true");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "d3d12_present_frame_limiter_fps", "30.0");
            } else {
                toml = GameConfigManager.updateOrAddTomlKey(toml, "d3d12_present_frame_limiter", "false");
            }

            // VSync
            toml = GameConfigManager.updateOrAddTomlKey(toml, "vsync", switchVsync.isChecked() ? "true" : "false");

            // Present Effect
            int effPos = spinnerPresentEffect.getSelectedItemPosition();
            String effName = (effPos == 0) ? "'fsr3'" : (effPos == 1 ? "'fxaa'" : "'none'");
            toml = GameConfigManager.updateOrAddTomlKey(toml, "present_effect", effName);

            etTomlContent.setText(toml);
            GameConfigManager.saveTomlContent(this, toml);
            Toast.makeText(this, "Ajustes rápidos aplicados ao downpour.toml!", Toast.LENGTH_SHORT).show();
        });
    }
}
