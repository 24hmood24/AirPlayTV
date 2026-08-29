#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string>
#include <cstring>
#include <unistd.h>
#include <unwind.h>
#include "dnssd.h"


extern "C" {
#include "raop.h"
#include "stream.h"
#include "logger.h"
}

#define TAG "mojibo-native"

static void nativeLogToScreen(const char *msg);

#define LOGI(...) do { \
    char _logbuf[512]; \
    snprintf(_logbuf, sizeof(_logbuf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", _logbuf); \
    nativeLogToScreen(_logbuf); \
} while (0)

#define LOGE(...) do { \
    char _logbuf[512]; \
    snprintf(_logbuf, sizeof(_logbuf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", _logbuf); \
    nativeLogToScreen(_logbuf); \
} while (0)

static JavaVM *g_vm = nullptr;
static jclass g_bridgeClass = nullptr;
static jmethodID g_onVideoNalUnit = nullptr;
static jmethodID g_onAudioPcm = nullptr;
static jmethodID g_onNativeLog = nullptr;
static jmethodID g_onVideoSize = nullptr;
static jmethodID g_onMirroringStopped = nullptr;
static jmethodID g_onSetVolume = nullptr;
static jmethodID g_onConnectionInit = nullptr;
static raop_t *g_raop = nullptr;

#include <csignal>
#include <fcntl.h>
#include <cstdio>

static char g_crash_path[512] = {0};

#include <dlfcn.h>

struct BacktraceState {
    void **current;
    void **end;
};

static _Unwind_Reason_Code unwindCallback(struct _Unwind_Context *context, void *arg) {
    BacktraceState *state = (BacktraceState *) arg;
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc && state->current != state->end) {
        *state->current++ = (void *) pc;
    }
    return _URC_NO_REASON;
}

static void crash_signal_handler(int sig, siginfo_t *info, void *ctx) {
    ucontext_t *uc = (ucontext_t *) ctx;

#if defined(__aarch64__)
    uintptr_t fp = uc->uc_mcontext.regs[29];
#elif defined(__arm__)
    uintptr_t fp = uc->uc_mcontext.arm_fp;
#else
    uintptr_t fp = 0;
#endif

    int fd = -1;
    if (g_crash_path[0] != '\0') {
        fd = open(g_crash_path, O_CREAT | O_WRONLY | O_TRUNC, 0666);
    }

    char line[256];
    int len = snprintf(line, sizeof(line), "=== crash signal=%d ===\n", sig);
    __android_log_print(ANDROID_LOG_ERROR, "MojiboCrash", "=== crash signal=%d ===", sig);
    if (fd >= 0) write(fd, line, len);

    void *buf[32];
    BacktraceState state = {buf, buf + 32};
    _Unwind_Backtrace(unwindCallback, &state);
    int n = (int) (state.current - buf);
    for (int i = 0; i < n; i++) {
        Dl_info dlinfo;
        void *addr = buf[i];
        if (dladdr(addr, &dlinfo)) {
            len = snprintf(line, sizeof(line), "#%d addr=%p lib=%s off=0x%lx sym=%s\n",
                           i, addr,
                           dlinfo.dli_fname ? dlinfo.dli_fname : "?",
                           (unsigned long) ((uintptr_t) addr - (uintptr_t) dlinfo.dli_fbase),
                           dlinfo.dli_sname ? dlinfo.dli_sname : "?");
        } else {
            len = snprintf(line, sizeof(line), "#%d addr=%p (no info)\n", i, addr);
        }
        __android_log_print(ANDROID_LOG_ERROR, "MojiboCrash", "%s", line);
        if (fd >= 0) write(fd, line, len);
    }

    if (fd >= 0) close(fd);

    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_crash_handlers() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_signal_handler;
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mojibo_airplaytv_player_NativeBridge_setCrashLogPath(
        JNIEnv *env, jobject, jstring jPath) {
    const char *path = env->GetStringUTFChars(jPath, nullptr);
    strncpy(g_crash_path, path, sizeof(g_crash_path) - 1);
    env->ReleaseStringUTFChars(jPath, path);
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    install_crash_handlers();
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mojibo_airplaytv_player_NativeBridge_nativeInitClassCache(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    g_bridgeClass = (jclass) env->NewGlobalRef(clazz);
g_onVideoNalUnit = env->GetStaticMethodID(g_bridgeClass, "onVideoNalUnit", "([BJ)V");
g_onAudioPcm = env->GetStaticMethodID(g_bridgeClass, "onAudioPcm", "([B)V");
g_onNativeLog = env->GetStaticMethodID(g_bridgeClass, "onNativeLog", "(Ljava/lang/String;)V");
g_onVideoSize = env->GetStaticMethodID(g_bridgeClass, "onVideoSize", "(II)V");
g_onMirroringStopped = env->GetStaticMethodID(g_bridgeClass, "onMirroringStopped", "()V");
    g_onConnectionInit = env->GetStaticMethodID(g_bridgeClass, "onConnectionInit", "()V");
    g_onSetVolume = env->GetStaticMethodID(g_bridgeClass, "onSetVolume", "(F)V");
}

static void nativeLogToScreen(const char *msg) {
    if (!g_vm || !g_bridgeClass || !g_onNativeLog) return;
    JNIEnv *env;
    bool attached = false;
    int status = g_vm->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    jstring jmsg = env->NewStringUTF(msg);
    env->CallStaticVoidMethod(g_bridgeClass, g_onNativeLog, jmsg);
    env->DeleteLocalRef(jmsg);
    if (attached) g_vm->DetachCurrentThread();
}

static JNIEnv *attachThread(bool *didAttach) {
    JNIEnv *env;
    int status = g_vm->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        *didAttach = true;
    } else {
        *didAttach = false;
    }
    return env;
}

static void cb_audio_set_volume(void *cls, float volume) {
    if (!g_bridgeClass || !g_onSetVolume) return;
    bool attached;
    JNIEnv *env = attachThread(&attached);
    env->CallStaticVoidMethod(g_bridgeClass, g_onSetVolume, (jfloat) volume);
    if (attached) g_vm->DetachCurrentThread();
}

static int video_frame_counter = 0;
static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {

    if (!data) {
        LOGE("video data NULL");
        return;
    }
    if (video_frame_counter++ % 15 == 0) {
        LOGI("cb_video_process CALLED #%d, data_len=%d", video_frame_counter, data->data_len);
    }

    if (!data->data || data->data_len <= 0) {
        LOGE("video buffer invalid");
        return;
    }
    bool attached;
    JNIEnv *env = attachThread(&attached);
    jbyteArray arr = env->NewByteArray(data->data_len);
    env->SetByteArrayRegion(arr, 0, data->data_len, (const jbyte *) data->data);
    env->CallStaticVoidMethod(g_bridgeClass, g_onVideoNalUnit, arr, (jlong) data->ntp_time_local);
    env->DeleteLocalRef(arr);
    if (attached) g_vm->DetachCurrentThread();
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {

    if (!data) {
        LOGE("audio data NULL");
        return;
    }

    if (!data->data || data->data_len <= 0) {
        LOGE("audio buffer invalid");
        return;
    }
    bool attached;
    JNIEnv *env = attachThread(&attached);
    jbyteArray arr = env->NewByteArray(data->data_len);
    env->SetByteArrayRegion(arr, 0, data->data_len, (const jbyte *) data->data);
    env->CallStaticVoidMethod(g_bridgeClass, g_onAudioPcm, arr);
    env->DeleteLocalRef(arr);
    if (attached) g_vm->DetachCurrentThread();
}
static void cb_video_pause(void *cls) {}
static void cb_video_resume(void *cls) {}
static void cb_conn_feedback(void *cls) {}
static void cb_conn_reset(void *cls, int reason) { LOGI("conn_reset reason=%d", reason); }
static void cb_video_reset(void *cls, reset_type_t reset_type) {}
static void cb_conn_init(void *cls) {
    LOGI("=== AIRPLAY CLIENT CONNECTED ===");
    bool didAttach = false;
    JNIEnv *env = attachThread(&didAttach);
    if (env && g_bridgeClass && g_onConnectionInit) {
        env->CallStaticVoidMethod(g_bridgeClass, g_onConnectionInit);
    }
    if (didAttach) g_vm->DetachCurrentThread();
}
static void cb_conn_destroy(void *cls) {
    LOGI("=== AIRPLAY CLIENT DISCONNECTED ===");
    if (g_bridgeClass && g_onMirroringStopped) {
        bool attached;
        JNIEnv *env = attachThread(&attached);
        env->CallStaticVoidMethod(g_bridgeClass, g_onMirroringStopped);
        if (attached) g_vm->DetachCurrentThread();
    }
}
static void cb_conn_teardown(void *cls, bool *teardown_96, bool *teardown_110) {
    *teardown_96 = true; *teardown_110 = true;
}
static void cb_audio_flush(void *cls) {}
static void cb_video_flush(void *cls) {}
static double cb_audio_set_client_volume(void *cls) { return 1.0; }
static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen) {}
static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen) {}
static void cb_audio_stop_coverart_rendering(void *cls) {}
static void cb_audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote_header) {}
static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {}
static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {}
static void cb_mirror_video_running(void *cls, bool is_running) { LOGI("mirror_video_running=%d", is_running); }
static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) { *admit = true; }
static void cb_display_pin(void *cls, char *pin) { LOGI("display_pin=%s", pin); }
static void cb_register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {}
static bool cb_check_register(void *cls, const char *pk_str) { return true; }
static const char *cb_passwd(void *cls, int *len) { *len = 0; return nullptr; }
static void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id) {}
static int cb_video_set_codec(void *cls, video_codec_t codec) { LOGI("video_set_codec=%d", codec); return 0; }

static void cb_video_report_size(void *cls, float *width_source, float *height_source, float *width, float *height) {
    LOGI("cb_video_report_size CALLED w=%f h=%f", *width, *height);
    if (!g_bridgeClass || !g_onVideoSize) return;
    bool attached;
    JNIEnv *env = attachThread(&attached);
    env->CallStaticVoidMethod(g_bridgeClass, g_onVideoSize, (jint) *width, (jint) *height);
    if (attached) g_vm->DetachCurrentThread();
}
static void cb_on_video_play(void *cls, const char *location, const float start_position) {}
static void cb_on_video_scrub(void *cls, const float position) {}
static void cb_on_video_rate(void *cls, const float rate) {}
static void cb_on_video_stop(void *cls) {}
static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *playback_video) {}
static float cb_on_video_playlist_remove(void *cls) { return 0.0f; }

static void cb_raop_log(void *cls, int level, const char *msg) {
    LOGI("raop:%d %s", level, msg);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mojibo_airplaytv_player_VideoDecoder_nativeYuv420ToRgb(
        JNIEnv *env, jobject /* this */,
jbyteArray yuv_, jint width, jint height, jint stride, jint sliceHeight,
        jint scale, jintArray out_) {
jbyte *yuv = env->GetByteArrayElements(yuv_, nullptr);
jint *out = env->GetIntArrayElements(out_, nullptr);

int ySize = stride * sliceHeight;
int outWidth = width / scale;
int outHeight = height / scale;

for (int outRow = 0; outRow < outHeight; outRow++) {
int row = outRow * scale;
int yRow = row * stride;
int uvRow = ySize + (row / 2) * stride;
int outRowOffset = outRow * outWidth;
for (int outCol = 0; outCol < outWidth; outCol++) {
int col = outCol * scale;
int y = (yuv[yRow + col] & 0xFF) - 16;
int uvCol = (col / 2) * 2;
int u = (yuv[uvRow + uvCol] & 0xFF) - 128;
int v = (yuv[uvRow + uvCol + 1] & 0xFF) - 128;

int r = (1192 * y + 1836 * v) >> 10;
int g = (1192 * y - 218 * u - 546 * v) >> 10;
int b = (1192 * y + 2163 * u) >> 10;

r = r < 0 ? 0 : (r > 255 ? 255 : r);
g = g < 0 ? 0 : (g > 255 ? 255 : g);
b = b < 0 ? 0 : (b > 255 ? 255 : b);

out[outRowOffset + outCol] = (0xFF << 24) | (r << 16) | (g << 8) | b;
}
}

env->ReleaseByteArrayElements(yuv_, yuv, JNI_ABORT);
env->ReleaseIntArrayElements(out_, out, 0);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mojibo_airplaytv_player_NativeBridge_startAirplayServer(
        JNIEnv *env, jobject, jstring jDeviceId, jstring jDeviceName, jint port) {

    const char *deviceId = env->GetStringUTFChars(jDeviceId, nullptr);
    const char *deviceNameFromJava = env->GetStringUTFChars(jDeviceName, nullptr);

    static raop_callbacks_t callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.audio_process = cb_audio_process;
    callbacks.video_process = cb_video_process;
    callbacks.video_pause = cb_video_pause;
    callbacks.video_resume = cb_video_resume;
    callbacks.conn_feedback = cb_conn_feedback;
    callbacks.conn_reset = cb_conn_reset;
    callbacks.video_reset = cb_video_reset;
    callbacks.conn_init = cb_conn_init;
    callbacks.conn_destroy = cb_conn_destroy;
    callbacks.conn_teardown = cb_conn_teardown;
    callbacks.audio_flush = cb_audio_flush;
    callbacks.video_flush = cb_video_flush;
    callbacks.audio_set_client_volume = cb_audio_set_client_volume;
    callbacks.audio_set_volume = cb_audio_set_volume;
    callbacks.audio_set_metadata = cb_audio_set_metadata;
    callbacks.audio_set_coverart = cb_audio_set_coverart;
    callbacks.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    callbacks.audio_remote_control_id = cb_audio_remote_control_id;
    callbacks.audio_set_progress = cb_audio_set_progress;
    callbacks.audio_get_format = cb_audio_get_format;
    callbacks.video_report_size = cb_video_report_size;
    callbacks.mirror_video_running = cb_mirror_video_running;
    callbacks.report_client_request = cb_report_client_request;
    callbacks.display_pin = cb_display_pin;
    callbacks.register_client = cb_register_client;
    callbacks.check_register = cb_check_register;
    callbacks.passwd = cb_passwd;
    callbacks.export_dacp = cb_export_dacp;
    callbacks.video_set_codec = cb_video_set_codec;
    callbacks.video_report_size = cb_video_report_size;
    callbacks.on_video_play = cb_on_video_play;
    callbacks.on_video_scrub = cb_on_video_scrub;
    callbacks.on_video_rate = cb_on_video_rate;
    callbacks.on_video_stop = cb_on_video_stop;
    callbacks.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    callbacks.on_video_playlist_remove = cb_on_video_playlist_remove;

    g_raop = raop_init(&callbacks);
    if (!g_raop) {
        LOGE("raop_init failed");
        env->ReleaseStringUTFChars(jDeviceId, deviceId);
        env->ReleaseStringUTFChars(jDeviceName, deviceNameFromJava);
        return -1;
    }

    raop_set_log_callback(g_raop, cb_raop_log, nullptr);

    std::string keyfilePath = "/data/data/com.mojibo.airplaytv/files/airplay.key";

    LOGI("Starting raop_init2 key=%s", keyfilePath.c_str());

    raop_init2(g_raop, 0, deviceId, keyfilePath.c_str());
    LOGI("raop_init2 done");

    raop_set_plist(g_raop, "width", 1470);
    raop_set_plist(g_raop, "height", 956);
    raop_set_plist(g_raop, "maxFPS", 24);
    LOGI("requested resolution: 1470x956 @ 24fps");

    unsigned int hw_tmp[6];
    unsigned char hw_addr[6];
    sscanf(deviceId, "%2x:%2x:%2x:%2x:%2x:%2x",
           &hw_tmp[0], &hw_tmp[1], &hw_tmp[2], &hw_tmp[3], &hw_tmp[4], &hw_tmp[5]);
    for (int i = 0; i < 6; i++) hw_addr[i] = (unsigned char) hw_tmp[i];

    int dnssd_error = 0;
    dnssd_t *dnssd = dnssd_init(deviceNameFromJava, (int) strlen(deviceNameFromJava), (const char *) hw_addr, 6, 0, &dnssd_error);
    if (dnssd) {
        raop_set_dnssd(g_raop, dnssd);
        LOGI("dnssd_init OK, raop_set_dnssd done");
    } else {
        LOGE("dnssd_init failed, error=%d", dnssd_error);
    }

    raop_set_log_level(g_raop, LOGGER_DEBUG);

    unsigned short assignedPort = (unsigned short) port;
    raop_set_port(g_raop, assignedPort);

    LOGI("Starting RAOP HTTP server on requested port %d", assignedPort);

    if (raop_start_httpd(g_raop, &assignedPort) < 0) {
        LOGE("raop_start_httpd failed");
        env->ReleaseStringUTFChars(jDeviceId, deviceId);
        env->ReleaseStringUTFChars(jDeviceName, deviceNameFromJava);
        return -1;
    }

    LOGI("RAOP READY port=%d device=%s", assignedPort, deviceId);
    env->ReleaseStringUTFChars(jDeviceId, deviceId);
    env->ReleaseStringUTFChars(jDeviceName, deviceNameFromJava);
    return (jint) assignedPort;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mojibo_airplaytv_player_NativeBridge_stopAirplayServer(JNIEnv *env, jobject) {
    if (g_raop) {
        raop_stop_httpd(g_raop);
        raop_destroy(g_raop);
        g_raop = nullptr;
    }
}