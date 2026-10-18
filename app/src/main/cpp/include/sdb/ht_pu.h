/* sdb/ht_pu.h - 最小兼容层（指针→u64 哈希表） */
#ifndef SDB_HT_PU_H
#define SDB_HT_PU_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ht_pu_t HtPU;

HtPU *ht_pu_new0(void);
void ht_pu_free(HtPU *ht);
uint64_t ht_pu_find(HtPU *ht, const void *key, bool *found);
bool ht_pu_insert(HtPU *ht, const void *key, uint64_t value);
bool ht_pu_update(HtPU *ht, const void *key, uint64_t value);
bool ht_pu_delete(HtPU *ht, const void *key);
size_t ht_pu_count(HtPU *ht);

#ifdef __cplusplus
}
#endif

#endif
