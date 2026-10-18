// ============================================================
// Radare2 JNI 桥接（dlsym 版）
//
// 为什么不用 r_core.h：
//   r2 的头文件嵌套极深（libr/include 下 70+ 个，还依赖 sdb 子项目
//   的 ht.h / ht_up.h / set.h 等一整套）。逐个补永远补不完。
//
//   而 libr_*.so 已经编好了，我们只需要**在运行时拿到函数地址**。
//   用 dlopen + dlsym 就能完全绕开头文件。
//
// 函数签名取自 r2 公开头文件（r_core.h / r_main.h）：
//   RCore *r_core_new(void);
//   void   r_core_free(RCore *core);
//   RIODesc *r_core_file_open(RCore *core, const char *file, int flags, ut64 loadaddr);
//   char  *r_core_cmd_str(RCore *core, const char *cmd);
//   int    r_core_cmd0(RCore *core, const char *cmd);
//   const char *r_core_version(void);
//
// 注意：只用 void* 与基本类型，不碰任何 r2 结构体字段。
// ============================================================

#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>

#define TAG "radare_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- 函数指针类型 ----
typedef void *(*fn_core_new)(void);
typedef void (*fn_core_free)(void *core);
typedef void *(*fn_file_open)(void *core, const char *file, int flags, unsigned long long loadaddr);
typedef char *(*fn_cmd_str)(void *core, const char *cmd);
typedef int (*fn_cmd0)(void *core, const char *cmd);
typedef const char *(*fn_version)(void);

// ---- 全局状态 ----
static void *g_lib = NULL;          // dlopen 句柄
static void *g_core = NULL;         // RCore*
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static fn_core_new  p_core_new  = NULL;
static fn_core_free p_core_free = NULL;
static fn_file_open p_file_open = NULL;
static fn_cmd_str   p_cmd_str   = NULL;
static fn_cmd0      p_cmd0      = NULL;
static fn_version   p_version   = NULL;

// 逐个库尝试 dlopen，直到拿到核心符号
static const char *LIB_CANDIDATES[] = {
    "libradare_bridge_loader.so",   // 占位（不存在，跳过）
    "libr_core.so",
    "libandroid-reverse-native.so",
};

/**
 * 加载 libr_core.so 并解析符号。
 * 返回 NULL 表示成功，否则返回错误文本。
 */
static const char *ensureLoaded(void) {
    if (g_lib && p_core_new) return NULL;

    for (size_t i = 0; i < sizeof(LIB_CANDIDATES) / sizeof(LIB_CANDIDATES[0]); i++) {
        void *h = dlopen(LIB_CANDIDATES[i], RTLD_NOW | RTLD_GLOBAL);
        if (!h) continue;

        fn_core_new v = (fn_core_new)dlsym(h, "r_core_new");
        if (!v) {
            dlclose(h);
            continue;
        }
        g_lib = h;
        p_core_new  = v;
        p_core_free = (fn_core_free)dlsym(h, "r_core_free");
        p_file_open = (fn_file_open)dlsym(h, "r_core_file_open");
        p_cmd_str   = (fn_cmd_str)dlsym(h, "r_core_cmd_str");
        p_cmd0      = (fn_cmd0)dlsym(h, "r_core_cmd0");
        p_version   = (fn_version)dlsym(h, "r_core_version");
        LOGI("loaded %s", LIB_CANDIDATES[i]);
        return NULL;
    }

    return "dlopen failed: libr_core.so not loadable";
}

static void *ensureCore(void) {
    if (g_core) return g_core;
    if (ensureLoaded() != NULL) return NULL;
    g_core = p_core_new();
    return g_core;
}

/** 释放 C 字符串并转成 Java String */
static jstring takeString(JNIEnv *env, char *s) {
    if (!s) return (*env)->NewStringUTF(env, "");
    jstring out = (*env)->NewStringUTF(env, s);
    free(s);
    return out;
}

// ============================================================
// JNI 实现
// ============================================================

JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeVersion(JNIEnv *env, jclass clazz) {
    const char *err = ensureLoaded();
    if (err) return (*env)->NewStringUTF(env, err);
    const char *v = p_version ? p_version() : "unknown";
    return (*env)->NewStringUTF(env, v ? v : "unknown");
}

JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeOpen(JNIEnv *env, jclass clazz, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return (*env)->NewStringUTF(env, "ERR: null path");

    pthread_mutex_lock(&g_lock);

    void *core = ensureCore();
    if (!core) {
        pthread_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return (*env)->NewStringUTF(env, "ERR: core init failed");
    }

    // 关掉之前打开的文件
    if (p_cmd0) p_cmd0(core, "o-*");

    // flags: R_PERM_R = 4
    void *desc = p_file_open ? p_file_open(core, path, 4, 0) : NULL;
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (!desc) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: open failed");
    }

    // 载入 bin 信息
    if (p_cmd0) p_cmd0(core, "ob bin.load");
    char *info = p_cmd_str ? p_cmd_str(core, "iI~arch,bits,class,machine") : NULL;

    pthread_mutex_unlock(&g_lock);
    return takeString(env, info);
}

JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeAnalyze(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    if (!g_core || !p_cmd0) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: no file opened");
    }
    p_cmd0(g_core, "e anal.timeout=120");
    p_cmd0(g_core, "aaa");
    char *n = p_cmd_str ? p_cmd_str(g_core, "aflc") : NULL;
    pthread_mutex_unlock(&g_lock);
    return takeString(env, n);
}

JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeCfgJson(JNIEnv *env, jclass clazz, jlong addr) {
    pthread_mutex_lock(&g_lock);
    if (!g_core || !p_cmd_str) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: no file opened");
    }

    char cmd[128];
    snprintf(cmd, sizeof(cmd), "agfj @ 0x%llx", (unsigned long long)addr);
    char *out = p_cmd_str(g_core, cmd);

    if (!out || strlen(out) < 3) {
        free(out);
        snprintf(cmd, sizeof(cmd), "afbj @ 0x%llx", (unsigned long long)addr);
        out = p_cmd_str(g_core, cmd);
    }

    pthread_mutex_unlock(&g_lock);
    return takeString(env, out);
}

JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativePseudoC(JNIEnv *env, jclass clazz, jlong addr) {
    pthread_mutex_lock(&g_lock);
    if (!g_core || !p_cmd_str) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: no file opened");
    }

    const char *cmds[] = {
        "pdg @ 0x%llx",
        "pdc @ 0x%llx",
        "pdf @ 0x%llx",
    };

    char *best = NULL;
    for (size_t i = 0; i < sizeof(cmds) / sizeof(cmds[0]); i++) {
        char cmd[128];
        snprintf(cmd, sizeof(cmd), cmds[i], (unsigned long long)addr);
        char *out = p_cmd_str(g_core, cmd);
        if (out && strlen(out) > 10) {
            free(best);
            best = out;
            if (i == 0) break;
        } else {
            free(out);
        }
    }

    pthread_mutex_unlock(&g_lock);
    return takeString(env, best);
}

JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeDisasm(JNIEnv *env, jclass clazz, jlong addr, jint count) {
    pthread_mutex_lock(&g_lock);
    if (!g_core || !p_cmd_str) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: no file opened");
    }
    char cmd[160];
    snprintf(cmd, sizeof(cmd), "pd %d @ 0x%llx", (int)count, (unsigned long long)addr);
    char *out = p_cmd_str(g_core, cmd);
    pthread_mutex_unlock(&g_lock);
    return takeString(env, out);
}

JNIEXPORT void JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeClose(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    if (g_core && p_core_free) {
        p_core_free(g_core);
        g_core = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}
