/* sdb/sdb.h - 最小兼容层
 *
 * 背景：
 *   Radare2 的 r_list.h 等头文件会 #include <sdb/sdb.h>。
 *   sdb 是 Radare2 的独立子项目，源码不在 radare2-master 里
 *   （subprojects/sdb 只有下载配置）。
 *
 *   但是：sdb_* 的实现已经编进了 libr_util.so，
 *   所以只需要**声明**，不需要实现。
 *
 * 本文件只声明 r2 头文件实际用到的类型/函数。
 * 缺少某个符号时，编译报错会指出名字，按名字补即可。
 */
#ifndef SDB_H
#define SDB_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ---- 基础类型 ----
typedef struct ht_pp_t HtPP;
typedef struct ht_up_t HtUP;
typedef struct ht_uu_t HtUU;
typedef struct ht_pu_t HtPU;
typedef struct sdb_t Sdb;
typedef struct sdb_ns_t SdbNs;
typedef struct sdb_kv_t SdbKv;
typedef struct sdb_foreachentry_t SdbForeachEntry;
typedef unsigned int SdbHash;

// ---- 常用函数声明（按需扩充）----
// 注意：这里不用 r_types.h 的 ut32（sdb.h 比 r_types.h 先被包含），
// 用标准 uint32_t，避免循环依赖。
Sdb *sdb_new(const char *path, int lock);
Sdb *sdb_new0(void);
void sdb_free(Sdb *s);
bool sdb_set(Sdb *s, const char *k, const char *v, uint32_t cas);
char *sdb_get(Sdb *s, const char *k, uint32_t *cas);
bool sdb_unset(Sdb *s, const char *k, uint32_t cas);
bool sdb_sync(Sdb *s);

#ifdef __cplusplus
}
#endif

#endif
