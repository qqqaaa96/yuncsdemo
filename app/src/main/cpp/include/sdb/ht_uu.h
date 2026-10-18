/* sdb/ht_uu.h - 最小兼容层（u64→u64 哈希表） */
#ifndef SDB_HT_UU_H
#define SDB_HT_UU_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ht_uu_t HtUU;

HtUU *ht_uu_new0(void);
void ht_uu_free(HtUU *ht);
uint64_t ht_uu_find(HtUU *ht, uint64_t key, bool *found);
bool ht_uu_insert(HtUU *ht, uint64_t key, uint64_t value);
bool ht_uu_update(HtUU *ht, uint64_t key, uint64_t value);
bool ht_uu_delete(HtUU *ht, uint64_t key);
size_t ht_uu_count(HtUU *ht);

#ifdef __cplusplus
}
#endif

#endif
