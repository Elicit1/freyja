<template>
  <div class="h-16 px-4 flex items-center justify-between studio-glass border-b border-[var(--header-border)] transition-colors select-none">
    <!-- Left: 折叠按钮 + 页面主标题与面包屑 -->
    <div class="flex items-center gap-3.5">
      <button
        @click="appStore.toggleSidebar"
        class="w-8 h-8 rounded-lg flex items-center justify-center text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--surface-hover)] transition-colors focus:outline-none"
        :title="appStore.isCollapse ? '展开侧边栏' : '收起侧边栏'"
      >
        <el-icon class="text-lg"><Fold v-if="!appStore.isCollapse" /><Expand v-else /></el-icon>
      </button>

      <!-- 页面主标题 + 辅助面包屑 -->
      <div class="flex items-baseline gap-2.5">
        <h2 class="text-base font-bold text-[var(--text-primary)] tracking-tight">
          {{ currentTitle }}
        </h2>

        <el-breadcrumb separator="/" class="hidden md:flex opacity-60 text-xs">
          <el-breadcrumb-item :to="{ path: '/' }">片场</el-breadcrumb-item>
          <el-breadcrumb-item v-for="item in breadcrumbs" :key="item.path">
            {{ item.meta?.title }}
          </el-breadcrumb-item>
        </el-breadcrumb>
      </div>
    </div>

    <!-- Right: 任务中心与外观入口 -->
    <div class="flex items-center gap-2.5">
      <TaskCenterFloat />

      <!-- 外观设置按钮 -->
      <el-tooltip content="片场视觉与主题" placement="bottom" :show-after="300">
        <button
          @click="appStore.openThemePanel"
          class="w-8 h-8 rounded-lg flex items-center justify-center text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--surface-hover)] border border-transparent hover:border-[var(--border-default)] transition-all"
        >
          <el-icon class="text-base"><Brush /></el-icon>
        </button>
      </el-tooltip>

    </div>

    <ThemePanel />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/store/app'
import { Fold, Expand, Brush } from '@element-plus/icons-vue'
import ThemePanel from '@/components/theme/ThemePanel.vue'
import TaskCenterFloat from '@/components/task/TaskCenterFloat.vue'

const route = useRoute()
const appStore = useAppStore()

const currentTitle = computed(() => {
  return (route.meta?.title as string) || '工作台'
})

const breadcrumbs = computed(() => {
  return route.matched.filter((item) => item.meta && item.meta.title && item.path !== '/')
})

</script>

<style scoped>
:deep(.el-breadcrumb__inner) {
  color: var(--text-muted) !important;
  font-weight: 400;
}
:deep(.el-breadcrumb__inner.is-link:hover) {
  color: var(--brand) !important;
}
</style>
