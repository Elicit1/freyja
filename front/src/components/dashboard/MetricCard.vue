<template>
  <div class="studio-card p-4 transition-all duration-200 hover:-translate-y-0.5 cursor-default group">
    <div class="flex items-start justify-between">
      <div class="space-y-1.5 flex-1 min-w-0 pr-3">
        <!-- 标签标题 -->
        <div class="text-xs font-medium text-[var(--text-secondary)] tracking-wide flex items-center gap-1.5">
          <span>{{ title }}</span>
        </div>

        <!-- 核心数字 (第一视觉层级) -->
        <div class="flex items-baseline gap-1.5">
          <span class="text-2xl font-black text-[var(--text-primary)] tracking-tight font-mono">
            {{ value }}
          </span>
          <span v-if="unit" class="text-xs text-[var(--text-muted)] font-normal">
            {{ unit }}
          </span>
        </div>

        <!-- 辅助说明与子指标 -->
        <div v-if="subText || $slots.extra" class="text-[11px] text-[var(--text-muted)] truncate pt-0.5">
          <slot name="extra">
            <span>{{ subText }}</span>
          </slot>
        </div>
      </div>

      <!-- 统一线性图标容器 -->
      <div
        class="w-10 h-10 rounded-xl flex items-center justify-center shrink-0 transition-transform duration-200 group-hover:scale-105"
        :class="iconBgClass || 'bg-[var(--brand-soft)] text-[var(--brand)]'"
      >
        <component :is="icon" class="text-xl" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  title: string
  value: number | string
  unit?: string
  icon: Component
  iconBgClass?: string
  subText?: string
}>()
</script>
