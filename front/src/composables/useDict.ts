import { toRefs, reactive, onMounted } from 'vue'
import { useDictStore } from '@/store/dict'
import type { SysDictData } from '@/types/dict'

/**
 * 字典快速获取 Hook (支持多个 dictType 批量并行加载与响应式解构)
 *
 * @example
 * const { common_status, video_ratio } = useDict('common_status', 'video_ratio')
 * // 在模板中可直接使用 common_status.value
 */
export function useDict(...dictTypes: string[]) {
  const dictStore = useDictStore()
  const res = reactive<Record<string, SysDictData[]>>({})

  dictTypes.forEach((type) => {
    res[type] = []
  })

  onMounted(async () => {
    await Promise.all(
      dictTypes.map(async (type) => {
        res[type] = await dictStore.getDict(type)
      })
    )
  })

  return toRefs(res)
}
