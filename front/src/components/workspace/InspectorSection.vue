<template>
  <div class="studio-card overflow-hidden transition-all duration-200">
    <!-- Header 行 -->
    <div
      class="px-4 py-3 flex items-center justify-between gap-3 bg-[var(--surface-muted)] border-b border-[var(--border-default)] select-none"
      :class="collapsible ? 'cursor-pointer hover:bg-[var(--surface-hover)]' : ''"
      @click="toggleCollapse"
    >
      <div class="flex items-center gap-2.5 min-w-0">
        <el-icon v-if="collapsible" class="text-xs text-[var(--text-muted)] transition-transform duration-200" :class="isCollapsed ? '-rotate-90' : ''">
          <ArrowDown />
        </el-icon>
        <div v-if="icon" class="w-6 h-6 rounded-lg bg-[var(--brand-soft)] text-[var(--brand)] flex items-center justify-center text-xs shrink-0">
          <component :is="icon" />
        </div>
        <div class="min-w-0">
          <div class="flex items-center gap-2">
            <h4 class="font-bold text-xs text-[var(--text-primary)] tracking-wide truncate">
              {{ title }}
            </h4>
            <span v-if="badge" class="studio-badge bg-[var(--surface)] text-[var(--text-secondary)] border border-[var(--border-default)] !py-0 !px-1.5 !text-[10px]">
              {{ badge }}
            </span>
          </div>
          <p v-if="description" class="text-[11px] text-[var(--text-muted)] truncate mt-0.5">
            {{ description }}
          </p>
        </div>
      </div>

      <!-- 右侧操作区 -->
      <div class="flex items-center gap-2 shrink-0" @click.stop>
        <slot name="actions" />
      </div>
    </div>

    <!-- 内容区 -->
    <div v-show="!isCollapsed" class="p-4 bg-[var(--surface)]">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, type Component } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'

const props = withDefaults(
  defineProps<{
    title: string
    description?: string
    icon?: Component
    badge?: string
    collapsible?: boolean
    defaultCollapsed?: boolean
  }>(),
  {
    collapsible: false,
    defaultCollapsed: false
  }
)

const isCollapsed = ref(props.defaultCollapsed)

function toggleCollapse() {
  if (props.collapsible) {
    isCollapsed.value = !isCollapsed.value
  }
}
</script>
