/* sdb/set.h - 最小兼容层（集合）
 *
 * 原文件属于 sdb 子项目，源码 zip 不含。
 * 实现已编入 libr_util.so，这里只声明。
 */
#ifndef SDB_SET_H
#define SDB_SET_H

#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct sdb_set_t SdbSet;

typedef unsigned int (*SdbSetHashFunction)(const void *key);
typedef bool (*SdbSetEqualityFunction)(const void *a, const void *b);

typedef struct sdb_set_t {
	SdbSetHashFunction hashfn;
	SdbSetEqualityFunction cmpfn;
	void *hashtable;
	int count;
	bool ownkeys;
} SdbSet;

SdbSet *sdb_set_new0(void);
SdbSet *sdb_set_new(SdbSetHashFunction hashfn, SdbSetEqualityFunction cmpfn);
void sdb_set_free(SdbSet *set);
bool sdb_set_add(SdbSet *set, void *key);
bool sdb_set_remove(SdbSet *set, void *key);
bool sdb_set_contains(SdbSet *set, void *key);

#ifdef __cplusplus
}
#endif

#endif
