package com.downpour;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DownpourActivity extends SDLActivity {

    private static final String TAG = "DownpourActivity";
    private static final int REQUEST_CODE_ISO = 1001;
    private static final int REQUEST_CODE_TU = 1002;
    private static final int REQUEST_CODE_DRIVER_ZIP = 1003;

    private static final String PREF_NAME = "downpour_settings";
    private static final String PREF_USE_TURNIP = "use_turnip";
    private static final String PREF_DRIVER_NAME = "driver_name";
    private static final String PREF_TURBO = "turbo_mode";

    private VirtualControllerLayout mVirtualController;

    // Native callbacks for Android bridge
    private native void nativeInit(String internalDir, String externalDir);
    private native void nativeSetDriverConfig(String driverDir, String driverName, String hookLibDir, boolean useTurnip, boolean enableTurbo, boolean disableDebug);
    private static native void nativeOnIsoPicked(String path);
    private static native void nativeOnTuFilePicked(String path);

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "c++_shared",
            "rexruntimerd",
            "rexgpu-xenosrd",
            "downpour"
        };
    }

    @Override
    protected String getMainFunction() {
        return "SDL_main";
    }

    @Override
    protected String getMainSharedObject() {
        return getApplicationInfo().nativeLibraryDir + "/libdownpour.so";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Configure edge-to-edge display cutout before window creation
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

        // Force landscape orientation (allowing 180 flip)
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // Keep screen on during gameplay and cutscenes
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Enforce true edge-to-edge fullscreen (no virtual buttons or status bar)
        hideSystemUi();
        setWindowStyle(true);

        // Enable Sustained Performance Mode to prevent thermal throttling clock drops
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isSustainedPerformanceModeSupported()) {
                getWindow().setSustainedPerformanceMode(true);
                Log.i(TAG, "Android Sustained Performance Mode activated");
            }
        }

        // Lock display to the highest supported refresh rate (e.g. 120Hz / 90Hz) without changing resolution
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Display display = getDisplay();
            if (display != null) {
                Display.Mode currentMode = display.getMode();
                Display.Mode[] modes = display.getSupportedModes();
                Display.Mode bestMode = null;
                for (Display.Mode m : modes) {
                    if (currentMode != null) {
                        if (m.getPhysicalWidth() != currentMode.getPhysicalWidth() ||
                            m.getPhysicalHeight() != currentMode.getPhysicalHeight()) {
                            continue;
                        }
                    }
                    if (bestMode == null || m.getRefreshRate() > bestMode.getRefreshRate()) {
                        bestMode = m;
                    }
                }
                if (bestMode != null && bestMode.getRefreshRate() > 60.0f) {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.preferredDisplayModeId = bestMode.getModeId();
                    getWindow().setAttributes(lp);
                    Log.i(TAG, "Locked display refresh rate to " + bestMode.getRefreshRate() + "Hz (mode " + bestMode.getModeId() + ") preserving resolution");
                }
            }
        }

        // Query native audio parameters to optimize buffer sizes
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            String nativeSampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            String nativeBufferSize = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            Log.i(TAG, "Native Audio: SampleRate=" + nativeSampleRate + ", BufferSize=" + nativeBufferSize);
        }

        // Check storage permissions
        checkStoragePermissions();

        // Pass application directories to native layer
        String internalDir = getFilesDir().getAbsolutePath();
        File extFiles = getExternalFilesDir(null);
        String externalDir = extFiles != null ? extFiles.getAbsolutePath() : internalDir;

        Log.i(TAG, "Initializing native layer: internal=" + internalDir + ", external=" + externalDir);
        try {
            nativeInit(internalDir, externalDir);
            initDriverConfiguration();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeInit unsatisfied: " + e.getMessage());
        }

        // Attach Windroid-style Virtual Controller on-screen overlay for gameplay
        setupVirtualController();
    }

    private void setupVirtualController() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean showVirtualController = prefs.getBoolean("show_virtual_controller", true);

        if (showVirtualController && mLayout != null) {
            runOnUiThread(() -> {
                if (mVirtualController == null) {
                    mVirtualController = new VirtualControllerLayout(this);
                    android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                        android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
                    );
                    mLayout.addView(mVirtualController, lp);
                    Log.i(TAG, "Virtual controller layout (XML) attached to game layout successfully");
                }
            });
        }
    }

    private void initDriverConfiguration() {
        GameConfigManager.ensureDriverPreferencesMigrated(this);

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean useTurnip = prefs.getBoolean(PREF_USE_TURNIP, false);
        boolean turbo = prefs.getBoolean(PREF_TURBO, true);
        String driverName = prefs.getString(PREF_DRIVER_NAME, "vulkan.adreno.so");

        File customDriverDir = new File(getFilesDir(), "custom_driver");
        String hookLibDir = getApplicationInfo().nativeLibraryDir;

        // Auto-detect if custom driver exists or if another .so was copied
        File driverFile = new File(customDriverDir, driverName);
        if (!driverFile.exists() && customDriverDir.exists()) {
            File[] files = customDriverDir.listFiles((dir, name) -> name.endsWith(".so"));
            if (files != null && files.length > 0) {
                driverName = files[0].getName();
                driverFile = files[0];
                prefs.edit().putString(PREF_DRIVER_NAME, driverName).apply();
            }
        }

        boolean disableDebug = GameConfigManager.isDisableDebug(this);

        boolean hasCustomDriver = driverFile.exists();
        if (useTurnip && hasCustomDriver) {
            Log.i(TAG, "Configuring AdrenoTools Turnip driver: dir=" + customDriverDir.getAbsolutePath() + ", name=" + driverName + ", disableDebug=" + disableDebug);
            GameConfigManager.markTurnipLaunchInProgress(this, true);
            nativeSetDriverConfig(customDriverDir.getAbsolutePath(), driverName, hookLibDir, true, turbo, disableDebug);
        } else {
            Log.i(TAG, "Configuring System Vulkan driver (Turnip active=" + (useTurnip && hasCustomDriver) + ", disableDebug=" + disableDebug + ")");
            GameConfigManager.markTurnipLaunchInProgress(this, false);
            nativeSetDriverConfig("", "", hookLibDir, false, false, disableDebug);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
            // Emulation and window are rendering; clear crash guard flag
            GameConfigManager.markTurnipLaunchInProgress(this, false);
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
                        controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars() | WindowInsets.Type.captionBar());
                        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    }
                }

                int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
                decorView.setSystemUiVisibility(flags);
            }
        } catch (Throwable t) {
            Log.w(TAG, "hideSystemUi error: " + t.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        GameConfigManager.markTurnipLaunchInProgress(this, false);
        super.onDestroy();
    }



    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        // Prevent SDLActivity from overriding orientation to portrait if w < h initially
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
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

    /**
     * Called to launch the AdrenoTools Turnip driver ZIP picker
     */
    public void launchDriverPicker() {
        runOnUiThread(() -> {
            Toast.makeText(this, "Selecione o arquivo ZIP do driver Turnip (AdrenoTools)", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_CODE_DRIVER_ZIP);
        });
    }

    /**
     * Shows dialog to toggle between System Driver and Turnip Driver
     */
    public void showDriverSelectionDialog() {
        runOnUiThread(() -> {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            boolean currentUseTurnip = prefs.getBoolean(PREF_USE_TURNIP, false);
            String currentDriver = prefs.getString(PREF_DRIVER_NAME, "vulkan.adreno.so");
            File customDriverDir = new File(getFilesDir(), "custom_driver");
            boolean hasTurnip = new File(customDriverDir, currentDriver).exists();

            String[] options = new String[] {
                "1. Driver do Sistema (Padrão Vulkan OEM)" + (!currentUseTurnip ? " [ATIVO]" : ""),
                "2. Driver Turnip AdrenoTools (" + (hasTurnip ? currentDriver : "Não instalado") + ")" + (currentUseTurnip && hasTurnip ? " [ATIVO]" : ""),
                "3. Instalar / Atualizar Driver Turnip (.zip)..."
            };

            new AlertDialog.Builder(this)
                .setTitle("Configuração de Driver Gráfico GPU")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        prefs.edit().putBoolean(PREF_USE_TURNIP, false).apply();
                        Toast.makeText(this, "Driver do Sistema selecionado. Reinicie o jogo se necessário.", Toast.LENGTH_SHORT).show();
                        initDriverConfiguration();
                    } else if (which == 1) {
                        if (hasTurnip) {
                            prefs.edit().putBoolean(PREF_USE_TURNIP, true).apply();
                            Toast.makeText(this, "Driver Turnip ativado. Reinicie o jogo se necessário.", Toast.LENGTH_SHORT).show();
                            initDriverConfiguration();
                        } else {
                            Toast.makeText(this, "Nenhum driver Turnip instalado. Selecione um arquivo .zip para instalar.", Toast.LENGTH_LONG).show();
                            launchDriverPicker();
                        }
                    } else if (which == 2) {
                        launchDriverPicker();
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
        });
    }

    /**
     * Extracts an AdrenoTools ZIP package into private app files
     */
    private void handlePickedDriverZip(Uri uri) {
        new Thread(() -> {
            try {
                runOnUiThread(() -> Toast.makeText(this, "Extraindo pacote de driver Turnip...", Toast.LENGTH_SHORT).show());
                File customDir = new File(getFilesDir(), "custom_driver");
                if (customDir.exists()) {
                    File[] oldFiles = customDir.listFiles();
                    if (oldFiles != null) {
                        for (File f : oldFiles) f.delete();
                    }
                } else {
                    customDir.mkdirs();
                }

                String resolvedLibraryName = null;

                try (InputStream rawIn = getContentResolver().openInputStream(uri);
                     BufferedInputStream bufIn = new BufferedInputStream(rawIn);
                     ZipInputStream zipIn = new ZipInputStream(bufIn)) {

                    ZipEntry entry;
                    byte[] buffer = new byte[64 * 1024];

                    while ((entry = zipIn.getNextEntry()) != null) {
                        String name = entry.getName();
                        if (entry.isDirectory() || name.contains("..")) {
                            zipIn.closeEntry();
                            continue;
                        }

                        String simpleName = new File(name).getName();
                        File outFile = new File(customDir, simpleName);

                        if ("meta.json".equalsIgnoreCase(simpleName)) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            int len;
                            while ((len = zipIn.read(buffer)) > 0) {
                                baos.write(buffer, 0, len);
                            }
                            byte[] jsonBytes = baos.toByteArray();
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                fos.write(jsonBytes);
                            }
                            try {
                                JSONObject json = new JSONObject(new String(jsonBytes, "UTF-8"));
                                resolvedLibraryName = json.optString("libraryName", null);
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to parse meta.json: " + e.getMessage());
                            }
                        } else {
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                int len;
                                while ((len = zipIn.read(buffer)) > 0) {
                                    fos.write(buffer, 0, len);
                                }
                            }
                            if (simpleName.endsWith(".so") && resolvedLibraryName == null) {
                                if (simpleName.equals("vulkan.adreno.so") || simpleName.contains("turnip") || simpleName.contains("freedreno")) {
                                    resolvedLibraryName = simpleName;
                                }
                            }
                        }
                        zipIn.closeEntry();
                    }
                }

                if (resolvedLibraryName == null) {
                    File[] files = customDir.listFiles((dir, name) -> name.endsWith(".so"));
                    if (files != null && files.length > 0) {
                        resolvedLibraryName = files[0].getName();
                    }
                }

                if (resolvedLibraryName == null) {
                    runOnUiThread(() -> Toast.makeText(this, "Erro: Nenhuma biblioteca .so encontrada no arquivo ZIP.", Toast.LENGTH_LONG).show());
                    return;
                }

                SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                prefs.edit()
                     .putBoolean(PREF_USE_TURNIP, true)
                     .putString(PREF_DRIVER_NAME, resolvedLibraryName)
                     .apply();

                String finalName = resolvedLibraryName;
                runOnUiThread(() -> {
                    Toast.makeText(this, "Driver Turnip '" + finalName + "' instalado com sucesso! Reiniciando...", Toast.LENGTH_LONG).show();
                });

                initDriverConfiguration();
                Thread.sleep(1500);
                restartActivity();

            } catch (Exception e) {
                Log.e(TAG, "Failed to extract driver zip: " + e.getMessage(), e);
                runOnUiThread(() -> Toast.makeText(this, "Falha ao extrair driver: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /**
     * Called from C++ via JNI to launch the ISO file picker
     */
    public void launchIsoPicker() {
        runOnUiThread(() -> {
            String extraIso = getIntent().getStringExtra("EXTRA_ISO_PATH");
            if (extraIso != null && !extraIso.isEmpty() && new File(extraIso).exists()) {
                Log.i(TAG, "Consuming pending ISO from Intent extra: " + extraIso);
                nativeOnIsoPicked(extraIso);
                return;
            }
            Toast.makeText(this, "Select Silent Hill: Downpour Xbox 360 ISO", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_CODE_ISO);
        });
    }

    /**
     * Called from C++ via JNI to launch the TU1 file picker
     */
    public void launchTuPicker() {
        runOnUiThread(() -> {
            Toast.makeText(this, "Select Title Update 1 (TU1) package or default.xexp", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_CODE_TU);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_ISO) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                handlePickedFile(data.getData(), true);
            } else {
                Log.i(TAG, "ISO file picking cancelled");
                nativeOnIsoPicked(null);
            }
        } else if (requestCode == REQUEST_CODE_TU) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                handlePickedFile(data.getData(), false);
            } else {
                Log.i(TAG, "TU file picking cancelled");
                nativeOnTuFilePicked(null);
            }
        } else if (requestCode == REQUEST_CODE_DRIVER_ZIP) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                handlePickedDriverZip(data.getData());
            } else {
                Log.i(TAG, "Driver ZIP selection cancelled");
            }
        }
    }

    /**
     * Resolves SAF Content URI to a local file path so C++ std::ifstream can read it
     */
    private void handlePickedFile(Uri uri, boolean isIso) {
        new Thread(() -> {
            try {
                String fileName = getFileName(uri);
                if (fileName == null || fileName.isEmpty()) {
                    fileName = isIso ? "downpour_game.iso" : "tu_package.bin";
                }

                // If file is already directly on disk (file://)
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    String path = uri.getPath();
                    if (isIso) nativeOnIsoPicked(path);
                    else nativeOnTuFilePicked(path);
                    return;
                }

                // If content:// URI, check if we can resolve raw path or copy to app cache
                File cacheTarget = new File(getCacheDir(), fileName);
                Log.i(TAG, "Copying content URI to cache: " + cacheTarget.getAbsolutePath());

                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(cacheTarget)) {
                    if (in == null) {
                        Log.e(TAG, "Cannot open stream for URI: " + uri);
                        if (isIso) nativeOnIsoPicked(null);
                        else nativeOnTuFilePicked(null);
                        return;
                    }
                    byte[] buffer = new byte[1024 * 1024]; // 1MB chunks
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }

                String resolvedPath = cacheTarget.getAbsolutePath();
                Log.i(TAG, "File ready for native access at: " + resolvedPath);
                if (isIso) {
                    runOnUiThread(() -> Toast.makeText(this, "Extraindo arquivos do jogo... Aguarde alguns instantes.", Toast.LENGTH_LONG).show());
                    nativeOnIsoPicked(resolvedPath);
                } else {
                    nativeOnTuFilePicked(resolvedPath);
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to resolve picked file: " + e.getMessage(), e);
                if (isIso) nativeOnIsoPicked(null);
                else nativeOnTuFilePicked(null);
            }
        }).start();
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

    /**
     * Called from C++ to download TU1 container over HTTP
     */
    public boolean downloadFile(String urlStr, String destPath) {
        runOnUiThread(() -> Toast.makeText(this, "Baixando atualização do jogo (TU1)...", Toast.LENGTH_SHORT).show());
        try {
            Log.i(TAG, "Starting HTTP download: " + urlStr + " -> " + destPath);
            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "DownpourRecomp-Android/1.0");
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Server returned HTTP " + responseCode + " " + connection.getResponseMessage());
                return false;
            }

            File destFile = new File(destPath);
            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[64 * 1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }

            Log.i(TAG, "Download finished successfully: " + destFile.length() + " bytes");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "HTTP download error: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Called from C++ to restart the Activity
     */
    public void restartActivity() {
        runOnUiThread(() -> {
            Log.i(TAG, "Restarting Downpour Activity cleanly");
            Toast.makeText(this, "Iniciando Silent Hill: Downpour...", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, DownpourActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            Runtime.getRuntime().exit(0);
        });
    }
}
