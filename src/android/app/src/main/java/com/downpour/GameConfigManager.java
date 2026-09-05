package com.downpour;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class GameConfigManager {

    private static final String TAG = "GameConfigManager";

    public static final String PREF_NAME = "downpour_settings";
    public static final String PREF_USE_TURNIP = "use_turnip";
    public static final String PREF_DRIVER_NAME = "driver_name";
    public static final String PREF_TURBO = "turbo_mode";

    public static final String DEFAULT_DRIVER_NAME = "vulkan.adreno.so";
    public static final String EXTRA_ISO_PATH = "EXTRA_ISO_PATH";

    public static final String PREF_TURNIP_IN_FLIGHT = "turnip_in_flight";
    public static final String PREF_DRIVER_MIGRATED_V2 = "driver_preference_migrated_v2";

    public static File getStorageDir(Context context) {
        File ext = context.getExternalFilesDir(null);
        return (ext != null) ? ext : context.getFilesDir();
    }

    public static File getGameDir(Context context) {
        return new File(getStorageDir(context), "game");
    }

    public static File getDefaultXexFile(Context context) {
        return new File(getGameDir(context), "default.xex");
    }

    public static boolean isGameInstalled(Context context) {
        File xex = getDefaultXexFile(context);
        return xex.exists() && xex.isFile() && xex.length() > 0;
    }

    public static File getCustomDriverDir(Context context) {
        File dir = new File(context.getFilesDir(), "custom_driver");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static boolean isTurnipEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_USE_TURNIP, false);
    }

    public static void setTurnipEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
               .edit()
               .putBoolean(PREF_USE_TURNIP, enabled)
               .apply();
    }

    public static void ensureDriverPreferencesMigrated(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_DRIVER_MIGRATED_V2, false)) {
            // Revert any previously auto-activated Turnip default to system driver
            prefs.edit()
                 .putBoolean(PREF_USE_TURNIP, false)
                 .putBoolean(PREF_TURNIP_IN_FLIGHT, false)
                 .putBoolean(PREF_DRIVER_MIGRATED_V2, true)
                 .apply();
            Log.i(TAG, "Migrated driver preference to Qualcomm System Driver default");
        }
    }

    public static void markTurnipLaunchInProgress(Context context, boolean inProgress) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
               .edit()
               .putBoolean(PREF_TURNIP_IN_FLIGHT, inProgress)
               .apply();
    }

    public static boolean checkAndRecoverTurnipCrash(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_TURNIP_IN_FLIGHT, false)) {
            Log.w(TAG, "Detected previous launch crashed with Turnip driver! Falling back to Qualcomm System Driver.");
            prefs.edit()
                 .putBoolean(PREF_USE_TURNIP, false)
                 .putBoolean(PREF_TURNIP_IN_FLIGHT, false)
                 .apply();
            return true;
        }
        return false;
    }

    public static boolean isTurboEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_TURBO, true);
    }

    public static void setTurboEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
               .edit()
               .putBoolean(PREF_TURBO, enabled)
               .apply();
    }

    public static String getDriverName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_DRIVER_NAME, DEFAULT_DRIVER_NAME);
    }

    public static void setDriverName(Context context, String name) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
               .edit()
               .putString(PREF_DRIVER_NAME, name)
               .apply();
    }

    public static boolean hasCustomDriverInstalled(Context context) {
        File customDir = getCustomDriverDir(context);
        String name = getDriverName(context);
        File driverFile = new File(customDir, name);
        if (driverFile.exists() && driverFile.length() > 0) {
            return true;
        }
        File[] files = customDir.listFiles((dir, fName) -> fName.endsWith(".so"));
        return (files != null && files.length > 0);
    }

    public static String getActiveDriverDescription(Context context) {
        boolean useTurnip = isTurnipEnabled(context);
        boolean hasDriver = hasCustomDriverInstalled(context);
        if (useTurnip && hasDriver) {
            return "Turnip AdrenoTools (" + getDriverName(context) + ") [ATIVO]";
        } else if (useTurnip && !hasDriver) {
            return "Turnip ativado (Nenhum driver .zip instalado ainda)";
        } else {
            return "Qualcomm OEM (Driver do Sistema Vulkan)";
        }
    }

    public static File getTomlConfigFile(Context context) {
        return new File(getStorageDir(context), "downpour.toml");
    }

    public static String loadTomlContent(Context context) {
        File tomlFile = getTomlConfigFile(context);
        if (tomlFile.exists() && tomlFile.isFile()) {
            try (FileInputStream fis = new FileInputStream(tomlFile);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            } catch (Exception e) {
                Log.e(TAG, "Error reading toml file: " + e.getMessage(), e);
            }
        }
        // If file doesn't exist, load from assets or return mobile template
        return getDefaultTomlContent(context);
    }

    public static boolean saveTomlContent(Context context, String content) {
        File tomlFile = getTomlConfigFile(context);
        try {
            File parent = tomlFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(tomlFile)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error saving toml file: " + e.getMessage(), e);
            return false;
        }
    }

    public static String getDefaultTomlContent(Context context) {
        try (InputStream is = context.getAssets().open("downpour.toml");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception ignored) {
        }

        // Fallback default canonical configuration for Android
        return "# Silent Hill: Downpour - Android Configuration\n" +
               "# ===== Render path & quality =====\n" +
               "render_target_path_d3d12 = 'rov'\n" +
               "resolution_scale = 1\n" +
               "anisotropic_override = 2\n" +
               "swap_post_effect = 'fxaa'\n" +
               "skip_depth_color_7e3_aliasing_transfers = true\n\n" +
               "# ===== Present =====\n" +
               "present_effect = 'fsr3'\n" +
               "d3d12_present_frame_limiter = true\n" +
               "d3d12_present_frame_limiter_fps = 60.0\n" +
               "vsync = true\n\n" +
               "# ===== Mobile Turnip & Vulkan Optimizations =====\n" +
               "vulkan_async_skip_incomplete_frames = true\n" +
               "readback_resolve = 'none'\n" +
               "vulkan_readback_resolve = false\n" +
               "gamma_render_target_as_unorm16 = true\n" +
               "gpu_allow_invalid_fetch_constants = true\n" +
               "snorm16_render_target_full_range = true\n" +
               "vulkan_force_convert_quad_lists_to_triangle_lists = true\n" +
               "vulkan_force_expand_rectangle_lists_in_vs = true\n" +
               "vulkan_force_expand_point_sprites_in_vs = true\n" +
               "execute_unclipped_draw_vs_on_cpu = false\n" +
               "direct_host_resolve = false\n" +
               "vulkan_dynamic_rendering = false\n" +
               "async_shader_compilation = true\n";
    }

    public static String updateOrAddTomlKey(String toml, String key, String value) {
        Pattern pattern = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*.*$");
        Matcher matcher = pattern.matcher(toml);
        if (matcher.find()) {
            return matcher.replaceAll(key + " = " + value);
        } else {
            return toml + "\n" + key + " = " + value + "\n";
        }
    }

    public static String extractDriverZip(Context context, Uri uri) throws Exception {
        File customDir = getCustomDriverDir(context);
        File[] oldFiles = customDir.listFiles();
        if (oldFiles != null) {
            for (File f : oldFiles) f.delete();
        }

        String resolvedLibraryName = null;

        try (InputStream rawIn = context.getContentResolver().openInputStream(uri);
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
                        JSONObject json = new JSONObject(new String(jsonBytes, StandardCharsets.UTF_8));
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

        if (resolvedLibraryName != null) {
            setTurnipEnabled(context, true);
            setDriverName(context, resolvedLibraryName);
        }

        return resolvedLibraryName;
    }
}
