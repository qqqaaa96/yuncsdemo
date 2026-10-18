// ============================================================
// Radare2 JNI 桥接（最小实现）
//
// 设计原则：
//   · 不在 C 里实现分析逻辑，只把 r2 命令的结果原样带回 Kotlin
//   · Kotlin 侧负责解析
//   · 任何一步失败都返回错误字符串，不静默失败
//
// 全局只持有一个 RCore（单个分析会话）。
// ============================================================

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <pthread.h>

#include <r_core.h>

static RCore *g_core = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

// 工具：把 C 字符串转成 Java String，并释放
static jstring takeString(JNIEnv *env, char *s) {
    if (!s) return (*env)->NewStringUTF(env, "");
    jstring out = (*env)->NewStringUTF(env, s);
    free(s);
    return out;
}

// ------------------------------------------------------------
// version() -> String
// 作用：确认 lib 能加载、能创建 RCore
// ------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeVersion(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    if (!g_core) g_core = r_core_new();
    pthread_mutex_unlock(&g_lock);
    if (!g_core) return (*env)->NewStringUTF(env, "ERR: r_core_new failed");

    // r_core_version() 返回 const char *
    const char *v = r_core_version();
    return (*env)->NewStringUTF(env, v ? v : "unknown");
}

// ------------------------------------------------------------
// openFile(path) -> String
// 作用：打开文件并返回基本信息；失败返回 ERR:...
// ------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeOpen(JNIEnv *env, jclass clazz, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return (*env)->NewStringUTF(env, "ERR: null path");

    pthread_mutex_lock(&g_lock);

    if (!g_core) g_core = r_core_new();
    if (!g_core) {
        pthread_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return (*env)->NewStringUTF(env, "ERR: r_core_new failed");
    }

    // 关掉之前的文件
    r_core_cmd0(g_core, "o-*");

    RIODesc *desc = r_core_file_open(g_core, path, R_PERM_R, 0);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (!desc) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: open failed");
    }

    // 载入 bin 信息
    r_core_cmd0(g_core, "ob bin.load");
    char *info = r_core_cmd_str(g_core, "iI~arch,bits,class,machine");

    pthread_mutex_unlock(&g_lock);
    return takeString(env, info);
}

// ------------------------------------------------------------
// analyze() -> String
// 作用：跑 aaa（自动分析），返回空串表示成功
// ------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeAnalyze(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    if (!g_core) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: no file opened");
    }
    r_core_cmd0(g_core, "e anal.timeout=120");
    r_core_cmd0(g_core, "aaa");
    char *n = r_core_cmd_str(g_core, " aflc");
    pthread_mutex_unlock(&g_lock);
    return takeString(env, n);
}

// ------------------------------------------------------------
// cfgJson(addr) -> String
// 作用：返回该函数的基本块图（agfj，JSON）
// ------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeCfgJson(JNIEnv *env, jclass clazz, jlong addr) {
    pthread_mutex_lock(&g_lock);
    if (!g_core) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: no file opened");
    }

    char cmd[128];
    snprintf(cmd, sizeof(cmd), "agfj @ 0x%llx", (unsigned long long)addr);
    char *out = r_core_cmd_str(g_core, cmd);

    // 如果块图为空，退一步用 afbj（基本块列表）
    if (!out || strlen(out) < 3) {
        free(out);
        snprintf(cmd, sizeof(cmd), "afbj @ 0x%llx", (unsigned long long)addr);
        out = r_core_cmd_str(g_core, cmd);
    }

    pthread_mutex_unlock(&g_lock);
    return takeString(env, out);
}

// ------------------------------------------------------------
// pseudoC(addr) -> String
// 作用：尝试多种伪 C 命令，哪个有输出用哪个
// ------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativePseudoC(JNIEnv *env, jclass clazz, jlong addr) {
    pthread_mutex_lock(&g_lock);
    if (!g_core) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: no file opened");
    }

    const char *cmds[] = {
        "pdg @ 0x%llx",   // Ghidra 反编译（需 r2ghidra 插件）
        "pdc @ 0x%llx",   // 内置伪 C
        "pdf @ 0x%llx",   // 反汇编（兼底）
    };

    char *best = NULL;
    for (size_t i = 0; i < sizeof(cmds) / sizeof(cmds[0]); i++) {
        char cmd[128];
        snprintf(cmd, sizeof(cmd), cmds[i], (unsigned long long)addr);
        char *out = r_core_cmd_str(g_core, cmd);
        if (out && strlen(out) > 10) {
            free(best);
            best = out;
            // pdg 成功就不再尝试 pdc
            if (i == 0) break;
        } else {
            free(out);
        }
    }

    pthread_mutex_unlock(&g_lock);
    return takeString(env, best);
}

// ------------------------------------------------------------
// disasm(addr, count) -> String
// ------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeDisasm(JNIEnv *env, jclass clazz, jlong addr, jint count) {
    pthread_mutex_lock(&g_lock);
    if (!g_core) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: no file opened");
    }
    char cmd[160];
    snprintf(cmd, sizeof(cmd), "pd %d @ 0x%llx", (int)count, (unsigned long long)addr);
    char *out = r_core_cmd_str(g_core, cmd);
    pthread_mutex_unlock(&g_lock);
    return takeString(env, out);
}

// ------------------------------------------------------------
// close()
// ------------------------------------------------------------
JNIEXPORT void JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeClose(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    if (g_core) {
        r_core_free(g_core);
        g_core = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}
