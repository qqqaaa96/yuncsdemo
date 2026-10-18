// ============================================================
// Radare2 JNI 桥接（正式头文件版）
//
// 头文件来自：
//   · radare2-master/libr/include  →  cpp/include/
//   · sdb-master/include/sdb       →  cpp/include/sdb/
//
// 用真实头文件的好处：
//   · 函数签名、常量、结构体全部由编译器检查
//   · 参数写错会在编译期就报错，而不是运行时崩
// ============================================================

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#include <r_core.h>

static RCore *g_core = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

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
    // r_core_version() 不在公开头文件里（r_core.h / r_main.h 都没有），
    // 不能直接调。改用命令 `?V` 拿版本，并顺便验证 RCore 能创建。
    pthread_mutex_lock(&g_lock);
    if (!g_core) g_core = r_core_new();
    if (!g_core) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: r_core_new failed");
    }
    char *v = r_core_cmd_str(g_core, "?V");
    pthread_mutex_unlock(&g_lock);

    if (!v) return (*env)->NewStringUTF(env, "unknown");
    jstring out = (*env)->NewStringUTF(env, v);
    free(v);
    return out;
}

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

    // 关掉之前打开的文件
    r_core_cmd0(g_core, "o-*");

    // R_PERM_R 是真实常量（已由头文件定义）
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

JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeAnalyze(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    if (!g_core) {
        pthread_mutex_unlock(&g_lock);
        return (*env)->NewStringUTF(env, "ERR: no file opened");
    }
    r_core_cmd0(g_core, "e anal.timeout=120");
    r_core_cmd0(g_core, "aaa");
    char *n = r_core_cmd_str(g_core, "aflc");
    pthread_mutex_unlock(&g_lock);
    return takeString(env, n);
}

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

    if (!out || strlen(out) < 3) {
        free(out);
        snprintf(cmd, sizeof(cmd), "afbj @ 0x%llx", (unsigned long long)addr);
        out = r_core_cmd_str(g_core, cmd);
    }

    pthread_mutex_unlock(&g_lock);
    return takeString(env, out);
}

JNIEXPORT jstring JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativePseudoC(JNIEnv *env, jclass clazz, jlong addr) {
    pthread_mutex_lock(&g_lock);
    if (!g_core) {
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
        char *out = r_core_cmd_str(g_core, cmd);
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

JNIEXPORT void JNICALL
Java_sy_great_toolbox_tools_RadareBridge_nativeClose(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    if (g_core) {
        r_core_free(g_core);
        g_core = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}
