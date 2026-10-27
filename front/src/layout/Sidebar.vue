<template>
  <div class="h-full flex flex-col studio-sidebar select-none">
    <!-- Logo & Title -->
    <router-link
      to="/dashboard"
      class="h-16 flex items-center px-4 overflow-hidden border-b border-[var(--sidebar-border)] shrink-0 gap-3 no-underline cursor-pointer group"
      :class="appStore.isCollapse ? 'justify-center !px-0' : 'justify-start'"
    >
      <div class="w-9 h-9 rounded-xl overflow-hidden flex items-center justify-center shrink-0 shadow-sm transition-transform duration-200 group-hover:scale-105">
        <img src="/logo/logo.png" alt="FREYJA Logo" class="w-full h-full object-contain" />
      </div>

      <div v-show="!appStore.isCollapse" class="flex flex-col overflow-hidden transition-opacity duration-200">
        <span class="font-black text-sm tracking-wider text-[var(--sidebar-active-text)] whitespace-nowrap">
          FREYJA
        </span>
        <span class="text-[9px] font-semibold tracking-widest text-[var(--sidebar-text-muted)] whitespace-nowrap">
          AI DIGITAL STUDIO
        </span>
      </div>
    </router-link>

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
        <el-sub-menu index="/assets">
          <template #title><el-icon><PictureFilled /></el-icon><span>资产中心</span></template>
          <el-menu-item index="/assets/characters">角色资产</el-menu-item>
          <el-menu-item index="/assets/scenes">场景资产</el-menu-item>
          <el-menu-item index="/assets/props">核心道具</el-menu-item>
          <el-menu-item index="/assets/keyframes">关键图资产</el-menu-item>
          <el-menu-item index="/assets/videos">视频素材</el-menu-item>
          <el-menu-item index="/assets/audios">音频素材</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="/dubbing">
          <template #title><el-icon><VideoPlay /></el-icon><span>配音中心</span></template>
          <el-menu-item index="/dubbing/characters">角色配音</el-menu-item>
          <el-menu-item index="/dubbing/effects">特效配置</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="/production">
          <template #title><el-icon><VideoCamera /></el-icon><span>视频中心</span></template>
          <el-menu-item index="/production/video-upscale">视频超分</el-menu-item>
          <el-menu-item index="/production/frame-interpolation">视频补帧</el-menu-item>
        </el-sub-menu>
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
        <el-menu-item index="/system/ai-capabilities">
          <el-icon><Setting /></el-icon>
          <template #title>AI 能力管理</template>
        </el-menu-item>
        <el-menu-item index="/system/mcp">
          <el-icon><Connection /></el-icon>
          <template #title>MCP 服务</template>
        </el-menu-item>
        <el-menu-item index="/system/skills">
          <el-icon><Collection /></el-icon>
          <template #title>AI Skills</template>
        </el-menu-item>
        <el-menu-item index="/system/agent">
          <el-icon><ChatDotRound /></el-icon>
          <template #title>Agent 配置</template>
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
          {{ currentThemeName }}
        </span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/store/app'
import { THEMES } from '@/theme'
import {
  Odometer,
  Film,
  PictureFilled,
  VideoPlay,
  VideoCamera,
  Tickets,
  Cpu,
  Operation,
  Collection,
  Brush,
  ChatDotRound,
  Setting,
  Connection
} from '@element-plus/icons-vue'

const route = useRoute()
const appStore = useAppStore()

const currentThemeName = computed(() => THEMES.find((theme) => theme.id === appStore.themeId)?.name ?? appStore.themeId)

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
:deep(.el-sub-menu__title) {
  height: 40px;
  margin: 2px 8px;
  border-radius: var(--radius-sm);
  color: var(--sidebar-text);
  font-weight: 500;
}
:deep(.el-sub-menu__title:hover) {
  background-color: var(--sidebar-hover-bg) !important;
  color: var(--sidebar-active-text);
}
:deep(.el-sub-menu__title .el-icon) {
  color: inherit;
  font-size: 17px;
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
