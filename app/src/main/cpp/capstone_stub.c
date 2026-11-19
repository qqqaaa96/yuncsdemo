// ============================================================
// Capstone 降级空实现
//
// 用途：CI 拉不到 Capstone 源码时编译这个，保证：
//   · App 能正常编译打包
//   · Kotlin 侧 CapstoneBridge.load() 拿到 true（库存在）
//   · 但 nativeDisasm() 返回 NULL，CapstoneBridge.disasm() 得空列表
//   · Arm64Disasm.decode() 自动回退到手写解码器
//
// 也就是说：功能降级，但不会崩、不会白屏。
// ============================================================

#include <jni.h>
#include <stddef.h>

JNIEXPORT jobjectArray JNICALL
Java_sy_great_toolbox_tools_CapstoneBridge_nativeDisasm(
        JNIEnv *env, jclass clazz,
        jint arch, jint isThumb,
        jbyteArray code, jlong baseAddr,
        jint count) {
    (void)env; (void)clazz;
    (void)arch; (void)isThumb;
    (void)code; (void)baseAddr; (void)count;
    // 返回 null → Kotlin 侧 disasm() 得到空列表 → 回退手写解码器
    return NULL;
}

JNIEXPORT jint JNICALL
Java_sy_great_toolbox_tools_CapstoneBridge_nativeVersionMajor(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return 0;   // 0.0 = 未启用
}

JNIEXPORT jint JNICALL
Java_sy_great_toolbox_tools_CapstoneBridge_nativeVersionMinor(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return 0;
}
