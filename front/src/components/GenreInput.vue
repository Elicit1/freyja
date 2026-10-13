<template>
  <div class="genre-input">
    <el-autocomplete
      v-model="displayValue"
      :fetch-suggestions="fetchSuggestions"
      :placeholder="placeholder"
      :maxlength="64"
      :size="size"
      clearable
      class="w-full"
      @select="handleSelect"
      @change="handleChange"
      @clear="publish('')"
    />
    <div v-if="showSuggestions && suggestions.length" class="mt-1.5 flex flex-wrap items-center gap-1.5">
      <span class="text-[11px] text-[var(--text-muted)]">建议：</span>
      <el-button
        v-for="item in suggestions"
        :key="item.dictValue"
        size="small"
        :type="modelValue === item.dictValue ? 'primary' : 'default'"
        plain
        class="!ml-0"
        @click="publish(item.dictValue)"
      >{{ item.dictLabel }}</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { AutocompleteFetchSuggestions } from 'element-plus'
import { useDictStore } from '@/store/dict'
import type { SysDictData } from '@/types/dict'

const props = withDefaults(defineProps<{
  modelValue?: string
  placeholder?: string
  showSuggestions?: boolean
  size?: 'large' | 'default' | 'small'
}>(), { placeholder: '输入题材，或选择建议', showSuggestions: true, size: 'default' })
const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'change', value: string): void
}>()
const dictStore = useDictStore()
const suggestions = ref<SysDictData[]>([])

// Existing dictionary codes keep their labels; free text remains free text.
const displayValue = computed({
  get: () => suggestions.value.find(item => item.dictValue === props.modelValue)?.dictLabel ?? props.modelValue ?? '',
  set: (value: string) => emit('update:modelValue', value)
})
const fetchSuggestions: AutocompleteFetchSuggestions = (query, callback) => {
  const keyword = query.trim().toLowerCase()
  callback(suggestions.value.filter(item => !keyword || item.dictLabel.toLowerCase().includes(keyword) || item.dictValue.toLowerCase().includes(keyword))
    .map(item => ({ value: item.dictLabel, genre: item.dictValue })))
}
function publish(value: string) {
  const genre = value.trim()
  emit('update:modelValue', genre)
  emit('change', genre)
}
function handleSelect(item: Record<string, any>) {
  if (typeof item.genre === 'string') publish(item.genre)
}
function handleChange(value: string) {
  const current = suggestions.value.find(item => item.dictValue === props.modelValue)
  // Blurring an untouched legacy value must not rewrite its code into the display label.
  publish(current?.dictLabel === value ? current.dictValue : value)
}
onMounted(async () => {
  suggestions.value = (await dictStore.getDict('drama_genre')).filter(item => item.status !== 0)
})
</script>
