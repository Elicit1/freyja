<template>
  <div class="flex flex-col items-center justify-center p-12 text-center rounded-2xl border border-dashed border-[var(--border-default)] bg-[var(--surface-muted)]/50 select-none">
    <!-- 图标容器 -->
    <div class="w-14 h-14 rounded-2xl bg-[var(--surface)] border border-[var(--border-default)] flex items-center justify-center text-2xl mb-3.5 shadow-xs text-[var(--text-secondary)]">
      <component :is="resolvedIcon" />
    </div>

    <!-- 标题与引导文案 -->
    <h3 class="text-sm font-bold text-[var(--text-primary)] mb-1">
      {{ resolvedTitle }}
    </h3>
    <p class="text-xs text-[var(--text-secondary)] max-w-sm mb-5 leading-relaxed">
      {{ resolvedDescription }}
    </p>

    <!-- 下一步明确操作 -->
    <div class="flex items-center gap-3">
      <slot name="action">
        <el-button
          v-if="actionText"
          type="primary"
          size="default"
          class="!shadow-xs"
          @click="$emit('action')"
        >
          {{ actionText }}
        </el-button>
      </slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, type Component } from 'vue'
import { FolderOpened, Search, Warning, Setting } from '@element-plus/icons-vue'

const props = withDefaults(
  defineProps<{
    type?: 'empty' | 'search' | 'error' | 'config'
    title?: string
    description?: string
    actionText?: string
    icon?: Component
  }>(),
  {
    type: 'empty'
  }
)

defineEmits<{
  (e: 'action'): void
}>()

const resolvedIcon = computed<Component>(() => {
  if (props.icon) return props.icon
  switch (props.type) {
    case 'search':
      return Search
    case 'error':
      return Warning
    case 'config':
      return Setting
    case 'empty':
    default:
      return FolderOpened
  }
})

const resolvedTitle = computed(() => {
  if (props.title) return props.title
  switch (props.type) {
    case 'search':
      return '未找到匹配结果'
    case 'error':
      return '数据加载失败'
    case 'config':
      return '相关配置尚未就绪'
    case 'empty':
    default:
      return '暂无数据记录'
  }
})

const resolvedDescription = computed(() => {
  if (props.description) return props.description
  switch (props.type) {
    case 'search':
      return '请尝试调整搜索关键字或筛选条件后再试。'
    case 'error':
      return '网络请求或后端服务响应异常，请点击重试。'
    case 'config':
      return '请前往系统设置完成相关 AI 提供商或网关配置。'
    case 'empty':
    default:
      return '当前工作区尚未创建任何内容，点击下方按钮开始创建。'
  }
})
</script>
