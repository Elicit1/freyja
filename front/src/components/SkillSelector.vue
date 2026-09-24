<template>
  <div class="space-y-1.5">
    <label class="block text-[11px] font-semibold text-slate-700">
      🧩 {{ mode === 'API' ? '本次必用 Skill（API 预加载）' : '本次使用 Skill（展开到复制 Prompt）' }}
    </label>
    <el-select
      v-model="selectedNames"
      multiple
      filterable
      clearable
      collapse-tags
      collapse-tags-tooltip
      fit-input-width
      class="w-full"
      size="small"
      :disabled="disabled"
      :loading="loading"
      :placeholder="mode === 'API' ? '可选：指定需要预加载的 Skill' : '选择要展开到外部 AI Prompt 的 Skill'"
    >
      <el-option
        v-for="skill in skills"
        :key="skill.name"
        :label="skill.displayName ? `${skill.displayName} (${skill.name})` : skill.name"
        :value="skill.name"
      >
        <div class="flex min-w-0 w-full items-center justify-between gap-2">
          <span class="min-w-0 flex-1 truncate">{{ skill.displayName || skill.name }}</span>
          <span class="text-[10px] text-slate-400">v{{ skill.currentVersion || '?' }}</span>
        </div>
        <div v-if="skill.description" class="max-w-full truncate text-[10px] text-slate-400">
          {{ skill.description }}
        </div>
      </el-option>
    </el-select>
    <p class="text-[10px] text-slate-400">
      {{ mode === 'API'
        ? (dynamicEnabled
          ? '未选择的 Skill 只提供目录信息，由 AI 在任务中按需调用 load_skill。'
          : '只有选中的 Skill 会在模型调用前加载。')
        : '系统只展开你选中的 Skill，不按 Skill 数量做限制。' }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { skillApi } from '@/api/skill'
import type { AiSkill } from '@/types/skill'

const props = withDefaults(defineProps<{
  modelValue?: string[]
  mode?: 'API' | 'MANUAL'
  disabled?: boolean
  dynamicEnabled?: boolean
}>(), {
  modelValue: () => [],
  mode: 'MANUAL',
  disabled: false,
  dynamicEnabled: true
})

const emit = defineEmits<{
  (event: 'update:modelValue', value: string[]): void
}>()

const skills = ref<AiSkill[]>([])
const loading = ref(false)
const selectedNames = computed({
  get: () => props.modelValue || [],
  set: (value: string[]) => emit('update:modelValue', value || [])
})

onMounted(async () => {
  loading.value = true
  try {
    const list = await skillApi.list()
    skills.value = (list || []).filter(skill => skill.enabled === 1 && !!skill.currentVersionId)
  } finally {
    loading.value = false
  }
})
</script>
