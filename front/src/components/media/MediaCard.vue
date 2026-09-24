<template>
  <div
    class="studio-card overflow-hidden flex flex-col group transition-all duration-200 cursor-pointer"
    :class="[
      selected ? 'border-[var(--brand)] ring-1 ring-[var(--brand)]' : 'hover:border-[var(--border-strong)]',
      disabled ? 'opacity-50 pointer-events-none' : ''
    ]"
    @click="$emit('click')"
  >
    <!-- 媒体画框容器 -->
    <div
      class="relative overflow-hidden bg-[var(--surface-muted)] flex items-center justify-center select-none"
      :class="aspectRatioClass"
    >
      <!-- 实际图片或视频 -->
      <img
        v-if="imageUrl"
        :src="imageUrl"
        :alt="title || 'media'"
        class="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
        loading="lazy"
      />
      <!-- 缺图专业占位 -->
      <div v-else class="flex flex-col items-center justify-center text-[var(--text-muted)] gap-1.5 p-4 text-center">
        <component :is="placeholderIcon || Picture" class="text-3xl opacity-40" />
        <span class="text-[11px] font-mono opacity-60">{{ placeholderText || '暂无预览' }}</span>
      </div>

      <!-- 顶部浮层：状态药丸或标签插槽 -->
      <div class="absolute top-2.5 left-2.5 right-2.5 flex items-center justify-between pointer-events-none">
        <div>
          <slot name="top-left" />
        </div>
        <div>
          <slot name="top-right" />
        </div>
      </div>

      <!-- 底部遮罩渐变信息（例如画幅、运镜、时长） -->
      <div v-if="$slots['media-overlay']" class="absolute bottom-0 inset-x-0 p-2 bg-gradient-to-t from-black/70 via-black/30 to-transparent flex items-center justify-between text-white text-[11px] pointer-events-none">
        <slot name="media-overlay" />
      </div>

      <!-- 悬停快捷操作遮罩 (非强制) -->
      <div
        v-if="$slots['hover-actions']"
        class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center gap-2"
        @click.stop
      >
        <slot name="hover-actions" />
      </div>
    </div>

    <!-- 卡片主体内容 -->
    <div class="p-3.5 flex-1 flex flex-col justify-between gap-2.5">
      <div>
        <div class="flex items-start justify-between gap-1.5 mb-1">
          <slot name="title">
            <h4 class="font-bold text-sm text-[var(--text-primary)] truncate group-hover:text-[var(--brand)] transition-colors">
              {{ title }}
            </h4>
          </slot>
          <slot name="title-extra" />
        </div>

        <p v-if="description" class="text-xs text-[var(--text-secondary)] line-clamp-2 leading-relaxed">
          {{ description }}
        </p>

        <slot name="body" />
      </div>

      <!-- 底部信息与高频操作栏 -->
      <div v-if="$slots.footer || $slots.actions" class="pt-2 border-t border-[var(--border-default)] flex items-center justify-between gap-2 text-xs">
        <div class="flex-1 min-w-0 text-[var(--text-muted)] truncate">
          <slot name="footer" />
        </div>
        <div class="shrink-0 flex items-center gap-1.5" @click.stop>
          <slot name="actions" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, type Component } from 'vue'
import { Picture } from '@element-plus/icons-vue'

const props = withDefaults(
  defineProps<{
    imageUrl?: string
    title?: string
    description?: string
    aspectRatio?: '16:9' | '3:4' | '1:1' | '9:16'
    placeholderIcon?: Component
    placeholderText?: string
    selected?: boolean
    disabled?: boolean
  }>(),
  {
    aspectRatio: '16:9',
    selected: false,
    disabled: false
  }
)

defineEmits<{
  (e: 'click'): void
}>()

const aspectRatioClass = computed(() => {
  switch (props.aspectRatio) {
    case '16:9':
      return 'aspect-video'
    case '3:4':
      return 'aspect-[3/4]'
    case '9:16':
      return 'aspect-[9/16]'
    case '1:1':
    default:
      return 'aspect-square'
  }
})
</script>
