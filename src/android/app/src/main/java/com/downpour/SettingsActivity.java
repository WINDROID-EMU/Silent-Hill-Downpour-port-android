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
    private Spinner spinnerVulkanPresentMode;
    private Spinner spinnerPresentEffect;
    private SwitchCompat switchAsyncShaders;
    private Spinner spinnerShaderThreads;
    private Spinner spinnerTextureCache;
    private TextView tvShaderCacheSize;
    private Button btnClearShaderCache;
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
        spinnerVulkanPresentMode = findViewById(R.id.spinner_vulkan_present_mode);
        spinnerPresentEffect = findViewById(R.id.spinner_present_effect);
        switchAsyncShaders = findViewById(R.id.switch_async_shaders);
        spinnerShaderThreads = findViewById(R.id.spinner_shader_threads);
        spinnerTextureCache = findViewById(R.id.spinner_texture_cache);
        tvShaderCacheSize = findViewById(R.id.tv_shader_cache_size);
        btnClearShaderCache = findViewById(R.id.btn_clear_shader_cache);
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
            if (isChecked) {
                GameConfigManager.markTurnipLaunchInProgress(this, false);
            }
            updateDriverStatusText();
            String msg = isChecked ? "Driver Turnip ativado!" : "Driver do Sistema Qualcomm ativado (Estável)!";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        switchTurboMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            GameConfigManager.setTurboEnabled(this, isChecked);
        });

        btnInstallDriverZip.setOnClickListener(v -> launchDriverPicker());

        btnResetSystemDriver.setOnClickListener(v -> {
            GameConfigManager.setTurnipEnabled(this, false);
            GameConfigManager.markTurnipLaunchInProgress(this, false);
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

    private void updateShaderCacheDisplay() {
        if (tvShaderCacheSize == null) return;
        long bytes = GameConfigManager.getCacheSizeBytes(this);
        double mb = bytes / (1024.0 * 1024.0);
        tvShaderCacheSize.setText(String.format(java.util.Locale.US, "%.2f MB", mb));
    }

    private void syncQuickSettingsFromToml(String toml) {
        // Resolution & Scale (Internal Render Target)
        String resScale = GameConfigManager.getTomlValue(toml, "resolution_scale", "1");
        String resPreset = GameConfigManager.getTomlValue(toml, "resolution", "720p").replace("'", "").replace("\"", "").trim().toLowerCase();
        String videoH = GameConfigManager.getTomlValue(toml, "video_mode_height", "720").trim();
        String winH = GameConfigManager.getTomlValue(toml, "window_height", "720").trim();

        if ("2".equals(resScale)) {
            spinnerResScale.setSelection(4); // 1440p (2x SSAA)
        } else if ("240p".equals(resPreset) || "426x240".equals(resPreset) || "240".equals(videoH) || "240".equals(winH)) {
            spinnerResScale.setSelection(2); // 240p
        } else if ("480p".equals(resPreset) || "854x480".equals(resPreset) || "640x480".equals(resPreset) || "480".equals(videoH) || "480".equals(winH)) {
            spinnerResScale.setSelection(1); // 480p
        } else if ("1080p".equals(resPreset) || "1920x1080".equals(resPreset) || "1080".equals(videoH) || "1080".equals(winH)) {
            spinnerResScale.setSelection(3); // 1080p
        } else {
            spinnerResScale.setSelection(0); // 720p (Padrão)
        }

        // FPS Limit
        String limiterEnabled = GameConfigManager.getTomlValue(toml, "d3d12_present_frame_limiter", "true");
        String fps = GameConfigManager.getTomlValue(toml, "d3d12_present_frame_limiter_fps", "60.0");
        if ("false".equalsIgnoreCase(limiterEnabled)) {
            spinnerFpsLimit.setSelection(2); // Ilimitado
        } else if ("30.0".equals(fps) || "30".equals(fps)) {
            spinnerFpsLimit.setSelection(1); // 30 FPS
        } else {
            spinnerFpsLimit.setSelection(0); // 60 FPS
        }

        // VSync
        boolean vsyncVal = Boolean.parseBoolean(GameConfigManager.getTomlValue(toml, "vsync", "true"));
        switchVsync.setChecked(vsyncVal);

        // Vulkan Present Mode
        boolean modeImm = Boolean.parseBoolean(GameConfigManager.getTomlValue(toml, "vulkan_allow_present_mode_immediate", "false"));
        boolean modeMbox = Boolean.parseBoolean(GameConfigManager.getTomlValue(toml, "vulkan_allow_present_mode_mailbox", "false"));
        boolean modeFifoRel = Boolean.parseBoolean(GameConfigManager.getTomlValue(toml, "vulkan_allow_present_mode_fifo_relaxed", "false"));
        if (modeMbox) {
            spinnerVulkanPresentMode.setSelection(1); // Mailbox
        } else if (modeImm) {
            spinnerVulkanPresentMode.setSelection(2); // Immediate
        } else if (modeFifoRel) {
            spinnerVulkanPresentMode.setSelection(3); // FIFO Relaxed
        } else {
            spinnerVulkanPresentMode.setSelection(0); // FIFO Seguro
        }

        // Present Effect / Upscaler
        String effect = GameConfigManager.getTomlValue(toml, "present_effect", "fsr3").replace("'", "").replace("\"", "");
        if ("fxaa".equalsIgnoreCase(effect)) {
            spinnerPresentEffect.setSelection(1);
        } else if ("none".equalsIgnoreCase(effect)) {
            spinnerPresentEffect.setSelection(2);
        } else {
            spinnerPresentEffect.setSelection(0); // fsr3
        }

        // Async Shader Compilation
        boolean asyncShaders = Boolean.parseBoolean(GameConfigManager.getTomlValue(toml, "async_shader_compilation", "true"));
        switchAsyncShaders.setChecked(asyncShaders);

        // Pipeline Threads
        String threads = GameConfigManager.getTomlValue(toml, "vulkan_pipeline_creation_threads", "4");
        if ("2".equals(threads)) spinnerShaderThreads.setSelection(1);
        else if ("6".equals(threads)) spinnerShaderThreads.setSelection(2);
        else if ("8".equals(threads)) spinnerShaderThreads.setSelection(3);
        else spinnerShaderThreads.setSelection(0); // 4 threads

        // Texture Cache Limits
        String softLimit = GameConfigManager.getTomlValue(toml, "texture_cache_memory_limit_soft", "256");
        if ("192".equals(softLimit)) spinnerTextureCache.setSelection(1);
        else if ("384".equals(softLimit)) spinnerTextureCache.setSelection(2);
        else spinnerTextureCache.setSelection(0); // 256/384 MB
    }

    private void setupTomlSection() {
        String[] resScales = new String[] {
            "720p (1280x720 - Render Padrão Recomendado)",
            "480p (854x480 - Render Econômico / Mais FPS)",
            "240p (426x240 - Render Extremo / 60 FPS)",
            "1080p (1920x1080 - Render Alta Definição)",
            "1440p (2560x1440 - 2x SSAA Super Nitidez)"
        };
        ArrayAdapter<String> resAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, resScales);
        spinnerResScale.setAdapter(resAdapter);

        String[] fpsLimits = new String[] { "60 FPS (Padrão)", "30 FPS (Economia)", "Ilimitado" };
        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fpsLimits);
        spinnerFpsLimit.setAdapter(fpsAdapter);

        String[] presentModes = new String[] {
            "Padrão / FIFO (VSync Seguro)",
            "Mailbox (Triple Buffering / Sem Tearing)",
            "Imediato (Destravado / Sem VSync)",
            "FIFO Relaxado (VSync Adaptativo)"
        };
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presentModes);
        spinnerVulkanPresentMode.setAdapter(modeAdapter);

        String[] presentEffects = new String[] { "fsr3 (AMD FidelityFX)", "fxaa (Anti-Aliasing Rápido)", "none (Nenhum)" };
        ArrayAdapter<String> presentAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presentEffects);
        spinnerPresentEffect.setAdapter(presentAdapter);

        String[] threadOptions = new String[] {
            "4 Threads (Padrão Recomendado)",
            "2 Threads (Economia de Bateria)",
            "6 Threads (CPUs Rápidas)",
            "8 Threads (Máximo Desempenho)"
        };
        ArrayAdapter<String> threadAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, threadOptions);
        spinnerShaderThreads.setAdapter(threadAdapter);

        String[] cacheOptions = new String[] {
            "Padrão Móvel (256 MB soft / 384 MB hard)",
            "Econômico (192 MB soft / 256 MB hard - 4GB RAM)",
            "Alto Desempenho (384 MB soft / 512 MB hard - 8GB+ RAM)"
        };
        ArrayAdapter<String> cacheAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, cacheOptions);
        spinnerTextureCache.setAdapter(cacheAdapter);

        // Load TOML Content into EditText
        String currentToml = GameConfigManager.loadTomlContent(this);
        etTomlContent.setText(currentToml);
        syncQuickSettingsFromToml(currentToml);

        // Shader Cache Management
        updateShaderCacheDisplay();
        btnClearShaderCache.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("Limpar Cache de Shaders")
                .setMessage("Deseja apagar os shaders SPIR-V e pipelines salvos em disco?\n\nIsso pode corrigir artefatos visuais ou travamentos após atualizar o driver Turnip. Os shaders serão recompilados na próxima execução.")
                .setPositiveButton("Sim, Limpar", (dialog, which) -> {
                    boolean ok = GameConfigManager.clearCache(this);
                    updateShaderCacheDisplay();
                    if (ok) {
                        Toast.makeText(this, "Cache de shaders limpo com sucesso!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Falha ao limpar cache.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
        });

        // Save Raw TOML
        btnSaveToml.setOnClickListener(v -> {
            String newContent = etTomlContent.getText().toString();
            boolean ok = GameConfigManager.saveTomlContent(this, newContent);
            if (ok) {
                syncQuickSettingsFromToml(newContent);
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
                    syncQuickSettingsFromToml(def);
                    Toast.makeText(this, "Configurações padrão restauradas!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
        });

        // Quick Settings Apply Button
        btnApplyQuickSettings.setOnClickListener(v -> {
            String toml = etTomlContent.getText().toString();

            // Resolution & Resolution Scale (Internal Render Target in UE3)
            int resPos = spinnerResScale.getSelectedItemPosition();
            if (resPos == 1) { // 480p
                toml = GameConfigManager.updateOrAddTomlKey(toml, "video_mode_width", "854");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "video_mode_height", "480");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "resolution", "'854x480'");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "resolution_scale", "1");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "window_width", "1280");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "window_height", "720");
            } else if (resPos == 2) { // 240p
                toml = GameConfigManager.updateOrAddTomlKey(toml, "video_mode_width", "426");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "video_mode_height", "240");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "resolution", "'426x240'");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "resolution_scale", "1");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "window_width", "1280");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "window_height", "720");
            } else if (resPos == 3) { // 1080p
                toml = GameConfigManager.updateOrAddTomlKey(toml, "video_mode_width", "1920");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "video_mode_height", "1080");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "resolution", "'1080p'");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "resolution_scale", "1");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "window_width", "1280");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "window_height", "720");
            } else if (resPos == 4) { // 1440p (2x SSAA)
                toml = GameConfigManager.updateOrAddTomlKey(toml, "video_mode_width", "1280");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "video_mode_height", "720");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "resolution", "'720p'");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "resolution_scale", "2");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "window_width", "1280");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "window_height", "720");
            } else { // 720p (Padrão)
                toml = GameConfigManager.updateOrAddTomlKey(toml, "video_mode_width", "1280");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "video_mode_height", "720");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "resolution", "'720p'");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "resolution_scale", "1");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "window_width", "1280");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "window_height", "720");
            }

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

            // Vulkan Present Mode
            int modePos = spinnerVulkanPresentMode.getSelectedItemPosition();
            if (modePos == 1) { // Mailbox
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_mailbox", "true");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_immediate", "false");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_fifo_relaxed", "false");
            } else if (modePos == 2) { // Immediate
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_mailbox", "false");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_immediate", "true");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_fifo_relaxed", "false");
            } else if (modePos == 3) { // FIFO Relaxed
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_mailbox", "false");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_immediate", "false");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_fifo_relaxed", "true");
            } else { // Standard FIFO
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_mailbox", "false");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_immediate", "false");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_allow_present_mode_fifo_relaxed", "false");
            }

            // Present Effect
            int effPos = spinnerPresentEffect.getSelectedItemPosition();
            String effName = (effPos == 0) ? "'fsr3'" : (effPos == 1 ? "'fxaa'" : "'none'");
            toml = GameConfigManager.updateOrAddTomlKey(toml, "present_effect", effName);

            // Async Shader Compilation & Pipeline Threads
            toml = GameConfigManager.updateOrAddTomlKey(toml, "async_shader_compilation", switchAsyncShaders.isChecked() ? "true" : "false");
            toml = GameConfigManager.updateOrAddTomlKey(toml, "store_shaders", "true");

            int threadPos = spinnerShaderThreads.getSelectedItemPosition();
            String threadCount = (threadPos == 1) ? "2" : (threadPos == 2 ? "6" : (threadPos == 3 ? "8" : "4"));
            toml = GameConfigManager.updateOrAddTomlKey(toml, "vulkan_pipeline_creation_threads", threadCount);

            // Texture Cache Limits
            int cachePos = spinnerTextureCache.getSelectedItemPosition();
            if (cachePos == 1) { // Eco
                toml = GameConfigManager.updateOrAddTomlKey(toml, "texture_cache_memory_limit_soft", "192");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "texture_cache_memory_limit_hard", "256");
            } else if (cachePos == 2) { // High
                toml = GameConfigManager.updateOrAddTomlKey(toml, "texture_cache_memory_limit_soft", "384");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "texture_cache_memory_limit_hard", "512");
            } else { // Default mobile
                toml = GameConfigManager.updateOrAddTomlKey(toml, "texture_cache_memory_limit_soft", "256");
                toml = GameConfigManager.updateOrAddTomlKey(toml, "texture_cache_memory_limit_hard", "384");
            }

            etTomlContent.setText(toml);
            GameConfigManager.saveTomlContent(this, toml);
            Toast.makeText(this, "Ajustes rápidos aplicados ao downpour.toml!", Toast.LENGTH_SHORT).show();
        });
    }
}
