<template>
  <el-tag :type="tagType" :size="size">
    {{ currentLabel }}
  </el-tag>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, watch } from 'vue'
import { useDictStore } from '@/store/dict'
import type { SysDictData } from '@/types/dict'
import type { TagProps } from 'element-plus'

interface Props {
  dictType: string
  value?: string | number | boolean
  size?: 'large' | 'default' | 'small'
}

const props = withDefaults(defineProps<Props>(), {
  size: 'default'
})

const dictStore = useDictStore()
const options = ref<SysDictData[]>([])

const loadData = async () => {
  if (props.dictType) {
    options.value = await dictStore.getDict(props.dictType)
  }
}

onMounted(() => {
  loadData()
})

watch(
  () => props.dictType,
  () => {
    loadData()
  }
)

const currentItem = computed(() => {
  if (props.value === undefined || props.value === null) return null
  const strVal = String(props.value)
  return options.value.find((item) => item.dictValue === strVal)
})

const currentLabel = computed(() => {
  if (currentItem.value) {
    return currentItem.value.dictLabel
  }
  return props.value !== undefined && props.value !== null ? String(props.value) : '-'
})

const tagType = computed<TagProps['type']>(() => {
  const strVal = String(props.value)
  if (strVal === '1' || strVal === 'true' || strVal === 'success') return 'success'
  if (strVal === '0' || strVal === 'false' || strVal === 'danger') return 'danger'
  if (strVal === 'warning') return 'warning'
  if (strVal === 'info') return 'info'
  return 'primary'
})
</script>
