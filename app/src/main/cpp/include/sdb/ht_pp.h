/* sdb/ht_pp.h - 最小兼容层（指针→指针哈希表） */
#ifndef SDB_HT_PP_H
#define SDB_HT_PP_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ht_pp_t HtPP;

typedef unsigned int (*HtPPHashFunction)(const void *key);
typedef bool (*HtPPEqualityFunction)(const void *a, const void *b);

HtPP *ht_pp_new0(void);
HtPP *ht_pp_new(void *user, HtPPHashFunction hf, HtPPEqualityFunction eq);
void ht_pp_free(HtPP *ht);
void *ht_pp_find(HtPP *ht, const void *key, bool *found);
bool ht_pp_insert(HtPP *ht, const void *key, void *value);
bool ht_pp_update(HtPP *ht, const void *key, void *value);
bool ht_pp_delete(HtPP *ht, const void *key);
size_t ht_pp_count(HtPP *ht);

#ifdef __cplusplus
}
#endif

#endif
