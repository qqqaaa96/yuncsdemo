// ============================================================
// Capstone 反汇编 JNI 桥接
//
// 为什么用 Capstone：
//   项目原来的 Arm64Disasm.kt 是手写解码器，只支持 ARM64，
//   而且有实际解码错误（MOVZ/NOP/ORR 别名都错过）。
//   Capstone 是工业级反汇编引擎，支持 ARM/ARM64/X86/X86-64 等。
//
// 设计要点：
//   1. 一次调用反汇编一整段，返回 Java 数组，
//      而不是每条指令调一次 JNI（那样开销大一个数量级）。
//   2. 架构用整数编码传递，避免 Java 那侧引用 capstone 枚举。
//   3. 出错时不抛异常，返回错误字符串，让 Kotlin 侧决定怎么处理。
// ============================================================

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include <capstone/capstone.h>

// ---- 架构编码（必须与 Kotlin 侧 CapstoneBridge.Arch 一致）----
#define ARCH_ARM64 1
#define ARCH_ARM   2
#define ARCH_X86   3
#define ARCH_X86_64 4

// 返回 Java: 用 long[] 打包，每 5 个 long 表示一条指令：
//   [0] address
//   [1] word（前 8 字节，不足补 0）
//   [2] size（指令字节数）
//   [3] flags 位图（见下方 F_* 常量）
//   [4] 预留
// 另外用 object[] 传文本：String[]{mnemonic, opStr}
//
// 但由于 JNI 里构造对象慢，这里拆成两个数组返回：
//   long[] data   —— 每 5 个 long 一条指令
//   String[] text —— 每 2 个 String 一条指令（mnemonic, op_str）

#define F_JUMP      (1 << 0)  // 无条件跳转（b / jmp）
#define F_CJUMP     (1 << 1)  // 条件跳转（b.eq / jne）
#define F_CALL      (1 << 2)  // 调用（bl / call）
#define F_RET       (1 << 3)  // 返回（ret）
#define F_INDIRECT  (1 << 4)  // 间接跳转/调用（br xN / blr xN / jmp reg）
#define F_INVALID   (1 << 5)  // 解码失败

/**
 * 把架构编码转成 capstone 的 arch/mode。
 *
 * thumb 参数仅对 ARM32 有意义（1 = Thumb，0 = ARM）。
 * 对其它架构忽略。
 */
static int map_arch(int arch, int thumb, cs_arch *out_arch, cs_mode *out_mode) {
    switch (arch) {
        case ARCH_ARM64:
            *out_arch = CS_ARCH_ARM64;
            *out_mode = CS_MODE_LITTLE_ENDIAN;
            return 0;
        case ARCH_ARM:
            *out_arch = CS_ARCH_ARM;
            // ARM32 由 Kotlin 侧传 thumb 标志区分两种指令集
            *out_mode = is64 ? CS_MODE_THUMB : CS_MODE_ARM;
            return 0;
        case ARCH_X86:
            *out_arch = CS_ARCH_X86;
            *out_mode = CS_MODE_32;
            return 0;
        case ARCH_X86_64:
            *out_arch = CS_ARCH_X86;
            *out_mode = CS_MODE_64;
            return 0;
        default:
            return -1;
    }
}

/** 从 capstone 指令组里判断控制流属性 */
static int classify(csh handle, cs_insn *insn) {
    int flags = 0;

    // 间接跳转：ARM 的 br/blr、X86 的 jmp/call 且操作数是寄存器
    // capstone 提供了 CS_GRP_JUMP / CS_GRP_CALL / CS_GRP_RET，
    // 但「间接」需要自己看操作数。
    bool is_ret = cs_insn_group(handle, insn, CS_GRP_RET);
    bool is_call = cs_insn_group(handle, insn, CS_GRP_CALL);
    bool is_jump = cs_insn_group(handle, insn, CS_GRP_JUMP);

    if (is_ret) {
        flags |= F_RET;
    }
    if (is_call) {
        flags |= F_CALL;
    }
    if (is_jump) {
        // 区分条件/无条件：看 mnemonic 是否带条件后缀
        // ARM64: b.xx / cbz / cbnz / tbz / tbnz
        // ARM:   bxx / blxx
        // X86:   jz / jne / jb ... 无条件只有 jmp
        const char *m = insn->mnemonic;
        bool unconditional = false;
        if (strcmp(m, "b") == 0 || strcmp(m, "jmp") == 0 || strcmp(m, "bl") == 0) {
            unconditional = true;
        }
        if (unconditional) {
            // b / jmp 是无条件跳转；bl 已在 is_call 里
            if (!is_call) flags |= F_JUMP;
        } else {
            flags |= F_CJUMP;
        }
    }

    // 间接：操作数第一个是寄存器（ARM: br xN / blr xN；X86: jmp rax / call rax）
    // 这里用简单启发式：操作数字符串以 x/w/r/e 开头，且不是纯立即数
    if ((flags & (F_CALL | F_JUMP | F_CJUMP)) != 0) {
        const char *op = insn->op_str;
        // 跳过空白
        while (*op == ' ') op++;
        bool looks_reg = false;
        if (*op == 'x' || *op == 'w') {
            // ARM64 寄存器 x0-x30 / w0-w30
            looks_reg = true;
        } else if (*op == 'r' || *op == 'e') {
            // X86 寄存器 rax / eax
            looks_reg = true;
        }
        // 排除 [sp, #8] 这类内存操作数（虽然跳转不会出现）
        if (looks_reg && *op != '[') {
            flags |= F_INDIRECT;
            // 间接跳转不当作普通无条件跳转
            if (!is_call) {
                flags &= ~F_JUMP;
            }
            if (is_call) {
                flags |= F_INDIRECT;
            }
        }
    }

    return flags;
}

/**
 * 反汇编一段代码。
 *
 * 返回 long[][]：
 *   long[0] = 指令数量
 *   long[1] = 每 5 个 long 一条指令的数据
 *
 * 单独再返回 String[] 文本，保证中文/特殊字符不丢。
 */
JNIEXPORT jobjectArray JNICALL
Java_sy_great_toolbox_tools_CapstoneBridge_nativeDisasm(
        JNIEnv *env, jclass clazz,
        jint arch, jint isThumb,
        jbyteArray code, jlong baseAddr,
        jint count) {

    cs_arch cs_arch_v;
    cs_mode cs_mode_v;
    if (map_arch(arch, (int)isThumb, &cs_arch_v, &cs_mode_v) != 0) {
        return NULL;
    }

    jsize len = (*env)->GetArrayLength(env, code);
    if (len <= 0) return NULL;

    jbyte *buf = (*env)->GetByteArrayElements(env, code, NULL);
    if (!buf) return NULL;

    csh handle;
    if (cs_open(cs_arch_v, cs_mode_v, &handle) != CS_ERR_OK) {
        (*env)->ReleaseByteArrayElements(env, code, buf, JNI_ABORT);
        return NULL;
    }

    // 需要这些细节：
    //   CS_OPT_DETAIL    —— 拿指令组（jump/call/ret）
    //   CS_OPT_SYNTAX    —— 默认 Intel/ARM 风格即可
    cs_option(handle, CS_OPT_DETAIL, CS_OPT_ON);
    // 跳过无效数据，遇到坏字节就停在最后一个有效指令
    cs_option(handle, CS_OPT_SKIPDATA, CS_OPT_ON);

    cs_insn *insn = NULL;
    size_t n = cs_disasm(handle, (const uint8_t *)buf, (size_t)len,
                         (uint64_t)baseAddr, (size_t)count, &insn);

    if (n == 0) {
        cs_free(insn, n);
        cs_close(&handle);
        (*env)->ReleaseByteArrayElements(env, code, buf, JNI_ABORT);
        return NULL;
    }

    // 构造结果数组：[0] = insnCount，后面每条 5 个 long
    jsize dataLen = 1 + (jsize)n * 5;
    jlongArray dataArr = (*env)->NewLongArray(env, dataLen);
    jlong *data = (jlong *)malloc(sizeof(jlong) * dataLen);
    data[0] = (jlong)n;

    // 文本：每条 2 个 String（mnemonic, op_str）
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray textArr = (*env)->NewObjectArray(env, (jsize)n * 2, strCls, NULL);

    for (size_t i = 0; i < n; i++) {
        cs_insn *cur = &insn[i];
        int base = 1 + (int)i * 5;

        // word：最多取前 8 字节，小端拼
        unsigned long long w = 0;
        int copy = cur->size < 8 ? cur->size : 8;
        for (int b = 0; b < copy; b++) {
            w |= ((unsigned long long)cur->bytes[b]) << (8 * b);
        }

        int flags = cur->size > 0 ? classify(handle, cur) : F_INVALID;

        data[base + 0] = (jlong)cur->address;
        data[base + 1] = (jlong)w;
        data[base + 2] = (jlong)cur->size;
        data[base + 3] = (jlong)flags;
        data[base + 4] = 0;

        jstring mn = (*env)->NewStringUTF(env, cur->mnemonic ? cur->mnemonic : "");
        jstring op = (*env)->NewStringUTF(env, cur->op_str ? cur->op_str : "");
        (*env)->SetObjectArrayElement(env, textArr, (jsize)i * 2, mn);
        (*env)->SetObjectArrayElement(env, textArr, (jsize)i * 2 + 1, op);
        (*env)->DeleteLocalRef(env, mn);
        (*env)->DeleteLocalRef(env, op);
    }

    (*env)->SetLongArrayRegion(env, dataArr, 0, dataLen, data);

    free(data);
    cs_free(insn, n);
    cs_close(&handle);
    (*env)->ReleaseByteArrayElements(env, code, buf, JNI_ABORT);

    // 返回 Object[]{ long[], String[] }
    jclass objCls = (*env)->FindClass(env, "java/lang/Object");
    jobjectArray result = (*env)->NewObjectArray(env, 2, objCls, NULL);
    (*env)->SetObjectArrayElement(env, result, 0, dataArr);
    (*env)->SetObjectArrayElement(env, result, 1, textArr);

    (*env)->DeleteLocalRef(env, dataArr);
    (*env)->DeleteLocalRef(env, textArr);

    return result;
}

/** Capstone 版本号，用于自检 */
JNIEXPORT jint JNICALL
Java_sy_great_toolbox_tools_CapstoneBridge_nativeVersionMajor(JNIEnv *env, jclass clazz) {
    return (jint)CS_API_MAJOR;
}

JNIEXPORT jint JNICALL
Java_sy_great_toolbox_tools_CapstoneBridge_nativeVersionMinor(JNIEnv *env, jclass clazz) {
    return (jint)CS_API_MINOR;
}
