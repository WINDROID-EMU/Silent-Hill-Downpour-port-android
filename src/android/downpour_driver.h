#pragma once

#include <string>

namespace downpour::driver {

struct DriverConfig {
  bool use_turnip = false;
  std::string driver_dir;      // Directory containing custom driver (e.g. /data/user/0/com.downpour/files/custom_driver/)
  std::string driver_name;     // Soname of the driver (e.g. vulkan.adreno.so)
  std::string hook_lib_dir;    // Path to app's nativeLibraryDir containing libmain_hook.so
  bool enable_turbo = true;    // Force maximum Adreno GPU clocks
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

}  // namespace downpour::driver
