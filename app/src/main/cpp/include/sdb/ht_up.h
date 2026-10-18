/* sdb/ht_up.h - 最小兼容层
 *
 * 原文件属于 sdb 子项目（radare2 的 git submodule），
 * 下载的源码 zip 里不含。
 *
 * 这里只提供 r2 头文件需要的**声明**：
 *   · HtUP 类型（仅作指针使用）
 *   · 常用函数原型
 *
 * sdb_* / ht_* 的实现已编入 libr_util.so，只需声明即可链接。
 */
#ifndef SDB_HT_UP_H
#define SDB_HT_UP_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// 不透明类型：r2 头文件只用 HtUP* 指针
typedef struct ht_up_t HtUP;

// 基本接口（签名与 sdb 一致）
HtUP *ht_up_new0(void);
HtUP *ht_up_new(void *user, void *fn);
void ht_up_free(HtUP *ht);
void *ht_up_find(HtUP *ht, uint64_t key, bool *found);
bool ht_up_insert(HtUP *ht, uint64_t key, void *data);
bool ht_up_update(HtUP *ht, uint64_t key, void *data);
bool ht_up_delete(HtUP *ht, uint64_t key);
size_t ht_up_count(HtUP *ht);

#ifdef __cplusplus
}
#endif

#endif
