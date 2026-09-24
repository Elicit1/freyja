<template>
  <el-container class="h-screen w-screen overflow-hidden bg-[var(--app-bg)] text-[var(--text-primary)]">
    <!-- Sidebar -->
    <el-aside :width="asideWidth" class="transition-all duration-200 ease-out z-20 overflow-hidden shrink-0">
      <Sidebar />
    </el-aside>

    <!-- Main Container -->
    <el-container id="main-layout-container" class="flex-col min-w-0 relative overflow-hidden bg-[var(--app-bg)]">
      <!-- Header -->
      <el-header class="!p-0 !h-16 z-20">
        <Header />
      </el-header>

      <!-- Main Content View -->
      <el-main class="!p-6 overflow-y-auto bg-[var(--app-bg)] flex-1">
        <router-view v-slot="{ Component }">
          <transition name="fade-transform" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>

      <!-- Workspace Page Layer (固定页面层容器，供 FullScreenPage 等业务详情页统一挂载) -->
      <div
        id="workspace-page-layer"
        class="absolute top-16 inset-x-0 bottom-0 pointer-events-none overflow-hidden"
      />
    </el-container>

  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '@/store/app'
import Sidebar from './Sidebar.vue'
import Header from './Header.vue'

const appStore = useAppStore()

const asideWidth = computed(() => {
  return appStore.isCollapse ? '64px' : '248px'
})
</script>

<style scoped>
.fade-transform-enter-active,
.fade-transform-leave-active {
  transition: all 0.18s ease;
}

.fade-transform-enter-from {
  opacity: 0;
  transform: translateY(4px);
}

.fade-transform-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
