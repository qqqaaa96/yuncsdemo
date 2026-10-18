/* r_userconf.h - 手写版本（Android arm64）
 *
 * 原文件是 r_userconf.h.acr 模板，configure 时生成。
 * 这里按 Android arm64 的典型配置直接写出，避免依赖 autotools。
 *
 * 取值依据：
 *   · libr_arch.so 有 12MB，说明内置了 capstone 反汇编器 → WANT_CAPSTONE 1
 *   · 项目只有 arm64-v8a → 不考虑其它架构
 *   · 不需要 qjs / v35 / zydis / sqsh
 */
#ifndef R2_CONFIGURE_H
#define R2_CONFIGURE_H

#ifdef __cplusplus
extern "C" {
#endif

#include "r_version.h"

#define R_CHECKS_LEVEL 0
#define WANT_DEBUGSTUFF 0
#define R_CRITICAL_ENABLED 0
#define DEBUGGER 0
#define WANT_ZIP 1
#define HAVE_DECL_ADDR_NO_RANDOMIZE 1
#define HAVE_ARC4RANDOM_UNIFORM 1
#define HAVE_EXPLICIT_BZERO 1
#define HAVE_EXPLICIT_MEMSET 1
#define HAVE_MEMMEM 1
#define HAVE_CLOCK_NANOSLEEP 1
#define HAVE_SIGACTION 1
#define WANT_THREADS 1
#define WANT_QJS 0
#define WANT_V35 0
#define WANT_CAPSTONE 1
#define WANT_ZYDIS 0
#define USE_SYS_ZYDIS 0
#define HAVE_LINUX_CAN_H 1
#define R2_USE_BUNDLE_PREFIX 0
#define R2_USE_SQSH 0
#ifndef R2_WASM_BROWSER
#define R2_WASM_BROWSER 0
#endif

#define R_BUILDSYSTEM "android-cmake"
#define R2_CSVERSION 5
#define WITH_STATIC_THEMES 0

#ifdef __cplusplus
}
#endif

#endif
