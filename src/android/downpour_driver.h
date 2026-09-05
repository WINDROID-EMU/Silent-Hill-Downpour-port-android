#pragma once

#include <string>

namespace downpour::driver {

struct DriverConfig {
  bool use_turnip = false;
  std::string driver_dir;      // Directory containing custom driver (e.g. /data/user/0/com.downpour/files/custom_driver/)
  std::string driver_name;     // Soname of the driver (e.g. vulkan.adreno.so)
  std::string hook_lib_dir;    // Path to app's nativeLibraryDir containing libmain_hook.so
  bool enable_turbo = true;    // Force maximum Adreno GPU clocks
  bool disable_debug = true;   // Disable all debug logging, Vulkan validation and disk I/O flush
};

// Sets driver configuration (called via JNI from DownpourActivity)
void SetDriverConfig(const DriverConfig& config);

// Gets current driver configuration
const DriverConfig& GetDriverConfig();

// Initializes AdrenoTools Turnip driver and installs dlopen interception
bool InitializeDriver();

// Checks if Turnip driver is currently loaded and active
bool IsTurnipActive();

// Cleanup on shutdown
void ShutdownDriver();

// Diagnostic only: creates a throwaway VkInstance against whichever driver is
// currently active (Turnip or system) and logs to logcat (tag "DownpourGpuCaps")
// whether the GPU/driver reports support for BC (DXT), ETC2 and ASTC texture
// compression. Does not affect rendering.
void LogTextureCompressionSupport();

}  // namespace downpour::driver
