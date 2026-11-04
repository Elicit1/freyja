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
      <el-dropdown trigger="click" @command="accountCommand">
        <button class="flex items-center gap-2 rounded-lg px-2 py-1.5 text-[var(--text-secondary)] hover:bg-[var(--surface-hover)]" aria-label="账户与安全">
          <el-icon><User /></el-icon><span class="hidden sm:inline text-xs">{{ username }}</span><el-icon class="!text-xs"><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="password" :icon="Lock">修改密码</el-dropdown-item>
            <el-dropdown-item command="logout" :icon="SwitchButton" :disabled="loggingOut" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

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
    <ChangePasswordDialog v-model="passwordDialogVisible" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/store/app'
import { Fold, Expand, Brush, User, ArrowDown, Lock, SwitchButton } from '@element-plus/icons-vue'
import ThemePanel from '@/components/theme/ThemePanel.vue'
import TaskCenterFloat from '@/components/task/TaskCenterFloat.vue'
import { authApi } from '@/api/auth'
import { clearLoginSession, getLoginSession } from '@/utils/auth-session'
import ChangePasswordDialog from '@/components/auth/ChangePasswordDialog.vue'

const route = useRoute()
const appStore = useAppStore()
const username = getLoginSession()?.username
const loggingOut = ref(false)
const passwordDialogVisible = ref(false)

function accountCommand(command: string) {
  if (command === 'password') passwordDialogVisible.value = true
  if (command === 'logout') void logout()
}

async function logout() {
  loggingOut.value = true
  try {
    await authApi.logout()
    clearLoginSession()
    window.location.replace('/login')
  } catch {
    // Retain the session when logout could not reach the server, so it can be retried.
  } finally { loggingOut.value = false }
}

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
