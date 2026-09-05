package com.downpour;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TitleActivity extends AppCompatActivity {

    private static final String TAG = "TitleActivity";
    private static final int REQUEST_CODE_ISO = 1001;

    private View contentContainer;
    private View btnStartGame;
    private TextView tvStartButton;
    private TextView tvGameStatus;
    private ImageView ivStatusIcon;
    private View btnSettings;
    private Button btnChangeIso;
    private TextView tvDriverStatus;
    private FrameLayout layoutLoading;
    private TextView tvLoadingText;
    private View fadeOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Allow edge-to-edge rendering behind display cutout/notch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            getWindow().setAttributes(lp);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        super.onCreate(savedInstanceState);

        // Landscape & screen on
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_title);

        // Hide navigation and system bars (immersive sticky)
        hideSystemUi();

        checkStoragePermissions();
        initViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();

        // Migrate old configurations and check if Turnip crashed during previous attempt
        GameConfigManager.ensureDriverPreferencesMigrated(this);
        if (GameConfigManager.checkAndRecoverTurnipCrash(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Recuperação de Inicialização")
                .setMessage("O jogo foi fechado pelo sistema devido a uma falha do driver Turnip (incompatibilidade / Device Lost no Android 15).\n\nO Driver Padrão do Sistema Qualcomm foi ativado automaticamente para garantir total estabilidade. Você já pode iniciar o jogo!")
                .setPositiveButton("OK", null)
                .show();
        }

        updateUiState();
        MenuMusicManager.getInstance().onActivityResumed(this);
        if (fadeOverlay != null && fadeOverlay.getVisibility() == View.VISIBLE) {
            fadeOverlay.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction(() -> {
                    fadeOverlay.setVisibility(View.GONE);
                    if (btnStartGame != null) btnStartGame.setEnabled(true);
                })
                .start();
        } else if (btnStartGame != null) {
            btnStartGame.setEnabled(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        MenuMusicManager.getInstance().onActivityPaused();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void hideSystemUi() {
        Window window = getWindow();
        if (window == null) return;

        try {
            View decorView = window.getDecorView();
            if (decorView != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.setDecorFitsSystemWindows(false);
                    WindowInsetsController controller = decorView.getWindowInsetsController();
                    if (controller != null) {
                        controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    }
                }

                int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN;
                decorView.setSystemUiVisibility(flags);
            }
        } catch (Throwable t) {
            Log.w(TAG, "hideSystemUi error: " + t.getMessage());
        }
    }

    private void initViews() {
        contentContainer = findViewById(R.id.content_container);
        if (contentContainer != null) {
            ViewCompat.setOnApplyWindowInsetsListener(contentContainer, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                float density = getResources().getDisplayMetrics().density;
                int basePadH = (int) (20 * density);
                int basePadV = (int) (14 * density);

                v.setPadding(
                    Math.max(basePadH, insets.left),
                    Math.max(basePadV, insets.top),
                    Math.max(basePadH, insets.right),
                    Math.max(basePadV, insets.bottom)
                );
                return windowInsets;
            });
        }

        btnStartGame = findViewById(R.id.btn_start_game);
        tvStartButton = findViewById(R.id.tv_start_button);
        tvGameStatus = findViewById(R.id.tv_game_status);
        ivStatusIcon = findViewById(R.id.iv_status_icon);
        btnSettings = findViewById(R.id.btn_settings);
        btnChangeIso = findViewById(R.id.btn_change_iso);
        tvDriverStatus = findViewById(R.id.tv_driver_status);
        layoutLoading = findViewById(R.id.layout_loading);
        tvLoadingText = findViewById(R.id.tv_loading_text);
        fadeOverlay = findViewById(R.id.fade_overlay);

        btnStartGame.setOnClickListener(v -> onStartClicked());

        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(TitleActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        btnChangeIso.setOnClickListener(v -> launchIsoPicker());

        findViewById(R.id.chip_gpu_driver).setOnClickListener(v -> {
            Intent intent = new Intent(TitleActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        updateUiState();
    }

    private void updateUiState() {
        boolean gameInstalled = GameConfigManager.isGameInstalled(this);

        if (gameInstalled) {
            tvStartButton.setText("INICIAR JOGO");
            tvGameStatus.setText("Dados do jogo prontos para iniciar");
            tvGameStatus.setTextColor(0xFF7EE787);
            ivStatusIcon.setImageResource(R.drawable.ic_check);
            ivStatusIcon.setColorFilter(0xFF7EE787);
            btnChangeIso.setVisibility(View.VISIBLE);
        } else {
            tvStartButton.setText("SELECIONAR ISO E INICIAR");
            tvGameStatus.setText("Nenhuma ISO carregada (Toque para selecionar a ISO)");
            tvGameStatus.setTextColor(0xFFFFA657);
            ivStatusIcon.setImageResource(R.drawable.ic_folder);
            ivStatusIcon.setColorFilter(0xFFFFA657);
            btnChangeIso.setVisibility(View.GONE);
        }

        // Driver status
        if (tvDriverStatus != null) {
            String desc = GameConfigManager.getActiveDriverDescription(this);
            tvDriverStatus.setText("GPU: " + desc);
            boolean turnipActive = GameConfigManager.isTurnipEnabled(this) && GameConfigManager.hasCustomDriverInstalled(this);
            tvDriverStatus.setTextColor(turnipActive ? 0xFF7EE787 : 0xFF8B949E);
        }
    }

    private void onStartClicked() {
        if (GameConfigManager.isGameInstalled(this)) {
            // Game is already extracted/installed, boot directly
            launchGame(null);
        } else {
            // ISO not selected yet, launch ISO picker
            launchIsoPicker();
        }
    }

    private void launchIsoPicker() {
        Toast.makeText(this, "Selecione o arquivo de imagem ISO do Silent Hill: Downpour (Xbox 360)", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_ISO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_ISO) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                handlePickedIsoUri(data.getData());
            } else {
                Toast.makeText(this, "Seleção de ISO cancelada.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void handlePickedIsoUri(Uri uri) {
        layoutLoading.setVisibility(View.VISIBLE);
        tvLoadingText.setText("Processando imagem ISO selecionada... Aguarde.");

        new Thread(() -> {
            try {
                String fileName = getFileName(uri);
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "downpour_game.iso";
                }

                // If file is directly on disk
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    String path = uri.getPath();
                    runOnUiThread(() -> {
                        layoutLoading.setVisibility(View.GONE);
                        launchGame(path);
                    });
                    return;
                }

                // Copy to cache dir
                File cacheTarget = new File(getCacheDir(), fileName);
                Log.i(TAG, "Copying ISO content URI to cache: " + cacheTarget.getAbsolutePath());

                runOnUiThread(() -> tvLoadingText.setText("Copiando " + cacheTarget.getName() + " para o cache do jogo..."));

                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(cacheTarget)) {
                    if (in == null) {
                        runOnUiThread(() -> {
                            layoutLoading.setVisibility(View.GONE);
                            Toast.makeText(this, "Erro ao abrir o arquivo ISO selecionado.", Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                    byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }

                String resolvedPath = cacheTarget.getAbsolutePath();
                runOnUiThread(() -> {
                    layoutLoading.setVisibility(View.GONE);
                    Toast.makeText(this, "ISO carregada com sucesso! Iniciando jogo...", Toast.LENGTH_SHORT).show();
                    launchGame(resolvedPath);
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to resolve ISO file: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    layoutLoading.setVisibility(View.GONE);
                    Toast.makeText(this, "Falha ao processar arquivo ISO: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void launchGame(final String isoPath) {
        if (btnStartGame != null) {
            btnStartGame.setEnabled(false);
        }
        if (btnSettings != null) {
            btnSettings.setEnabled(false);
        }

        // Track whether Turnip was active for crash recovery
        boolean turnipRequested = GameConfigManager.isTurnipEnabled(this) && GameConfigManager.hasCustomDriverInstalled(this);
        GameConfigManager.markTurnipLaunchInProgress(this, turnipRequested);

        // Fades out music volume smoothly over 1400ms in sync with the screen fade transition
        MenuMusicManager.getInstance().fadeOutAndStop(1400, null);

        if (fadeOverlay != null) {
            fadeOverlay.setVisibility(View.VISIBLE);
            fadeOverlay.setAlpha(0f);
            // Transição cinematográfica: tela vai se apagando devagar (1.4s)
            fadeOverlay.animate()
                .alpha(1.0f)
                .setDuration(1400)
                .withEndAction(() -> {
                    Intent intent = new Intent(TitleActivity.this, DownpourActivity.class);
                    if (isoPath != null && !isoPath.isEmpty()) {
                        intent.putExtra(GameConfigManager.EXTRA_ISO_PATH, isoPath);
                    }
                    startActivity(intent);
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                })
                .start();
        } else {
            Intent intent = new Intent(TitleActivity.this, DownpourActivity.class);
            if (isoPath != null && !isoPath.isEmpty()) {
                intent.putExtra(GameConfigManager.EXTRA_ISO_PATH, isoPath);
            }
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }
        return result;
    }

    private void checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        }
    }
}
