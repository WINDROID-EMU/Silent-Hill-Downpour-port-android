#include "downpour_android.h"
#include "downpour_driver.h"

#if defined(__ANDROID__)
#include <jni.h>
#include <android/log.h>
#include <condition_variable>
#include <mutex>
#include <chrono>
#include <signal.h>
#include <ucontext.h>
#include <cinttypes>

#define LOG_TAG "DownpourRecomp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace downpour::android {

static JavaVM* g_jvm = nullptr;
static jobject g_activity_obj = nullptr;
static jclass g_activity_class = nullptr;

static std::mutex g_picker_mutex;
static std::condition_variable g_picker_cv;
static std::string g_pending_iso_path;
static bool g_iso_picker_done = false;

static std::string g_pending_tu_path;
static bool g_tu_picker_done = false;

static std::filesystem::path g_internal_files_dir;
static std::filesystem::path g_external_files_dir;

static struct sigaction g_old_sigaction_segv;
static struct sigaction g_old_sigaction_bus;
static struct sigaction g_old_sigaction_ill;
static struct sigaction g_old_sigaction_fpe;

static void FatalSignalHandler(int sig, siginfo_t* info, void* context) {
  const char* sig_name = "UNKNOWN";
  switch (sig) {
    case SIGSEGV: sig_name = "SIGSEGV (Segmentation fault)"; break;
    case SIGBUS:  sig_name = "SIGBUS (Bus error)"; break;
    case SIGILL:  sig_name = "SIGILL (Illegal instruction)"; break;
    case SIGFPE:  sig_name = "SIGFPE (Floating point exception)"; break;
    case SIGABRT: sig_name = "SIGABRT (Aborted)"; break;
  }

  uintptr_t fault_addr = reinterpret_cast<uintptr_t>(info->si_addr);
  __android_log_print(ANDROID_LOG_ERROR, "DownpourCrash",
                      "================ FATAL SIGNAL CAUGHT ================");
  __android_log_print(ANDROID_LOG_ERROR, "DownpourCrash",
                      "Signal: %s (%d), Fault Address: 0x%" PRIxPTR ", Code: %d",
                      sig_name, sig, fault_addr, info->si_code);

  if (context) {
    ucontext_t* uc = reinterpret_cast<ucontext_t*>(context);
#if defined(__aarch64__)
    uintptr_t pc = uc->uc_mcontext.pc;
    uintptr_t sp = uc->uc_mcontext.sp;
    uintptr_t lr = uc->uc_mcontext.regs[30];
    __android_log_print(ANDROID_LOG_ERROR, "DownpourCrash",
                        "PC: 0x%" PRIxPTR ", LR: 0x%" PRIxPTR ", SP: 0x%" PRIxPTR,
                        pc, lr, sp);
#endif
  }

  struct sigaction* old_sa = nullptr;
  if (sig == SIGSEGV) old_sa = &g_old_sigaction_segv;
  else if (sig == SIGBUS) old_sa = &g_old_sigaction_bus;
  else if (sig == SIGILL) old_sa = &g_old_sigaction_ill;
  else if (sig == SIGFPE) old_sa = &g_old_sigaction_fpe;

  if (old_sa && old_sa->sa_sigaction && old_sa->sa_sigaction != FatalSignalHandler) {
    old_sa->sa_sigaction(sig, info, context);
  } else {
    signal(sig, SIG_DFL);
    raise(sig);
  }
}

static void InstallCrashSignalHandler() {
  struct sigaction sa{};
  sa.sa_sigaction = FatalSignalHandler;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  sigemptyset(&sa.sa_mask);

  sigaction(SIGSEGV, &sa, &g_old_sigaction_segv);
  sigaction(SIGBUS, &sa, &g_old_sigaction_bus);
  sigaction(SIGILL, &sa, &g_old_sigaction_ill);
  sigaction(SIGFPE, &sa, &g_old_sigaction_fpe);
  LOGI("Crash signal handlers installed successfully.");
}

static JNIEnv* GetEnv() {
  if (!g_jvm) return nullptr;
  JNIEnv* env = nullptr;
  jint result = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (result == JNI_EDETACHED) {
    if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
      LOGE("Failed to attach current thread to JVM");
      return nullptr;
    }
  }
  return env;
}

void InitializeJni() {
  InstallCrashSignalHandler();
  LOGI("Downpour Android JNI Initialized");
}

void SetPendingIsoPath(const std::string& path) {
  std::unique_lock<std::mutex> lock(g_picker_mutex);
  g_pending_iso_path = path;
  g_iso_picker_done = true;
  g_picker_cv.notify_all();
}

void SetPendingTuPath(const std::string& path) {
  std::unique_lock<std::mutex> lock(g_picker_mutex);
  g_pending_tu_path = path;
  g_tu_picker_done = true;
  g_picker_cv.notify_all();
}

void RequestPickIso() {
  JNIEnv* env = GetEnv();
  if (!env || !g_activity_obj) {
    LOGE("RequestPickIso failed: env or activity is null");
    return;
  }
  jclass clazz = env->GetObjectClass(g_activity_obj);
  jmethodID method = env->GetMethodID(clazz, "launchIsoPicker", "()V");
  if (method) {
    env->CallVoidMethod(g_activity_obj, method);
  } else {
    LOGE("launchIsoPicker method not found on Activity");
  }
}

void RequestPickTu() {
  JNIEnv* env = GetEnv();
  if (!env || !g_activity_obj) {
    LOGE("RequestPickTu failed: env or activity is null");
    return;
  }
  jclass clazz = env->GetObjectClass(g_activity_obj);
  jmethodID method = env->GetMethodID(clazz, "launchTuPicker", "()V");
  if (method) {
    env->CallVoidMethod(g_activity_obj, method);
  } else {
    LOGE("launchTuPicker method not found on Activity");
  }
}

std::filesystem::path ConsumePendingIsoPath() {
  {
    std::unique_lock<std::mutex> lock(g_picker_mutex);
    g_iso_picker_done = false;
    g_pending_iso_path.clear();
  }

  RequestPickIso();

  std::unique_lock<std::mutex> lock(g_picker_mutex);
  // Wait for user to pick a file (or cancel, up to 5 minutes)
  g_picker_cv.wait(lock, [] { return g_iso_picker_done; });

  return g_pending_iso_path;
}

std::filesystem::path ConsumePendingTuPath() {
  {
    std::unique_lock<std::mutex> lock(g_picker_mutex);
    g_tu_picker_done = false;
    g_pending_tu_path.clear();
  }

  RequestPickTu();

  std::unique_lock<std::mutex> lock(g_picker_mutex);
  g_picker_cv.wait(lock, [] { return g_tu_picker_done; });

  return g_pending_tu_path;
}

void SetInternalFilesDir(const std::string& path) {
  g_internal_files_dir = path;
  LOGI("Internal files dir set to: %s", path.c_str());
}

void SetExternalFilesDir(const std::string& path) {
  g_external_files_dir = path;
  LOGI("External files dir set to: %s", path.c_str());
}

std::filesystem::path GetInternalFilesDir() {
  if (g_internal_files_dir.empty()) {
    return "/data/data/com.downpour/files";
  }
  return g_internal_files_dir;
}

std::filesystem::path GetExternalFilesDir() {
  if (g_external_files_dir.empty()) {
    return "/storage/emulated/0/Android/data/com.downpour/files";
  }
  return g_external_files_dir;
}

void RequestRestartApp() {
  JNIEnv* env = GetEnv();
  if (!env || !g_activity_obj) {
    LOGE("RequestRestartApp failed: env or activity is null");
    return;
  }
  jclass clazz = env->GetObjectClass(g_activity_obj);
  jmethodID method = env->GetMethodID(clazz, "restartActivity", "()V");
  if (method) {
    env->CallVoidMethod(g_activity_obj, method);
  }
}

bool DownloadFileViaJava(const std::string& url, const std::string& destination,
                        void (*progress_callback)(uint64_t current, uint64_t total, void* user_data),
                        void* user_data, std::string& error) {
  JNIEnv* env = GetEnv();
  if (!env || !g_activity_obj) {
    error = "Android JVM or Activity not available for download.";
    return false;
  }
  jclass clazz = env->GetObjectClass(g_activity_obj);
  jmethodID method = env->GetMethodID(clazz, "downloadFile", "(Ljava/lang/String;Ljava/lang/String;)Z");
  if (!method) {
    error = "downloadFile method not found in Android Activity.";
    return false;
  }

  jstring jUrl = env->NewStringUTF(url.c_str());
  jstring jDest = env->NewStringUTF(destination.c_str());
  jboolean success = env->CallBooleanMethod(g_activity_obj, method, jUrl, jDest);
  env->DeleteLocalRef(jUrl);
  env->DeleteLocalRef(jDest);

  if (!success) {
    error = "Download failed in Java layer. Please check your internet connection.";
    return false;
  }
  return true;
}

void SetDriverConfig(const std::string& driver_dir, const std::string& driver_name,
                     const std::string& hook_lib_dir, bool use_turnip, bool enable_turbo,
                     bool disable_debug) {
  downpour::driver::DriverConfig cfg;
  cfg.driver_dir = driver_dir;
  cfg.driver_name = driver_name;
  cfg.hook_lib_dir = hook_lib_dir;
  cfg.use_turnip = use_turnip;
  cfg.enable_turbo = enable_turbo;
  cfg.disable_debug = disable_debug;
  downpour::driver::SetDriverConfig(cfg);
}

}  // namespace downpour::android

// --- JNI Exported Functions called by DownpourActivity ---

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
  downpour::android::g_jvm = vm;
  LOGI("JNI_OnLoad called for Downpour");
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_downpour_DownpourActivity_nativeSetDriverConfig(
    JNIEnv* env, jobject /*thiz*/,
    jstring driverDir, jstring driverName, jstring hookLibDir,
    jboolean useTurnip, jboolean enableTurbo, jboolean disableDebug) {
  std::string dir_str;
  std::string name_str;
  std::string hook_str;

  if (driverDir) {
    const char* c_dir = env->GetStringUTFChars(driverDir, nullptr);
    if (c_dir) {
      dir_str = c_dir;
      env->ReleaseStringUTFChars(driverDir, c_dir);
    }
  }
  if (driverName) {
    const char* c_name = env->GetStringUTFChars(driverName, nullptr);
    if (c_name) {
      name_str = c_name;
      env->ReleaseStringUTFChars(driverName, c_name);
    }
  }
  if (hookLibDir) {
    const char* c_hook = env->GetStringUTFChars(hookLibDir, nullptr);
    if (c_hook) {
      hook_str = c_hook;
      env->ReleaseStringUTFChars(hookLibDir, c_hook);
    }
  }

  downpour::android::SetDriverConfig(dir_str, name_str, hook_str, useTurnip, enableTurbo, disableDebug);
}

JNIEXPORT void JNICALL
Java_com_downpour_DownpourActivity_nativeInit(JNIEnv* env, jobject thiz,
                                              jstring internalDir, jstring externalDir) {
  if (downpour::android::g_activity_obj) {
    env->DeleteGlobalRef(downpour::android::g_activity_obj);
  }
  downpour::android::g_activity_obj = env->NewGlobalRef(thiz);

  const char* c_internal = env->GetStringUTFChars(internalDir, nullptr);
  const char* c_external = env->GetStringUTFChars(externalDir, nullptr);
  if (c_internal) {
    downpour::android::SetInternalFilesDir(c_internal);
    env->ReleaseStringUTFChars(internalDir, c_internal);
  }
  if (c_external) {
    downpour::android::SetExternalFilesDir(c_external);
    env->ReleaseStringUTFChars(externalDir, c_external);
  }
}

JNIEXPORT void JNICALL
Java_com_downpour_DownpourActivity_nativeOnIsoPicked(JNIEnv* env, jobject /*thiz*/, jstring path) {
  if (path) {
    const char* c_path = env->GetStringUTFChars(path, nullptr);
    downpour::android::SetPendingIsoPath(c_path ? c_path : "");
    env->ReleaseStringUTFChars(path, c_path);
  } else {
    downpour::android::SetPendingIsoPath("");
  }
}

JNIEXPORT void JNICALL
Java_com_downpour_DownpourActivity_nativeOnTuFilePicked(JNIEnv* env, jobject /*thiz*/, jstring path) {
  if (path) {
    const char* c_path = env->GetStringUTFChars(path, nullptr);
    downpour::android::SetPendingTuPath(c_path ? c_path : "");
    env->ReleaseStringUTFChars(path, c_path);
  } else {
    downpour::android::SetPendingTuPath("");
  }
}

}  // extern "C"

#else

namespace downpour::android {
void InitializeJni() {}
void RequestPickIso() {}
void RequestPickTu() {}
void SetPendingIsoPath(const std::string&) {}
void SetPendingTuPath(const std::string&) {}
std::filesystem::path ConsumePendingIsoPath() { return {}; }
std::filesystem::path ConsumePendingTuPath() { return {}; }
void SetInternalFilesDir(const std::string&) {}
void SetExternalFilesDir(const std::string&) {}
std::filesystem::path GetInternalFilesDir() { return {}; }
std::filesystem::path GetExternalFilesDir() { return {}; }
void RequestRestartApp() {}
bool DownloadFileViaJava(const std::string&, const std::string&,
                        void (*)(uint64_t, uint64_t, void*), void*, std::string& error) {
  error = "Not on Android";
  return false;
}
void SetDriverConfig(const std::string&, const std::string&, const std::string&, bool, bool, bool) {}
}

#endif
