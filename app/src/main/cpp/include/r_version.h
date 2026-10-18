/* r_version.h - 手写版本（对应 Android Reverse 内置的 Radare2）
 *
 * 原文件是 r_version.h.in 模板。
 *
 * 注意：这里写的版本号用于编译期（如条件编译、r2 -v 输出），
 * 运行时真正生效的是 libr_core.so 内部的版本。
 * 两者不一致通常不影响运行；若出现异常再按实际版本调整。
 */
#ifndef R_VERSION_H
#define R_VERSION_H 1

#define R_MESON_VERSION "5.9.8"
#define R2_VERSION_MAJOR 5
#define R2_VERSION_MINOR 9
#define R2_VERSION_PATCH 8
#define R2_VERSION_NUMBER 50908
#define R2_VERSION_COMMIT 0
#define R2_VERSION "5.9.8"
#define R2_GITTAP "5.9.8"
#define R2_GITTIP "unknown"
#define R2_BIRTH "2024"

#endif
