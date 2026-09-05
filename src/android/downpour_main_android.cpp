#include <SDL3/SDL_main.h>

#include <algorithm>
#include <cstdlib>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include <rex/cvar.h>
#include <rex/filesystem.h>
#include <rex/logging.h>
#include <rex/memory/utils.h>
#include <rex/platform.h>
#include <rex/thread.h>
#include <rex/ui/windowed_app.h>
#include <rex/ui/windowed_app_context_sdl.h>

#include "generated/default/downpour_init.h"
#include "downpour_app.h"
#include "downpour_driver.h"

#if defined(__ANDROID__)
#include <android/log.h>
#define MAIN_LOGI(...) do { if (!downpour::driver::GetDriverConfig().disable_debug) __android_log_print(ANDROID_LOG_INFO, "DownpourMain", __VA_ARGS__); } while(0)
#define MAIN_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DownpourMain", __VA_ARGS__)
#else
#define MAIN_LOGI(...)
#define MAIN_LOGE(...)
#endif

namespace {

int RunWindowedApp(int argc, char** argv) {
#if defined(__ANDROID__)
  rex::memory::AndroidInitialize();
  rex::thread::AndroidInitialize();
  rex::filesystem::AndroidInitialize();
#endif
  auto remaining = rex::cvar::Init(argc, argv);
  // Default baseline settings for Android / Silent Hill Downpour (UE3)
  rex::cvar::SetFlagByName("async_shader_compilation", "true");
  rex::cvar::SetFlagByName("vulkan_pipeline_creation_threads", "4");
  rex::cvar::SetFlagByName("store_shaders", "true");

  // Mobile texture cache limits (512MB/768MB prevents aggressive eviction and white textures during UE3 streaming)
  rex::cvar::SetFlagByName("texture_cache_memory_limit_soft", "512");
  rex::cvar::SetFlagByName("texture_cache_memory_limit_hard", "768");
  rex::cvar::SetFlagByName("texture_cache_memory_limit_render_to_texture", "96");
  rex::cvar::SetFlagByName("texture_cache_memory_limit_soft_lifetime", "60");
  rex::cvar::SetFlagByName("gpu_allow_invalid_fetch_constants", "true");

  // Downpour & Adreno hardware MSAA / readback / chromatic noise fixes
  rex::cvar::SetFlagByName("native_2x_msaa", "true");
  rex::cvar::SetFlagByName("readback_memexport", "true");
  rex::cvar::SetFlagByName("readback_memexport_fast", "true");
  rex::cvar::SetFlagByName("skip_depth_color_7e3_aliasing_transfers", "true");
  rex::cvar::SetFlagByName("vsync", "true");

  // Load user's downpour.toml: overrides defaults if configured in app Settings
  auto ext = downpour::android::GetExternalFilesDir();
  bool disable_debug = downpour::driver::GetDriverConfig().disable_debug;

  if (!ext.empty()) {
    auto toml_path = ext / "downpour.toml";
    if (std::filesystem::exists(toml_path)) {
      rex::cvar::LoadConfig(toml_path);
    }
  }

  // Check if debug is disabled either by preference or TOML setting
  std::string current_log_level = REXCVAR_GET(log_level);
  if (disable_debug || current_log_level == "off") {
    rex::cvar::SetFlagByName("log_file", "");
    rex::cvar::SetFlagByName("log_level", "off");
    rex::cvar::SetFlagByName("log_verbose", "false");
    rex::cvar::SetFlagByName("log_noisy", "false");
    rex::cvar::SetFlagByName("log_high_frequency_kernel_calls", "false");
    rex::cvar::SetFlagByName("vulkan_validation_enabled", "false");
    rex::cvar::SetFlagByName("vulkan_log_debug_messages", "false");
    rex::cvar::SetFlagByName("gpu_debug_markers", "false");
    rex::cvar::SetFlagByName("kernel_debug_monitor", "false");
    rex::cvar::SetFlagByName("kernel_cert_monitor", "false");
  } else if (!ext.empty()) {
    std::error_code ec;
    std::filesystem::create_directories(ext / "logs", ec);
    rex::cvar::SetFlagByName("log_file", (ext / "logs" / "downpour.log").string());
  }

  rex::InitLoggingEarly();

#if defined(__ANDROID__)
  // Initialize Turnip / custom AdrenoTools driver before SDL3/Vulkan initialization
  downpour::driver::InitializeDriver();
  downpour::driver::LogTextureCompressionSupport();
#endif

  int result = EXIT_FAILURE;
  {
    MAIN_LOGI("Initializing SDLWindowedAppContext...");
    rex::ui::SDLWindowedAppContext app_context;
    if (!app_context.Initialize()) {
      MAIN_LOGE("app_context.Initialize() failed!");
      return EXIT_FAILURE;
    }
    MAIN_LOGI("SDLWindowedAppContext initialized successfully.");

    std::unique_ptr<rex::ui::WindowedApp> app = DownpourApp::Create(app_context);

    // Match remaining positional args to the app's expected options.
    const auto& option_names = app->GetPositionalOptions();
    std::map<std::string, std::string> parsed;
    size_t count = std::min(remaining.size(), option_names.size());
    for (size_t i = 0; i < count; ++i) {
      parsed[option_names[i]] = remaining[i];
    }
    app->SetParsedArguments(std::move(parsed));

    MAIN_LOGI("Calling app->OnInitialize()...");
    bool init_ok = app->OnInitialize();
    MAIN_LOGI("app->OnInitialize() returned: %s", init_ok ? "SUCCESS" : "FAILED");

    result = init_ok ? app_context.RunMainMessageLoop() : EXIT_FAILURE;

    app->InvokeOnDestroy();
#if defined(__ANDROID__)
    MAIN_LOGI("Shutting down driver and Android subsystems...");
    downpour::driver::ShutdownDriver();
    rex::filesystem::AndroidShutdown();
    rex::thread::AndroidShutdown();
    rex::memory::AndroidShutdown();
#endif
  }

  return result;
}

}  // namespace

extern "C" SDLMAIN_DECLSPEC int SDLCALL SDL_main(int argc, char* argv[]) {
  return RunWindowedApp(argc, argv);
}
