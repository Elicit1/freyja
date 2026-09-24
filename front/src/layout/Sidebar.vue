<template>
  <div class="h-full flex flex-col studio-sidebar select-none">
    <!-- Logo & Title -->
    <div
      class="h-16 flex items-center px-4 overflow-hidden border-b border-[var(--sidebar-border)] shrink-0 gap-3"
      :class="appStore.isCollapse ? 'justify-center !px-0' : 'justify-start'"
    >
      <div class="w-9 h-9 rounded-xl bg-gradient-to-tr from-[var(--brand)] to-[var(--accent)] flex items-center justify-center text-white shrink-0 shadow-sm">
        <el-icon class="text-xl"><Film /></el-icon>
      </div>

      <div v-show="!appStore.isCollapse" class="flex flex-col overflow-hidden transition-opacity duration-200">
        <span class="font-black text-sm tracking-wider text-[var(--sidebar-active-text)] whitespace-nowrap">
          FREYJA
        </span>
        <span class="text-[9px] font-semibold tracking-widest text-[var(--sidebar-text-muted)] whitespace-nowrap">
          AI DIGITAL STUDIO
        </span>
      </div>
    </div>

    <!-- Grouped Menus -->
    <el-scrollbar class="flex-1 py-2">
      <el-menu
        :default-active="activeMenu"
        :collapse="appStore.isCollapse"
        :collapse-transition="false"
        class="border-r-0 !bg-transparent"
        router
      >
        <!-- 分组 1: 创作 -->
        <div v-if="!appStore.isCollapse" class="sidebar-group-title">
          创作
        </div>
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <template #title>工作台概览</template>
        </el-menu-item>
        <el-menu-item index="/drama">
          <el-icon><Film /></el-icon>
          <template #title>剧作与分镜</template>
        </el-menu-item>

        <!-- 分组 2: 制作 -->
        <div v-if="!appStore.isCollapse" class="sidebar-group-title mt-3">
          制作
        </div>
        <el-menu-item index="/assets">
          <el-icon><PictureFilled /></el-icon>
          <template #title>资产中心</template>
        </el-menu-item>
        <el-menu-item index="/production/video-upscale">
          <el-icon><ZoomIn /></el-icon>
          <template #title>视频超分</template>
        </el-menu-item>
        <el-menu-item index="/production/frame-interpolation">
          <el-icon><VideoPlay /></el-icon>
          <template #title>视频补帧</template>
        </el-menu-item>
        <el-menu-item index="/render/tasks">
          <el-icon><VideoCamera /></el-icon>
          <template #title>任务中心</template>
        </el-menu-item>

        <!-- 分组 3: 管理 -->
        <div v-if="!appStore.isCollapse" class="sidebar-group-title mt-3">
          管理
        </div>
        <el-menu-item index="/system/dict">
          <el-icon><Tickets /></el-icon>
          <template #title>数据字典</template>
        </el-menu-item>
        <el-menu-item index="/system/ai-provider">
          <el-icon><Cpu /></el-icon>
          <template #title>AI 提供商</template>
        </el-menu-item>
        <el-menu-item index="/system/config">
          <el-icon><Operation /></el-icon>
          <template #title>系统参数</template>
        </el-menu-item>
        <el-menu-item index="/system/skills">
          <el-icon><Collection /></el-icon>
          <template #title>AI Skills</template>
        </el-menu-item>
      </el-menu>
    </el-scrollbar>

    <!-- 底部：外观与主题入口 -->
    <div class="p-2 border-t border-[var(--sidebar-border)] shrink-0">
      <el-tooltip
        v-if="appStore.isCollapse"
        content="片场外观与设置"
        placement="right"
        :show-after="300"
      >
        <button
          class="w-full h-10 rounded-lg flex items-center justify-center text-[var(--sidebar-text)] hover:text-[var(--sidebar-active-text)] hover:bg-[var(--sidebar-hover-bg)] transition-colors"
          @click="appStore.openThemePanel"
        >
          <el-icon class="text-base"><Brush /></el-icon>
        </button>
      </el-tooltip>

      <button
        v-else
        class="w-full h-10 px-3 rounded-lg flex items-center justify-between text-xs text-[var(--sidebar-text)] hover:text-[var(--sidebar-active-text)] hover:bg-[var(--sidebar-hover-bg)] transition-colors"
        @click="appStore.openThemePanel"
      >
        <div class="flex items-center gap-2.5">
          <el-icon class="text-base text-[var(--brand)]"><Brush /></el-icon>
          <span class="font-medium">片场外观设置</span>
        </div>
        <span class="text-[10px] px-1.5 py-0.5 rounded bg-[var(--brand-soft)] text-[var(--brand)] font-semibold uppercase">
          {{ appStore.themeId }}
        </span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/store/app'
import {
  Odometer,
  Film,
  PictureFilled,
  ZoomIn,
  VideoPlay,
  VideoCamera,
  Tickets,
  Cpu,
  Operation,
  Collection,
  Brush
} from '@element-plus/icons-vue'

const route = useRoute()
const appStore = useAppStore()

const activeMenu = computed(() => {
  const { path } = route
  return path
})
</script>

<style scoped>
.sidebar-group-title {
  padding: 8px 16px 4px 16px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--sidebar-text-muted);
}

:deep(.el-menu) {
  border-right: none;
}
:deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  margin: 2px 8px;
  border-radius: var(--radius-sm);
  color: var(--sidebar-text);
  font-weight: 500;
}
:deep(.el-menu-item .el-icon) {
  font-size: 17px;
  color: inherit;
}
:deep(.el-menu-item:hover) {
  background-color: var(--sidebar-hover-bg) !important;
  color: var(--sidebar-active-text) !important;
}
:deep(.el-menu-item.is-active) {
  background-color: var(--sidebar-active-bg) !important;
  color: var(--sidebar-active-text) !important;
  font-weight: 600;
  position: relative;
}
:deep(.el-menu-item.is-active::before) {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 0 4px 4px 0;
  background-color: var(--sidebar-active-bar);
}
</style>
