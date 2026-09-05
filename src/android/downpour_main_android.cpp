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
#define MAIN_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DownpourMain", __VA_ARGS__)
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
  rex::cvar::ApplyEnvironment();
  auto ext = downpour::android::GetExternalFilesDir();
  if (!ext.empty()) {
    std::error_code ec;
    std::filesystem::create_directories(ext / "logs", ec);
    rex::cvar::SetFlagByName("log_file", (ext / "logs" / "downpour.log").string());
  }
  rex::InitLoggingEarly();

  // Mobile Turnip/Adreno GPU rendering flags
  rex::cvar::SetFlagByName("vulkan_async_skip_incomplete_frames", "true");
  rex::cvar::SetFlagByName("readback_resolve", "none");
  rex::cvar::SetFlagByName("vulkan_readback_resolve", "false");
  rex::cvar::SetFlagByName("gamma_render_target_as_unorm16", "true");
  rex::cvar::SetFlagByName("gpu_allow_invalid_fetch_constants", "true");
  rex::cvar::SetFlagByName("snorm16_render_target_full_range", "true");
  rex::cvar::SetFlagByName("vulkan_force_convert_quad_lists_to_triangle_lists", "true");
  rex::cvar::SetFlagByName("vulkan_force_expand_rectangle_lists_in_vs", "true");
  rex::cvar::SetFlagByName("vulkan_force_expand_point_sprites_in_vs", "true");
  rex::cvar::SetFlagByName("execute_unclipped_draw_vs_on_cpu", "false");
  rex::cvar::SetFlagByName("direct_host_resolve", "false");
  rex::cvar::SetFlagByName("vulkan_dynamic_rendering", "false");

  // Asynchronous shader compilation (prevents micro-stutters during gameplay)
  rex::cvar::SetFlagByName("async_shader_compilation", "true");
  rex::cvar::SetFlagByName("vulkan_pipeline_creation_threads", "4");
  rex::cvar::SetFlagByName("store_shaders", "true");

  // Mobile texture cache limits (prevent Android Low Memory Killer OOM)
  rex::cvar::SetFlagByName("texture_cache_memory_limit_soft", "256");
  rex::cvar::SetFlagByName("texture_cache_memory_limit_hard", "384");
  rex::cvar::SetFlagByName("texture_cache_memory_limit_render_to_texture", "64");

  // Mobile bandwidth & fillrate optimizations
  rex::cvar::SetFlagByName("native_2x_msaa", "false");
  rex::cvar::SetFlagByName("anisotropic_override", "2");
  rex::cvar::SetFlagByName("readback_memexport", "false");
  rex::cvar::SetFlagByName("readback_memexport_fast", "true");
  rex::cvar::SetFlagByName("vulkan_submit_on_primary_buffer_end", "false");
  rex::cvar::SetFlagByName("vsync", "true");

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
