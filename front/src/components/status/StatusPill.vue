<template>
  <span
    class="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-medium border transition-colors select-none"
    :class="[pillClasses, sizeClass]"
  >
    <!-- 指示微圆点或加载旋转器 -->
    <span
      v-if="normalizedStatus === 'processing'"
      class="w-1.5 h-1.5 rounded-full bg-current animate-ping opacity-75 shrink-0"
    ></span>
    <span
      v-else
      class="w-1.5 h-1.5 rounded-full shrink-0"
      :class="dotClass"
    ></span>

    <!-- 状态主文本 -->
    <span class="tracking-tight whitespace-nowrap">{{ label }}</span>

    <!-- 辅助补充文本 -->
    <span v-if="subLabel" class="opacity-70 text-[10px] font-mono">
      {{ subLabel }}
    </span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

export type StatusType = 'success' | 'processing' | 'pending' | 'warning' | 'failed' | 'disabled'

const props = withDefaults(
  defineProps<{
    status: StatusType | string
    label: string
    subLabel?: string
    size?: 'small' | 'default'
  }>(),
  {
    size: 'default'
  }
)

const normalizedStatus = computed<StatusType>(() => {
  const s = (props.status || '').toLowerCase()
  if (s.includes('success') || s.includes('done') || s.includes('complete') || s.includes('online')) return 'success'
  if (s.includes('process') || s.includes('rendering') || s.includes('generating') || s.includes('running')) return 'processing'
  if (s.includes('pend') || s.includes('queued') || s.includes('waiting') || s.includes('draft')) return 'pending'
  if (s.includes('warn') || s.includes('alert')) return 'warning'
  if (s.includes('fail') || s.includes('error') || s.includes('offline')) return 'failed'
  return 'disabled'
})

const sizeClass = computed(() => {
  return props.size === 'small' ? '!px-1.5 !py-0 !text-[10px]' : ''
})

const pillClasses = computed(() => {
  switch (normalizedStatus.value) {
    case 'success':
      return 'bg-emerald-50/80 text-emerald-700 border-emerald-200'
    case 'processing':
      return 'bg-[var(--brand-soft)] text-[var(--brand)] border-[var(--brand-soft)] font-semibold'
    case 'pending':
      return 'bg-amber-50/80 text-amber-700 border-amber-200'
    case 'warning':
      return 'bg-orange-50/80 text-orange-700 border-orange-200'
    case 'failed':
      return 'bg-rose-50/80 text-rose-700 border-rose-200'
    case 'disabled':
    default:
      return 'bg-[var(--surface-muted)] text-[var(--text-muted)] border-[var(--border-default)]'
  }
})

const dotClass = computed(() => {
  switch (normalizedStatus.value) {
    case 'success':
      return 'bg-emerald-500'
    case 'processing':
      return 'bg-[var(--brand)]'
    case 'pending':
      return 'bg-amber-500'
    case 'warning':
      return 'bg-orange-500'
    case 'failed':
      return 'bg-rose-500'
    case 'disabled':
    default:
      return 'bg-[var(--text-muted)]'
  }
})
</script>
