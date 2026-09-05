#pragma once

#include <filesystem>
#include <string>

namespace downpour::android {

// Called from JNI_OnLoad or initialization
void InitializeJni();

// Request the Android Activity to launch the Storage Access Framework (SAF) picker
void RequestPickIso();
void RequestPickTu();

// Called from JNI when user selects a file or cancels
void SetPendingIsoPath(const std::string& path);
void SetPendingTuPath(const std::string& path);

// Consumes the pending path (waits or returns current)
std::filesystem::path ConsumePendingIsoPath();
std::filesystem::path ConsumePendingTuPath();

// Storage paths retrieved from Android Context
void SetInternalFilesDir(const std::string& path);
void SetExternalFilesDir(const std::string& path);
std::filesystem::path GetInternalFilesDir();
std::filesystem::path GetExternalFilesDir();

// Request activity relaunch / restart
void RequestRestartApp();

// Download helper via Android Java layer (HttpURLConnection / DownloadManager)
bool DownloadFileViaJava(const std::string& url, const std::string& destination,
                        void (*progress_callback)(uint64_t current, uint64_t total, void* user_data),
                        void* user_data, std::string& error);

// Driver configuration from Android Java layer
void SetDriverConfig(const std::string& driver_dir, const std::string& driver_name,
                     const std::string& hook_lib_dir, bool use_turnip, bool enable_turbo,
                     bool disable_debug = true);

}  // namespace downpour::android
