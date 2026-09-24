<template>
  <el-select
    :model-value="modelValue"
    :placeholder="placeholder"
    :clearable="clearable"
    :disabled="disabled"
    :multiple="multiple"
    :filterable="filterable"
    :size="size"
    :loading="loading"
    @update:model-value="handleUpdateValue"
    @change="handleChange"
  >
    <el-option
      v-for="item in options"
      :key="item.dictValue"
      :label="item.dictLabel"
      :value="convertValue(item.dictValue)"
      :disabled="item.status === 0"
    />
  </el-select>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { useDictStore } from '@/store/dict'
import type { SysDictData } from '@/types/dict'

interface Props {
  modelValue?: string | number | boolean | (string | number)[]
  dictType: string
  placeholder?: string
  clearable?: boolean
  disabled?: boolean
  multiple?: boolean
  filterable?: boolean
  valueType?: 'string' | 'number'
  size?: 'large' | 'default' | 'small'
}

const props = withDefaults(defineProps<Props>(), {
  placeholder: '请选择',
  clearable: true,
  disabled: false,
  multiple: false,
  filterable: false,
  valueType: 'string',
  size: 'default'
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: any): void
  (e: 'change', value: any): void
}>()

const dictStore = useDictStore()
const options = ref<SysDictData[]>([])
const loading = ref(false)

const convertValue = (val: string) => {
  if (props.valueType === 'number') {
    // 严禁对长数字/雪花ID（>=15位）转换导致精度丢失
    if (val.length >= 15) {
      return val
    }
    const num = Number(val)
    return isNaN(num) ? val : num
  }
  return val
}

const loadData = async () => {
  if (!props.dictType) {
    options.value = []
    return
  }
  loading.value = true
  try {
    options.value = await dictStore.getDict(props.dictType)
  } finally {
    loading.value = false
  }
}

watch(
  () => props.dictType,
  () => {
    loadData()
  }
)

onMounted(() => {
  loadData()
})

const handleUpdateValue = (val: any) => {
  emit('update:modelValue', val)
}

const handleChange = (val: any) => {
  emit('change', val)
}
</script>
