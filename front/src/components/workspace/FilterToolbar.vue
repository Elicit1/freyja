<template>
  <div class="p-4 bg-[var(--surface)] border-b border-[var(--border-default)] flex flex-wrap items-center justify-between gap-3 select-none">
    <!-- 左侧：搜索框与过滤项 -->
    <div class="flex flex-wrap items-center gap-2.5 flex-1 min-w-[280px]">
      <el-input
        v-if="showSearch"
        :model-value="modelValue"
        :placeholder="searchPlaceholder || '输入关键词搜索...'"
        clearable
        class="!w-56"
        @update:model-value="handleSearchInput"
        @keyup.enter="$emit('search')"
        @clear="$emit('search')"
      >
        <template #prefix>
          <el-icon class="text-[var(--text-muted)]"><Search /></el-icon>
        </template>
      </el-input>

      <!-- 业务自定过滤条件插槽 -->
      <slot name="filters" />

      <!-- 重置按钮 -->
      <el-button
        v-if="showReset"
        size="default"
        class="!border-[var(--border-default)] !text-[var(--text-secondary)] hover:!text-[var(--text-primary)]"
        @click="$emit('reset')"
      >
        重置
      </el-button>
    </div>

    <!-- 右侧：网格/列表切换 + 主次操作 -->
    <div class="flex items-center gap-2.5 shrink-0">
      <!-- 视图切换 (Grid / List) -->
      <el-radio-group
        v-if="showViewMode"
        :model-value="viewMode"
        size="small"
        @update:model-value="handleViewModeChange"
      >
        <el-radio-button value="grid">
          <el-icon class="mr-1"><Grid /></el-icon>网格
        </el-radio-button>
        <el-radio-button value="list">
          <el-icon class="mr-1"><Tickets /></el-icon>列表
        </el-radio-button>
      </el-radio-group>

      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { Search, Grid, Tickets } from '@element-plus/icons-vue'

withDefaults(
  defineProps<{
    modelValue?: string
    searchPlaceholder?: string
    showSearch?: boolean
    showReset?: boolean
    showViewMode?: boolean
    viewMode?: 'grid' | 'list'
  }>(),
  {
    showSearch: true,
    showReset: true,
    showViewMode: false,
    viewMode: 'grid'
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', val: string): void
  (e: 'update:viewMode', val: 'grid' | 'list'): void
  (e: 'search'): void
  (e: 'reset'): void
}>()

function handleSearchInput(val: string) {
  emit('update:modelValue', val)
}

function handleViewModeChange(val: any) {
  emit('update:viewMode', val as 'grid' | 'list')
}
</script>
