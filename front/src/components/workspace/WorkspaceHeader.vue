<template>
  <div class="bg-[var(--surface)] border-b border-[var(--border-default)] px-6 py-3.5 flex flex-wrap items-center justify-between gap-4 transition-colors select-none">
    <!-- 左侧：标题、图标、上下文说明与标签 -->
    <div class="flex items-center gap-3.5 min-w-0 flex-wrap">
      <slot name="left-prefix" />

      <div v-if="icon" class="w-9 h-9 rounded-xl bg-[var(--brand-soft)] text-[var(--brand)] flex items-center justify-center text-lg shrink-0">
        <component :is="icon" />
      </div>

      <div class="min-w-0">
        <div class="flex items-center gap-2 flex-wrap">
          <slot name="title">
            <h2 class="text-base font-bold text-[var(--text-primary)] tracking-tight truncate">
              {{ title }}
            </h2>
          </slot>
          <span v-if="tag" class="studio-badge bg-[var(--surface-muted)] text-[var(--text-secondary)] border border-[var(--border-default)]">
            {{ tag }}
          </span>
          <slot name="tag" />
        </div>

        <p v-if="subtitle" class="text-xs text-[var(--text-secondary)] mt-0.5 truncate max-w-xl">
          {{ subtitle }}
        </p>
      </div>

      <slot name="context" />
    </div>

    <!-- 右侧：统计指标与操作按钮区 -->
    <div class="flex items-center gap-3 shrink-0 flex-wrap">
      <slot name="stats" />
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  title?: string
  subtitle?: string
  icon?: Component
  tag?: string
}>()
</script>
