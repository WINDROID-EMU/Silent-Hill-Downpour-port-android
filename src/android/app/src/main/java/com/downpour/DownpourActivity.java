package com.downpour;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class DownpourActivity extends SDLActivity {

    private static final String TAG = "DownpourActivity";
    private static final int REQUEST_CODE_ISO = 1001;
    private static final int REQUEST_CODE_TU = 1002;

    // Native callbacks for Android bridge
    private native void nativeInit(String internalDir, String externalDir);
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
        super.onCreate(savedInstanceState);

        // Force landscape orientation
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Check storage permissions
        checkStoragePermissions();

        // Pass application directories to native layer
        String internalDir = getFilesDir().getAbsolutePath();
        File extFiles = getExternalFilesDir(null);
        String externalDir = extFiles != null ? extFiles.getAbsolutePath() : internalDir;

        Log.i(TAG, "Initializing native layer: internal=" + internalDir + ", external=" + externalDir);
        try {
            nativeInit(internalDir, externalDir);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeInit unsatisfied: " + e.getMessage());
        }
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
     * Called from C++ via JNI to launch the ISO file picker
     */
    public void launchIsoPicker() {
        runOnUiThread(() -> {
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
