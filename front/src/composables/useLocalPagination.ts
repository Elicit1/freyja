import { computed, ref, watch, type ComputedRef } from 'vue'

/** Paginate a complete editable catalog without dropping off-page state. */
export function useLocalPagination<T>(items: ComputedRef<readonly T[]>, initialSize = 20) {
  const page = ref(1), pageSize = ref(initialSize)
  const total = computed(() => items.value.length)
  const rows = computed(() => items.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
  function reset() { page.value = 1 }
  watch(pageSize, reset)
  watch(total, value => { page.value = Math.min(page.value, Math.max(1, Math.ceil(value / pageSize.value))) })
  return { page, pageSize, total, rows, reset }
}
