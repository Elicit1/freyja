import { defineStore } from 'pinia'
import { ref } from 'vue'
import { dictCommonApi } from '@/api/dict'
import type { SysDictData } from '@/types/dict'

export const useDictStore = defineStore('dict', () => {
  // 缓存字典数据 { [dictType]: SysDictData[] }
  const dictMap = ref<Record<string, SysDictData[]>>({})
  // 防止重复并发请求同一个 dictType
  const pendingRequests = new Map<string, Promise<SysDictData[]>>()

  /**
   * 获取指定类型的字典数据
   * @param dictType 字典类型编码
   * @param forceRefresh 是否强制刷新
   */
  const getDict = async (dictType: string, forceRefresh = false): Promise<SysDictData[]> => {
    if (!dictType) return []

    // 若缓存存在且不强制刷新，直接返回
    if (!forceRefresh && dictMap.value[dictType]) {
      return dictMap.value[dictType]
    }

    // 若当前正在请求中，复用 Promise
    if (pendingRequests.has(dictType)) {
      return pendingRequests.get(dictType)!
    }

    const requestPromise = (async () => {
      try {
        const data = await dictCommonApi.getDataByType(dictType)
        dictMap.value[dictType] = data || []
        return dictMap.value[dictType]
      } catch (error) {
        console.error(`Failed to load dict for type: ${dictType}`, error)
        dictMap.value[dictType] = []
        return []
      } finally {
        pendingRequests.delete(dictType)
      }
    })()

    pendingRequests.set(dictType, requestPromise)
    return requestPromise
  }

  /**
   * 清除指定类型的字典缓存
   */
  const removeDict = (dictType: string) => {
    delete dictMap.value[dictType]
    pendingRequests.delete(dictType)
  }

  /**
   * 清空所有字典缓存
   */
  const clearAllDict = () => {
    dictMap.value = {}
    pendingRequests.clear()
  }

  return {
    dictMap,
    getDict,
    removeDict,
    clearAllDict
  }
})
