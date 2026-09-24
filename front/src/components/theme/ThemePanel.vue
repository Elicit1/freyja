<template>
  <el-drawer
    v-model="visible"
    title="片场视觉与外观"
    size="360px"
    :with-header="true"
    destroy-on-close
    append-to-body
    class="theme-panel-drawer"
  >
    <div class="space-y-6 text-sm text-[var(--text-primary)]">
      <!-- 1. 主题选择 -->
      <div>
        <div class="flex items-center justify-between mb-3">
          <span class="font-bold text-sm tracking-wide">片场视觉风格</span>
          <span class="text-xs text-[var(--text-muted)]">即时生效无需刷新</span>
        </div>

        <div class="grid grid-cols-2 gap-3">
          <!-- 曜石片场 -->
          <div
            class="group cursor-pointer rounded-xl border-2 p-2.5 transition-all relative overflow-hidden"
            :class="isObsidian ? 'border-[var(--brand)] bg-[var(--brand-soft)] shadow-sm' : 'border-[var(--border-default)] hover:border-[var(--border-strong)] bg-[var(--surface-muted)]'"
            @click="changeTheme('obsidian')"
          >
            <!-- 视觉小卡片预览 -->
            <div class="h-16 rounded-lg overflow-hidden flex border border-black/10 shadow-xs mb-2">
              <div class="w-1/3 bg-[#11131A] flex flex-col justify-center items-center gap-1 p-1">
                <div class="w-3 h-1 bg-[#6D5DFB] rounded-full"></div>
                <div class="w-2.5 h-0.5 bg-white/20 rounded-full"></div>
                <div class="w-2.5 h-0.5 bg-white/20 rounded-full"></div>
              </div>
              <div class="w-2/3 bg-[#F3F4F7] p-1.5 flex flex-col justify-between">
                <div class="h-2 bg-white rounded shadow-xs w-3/4"></div>
                <div class="h-3 bg-white rounded shadow-xs w-full"></div>
              </div>
            </div>

            <div class="flex items-center justify-between">
              <div>
                <div class="font-bold text-xs">曜石片场</div>
                <div class="text-[10px] text-[var(--text-muted)]">深色导航 · 沉浸创作</div>
              </div>
              <el-icon v-if="isObsidian" class="text-[var(--brand)] text-base font-bold"><Check /></el-icon>
            </div>
          </div>

          <!-- 雾白工作室 -->
          <div
            class="group cursor-pointer rounded-xl border-2 p-2.5 transition-all relative overflow-hidden"
            :class="isStudio ? 'border-[var(--brand)] bg-[var(--brand-soft)] shadow-sm' : 'border-[var(--border-default)] hover:border-[var(--border-strong)] bg-[var(--surface-muted)]'"
            @click="changeTheme('studio')"
          >
            <!-- 视觉小卡片预览 -->
            <div class="h-16 rounded-lg overflow-hidden flex border border-black/10 shadow-xs mb-2">
              <div class="w-1/3 bg-[#F8F9FC] border-r border-[#E7EAF0] flex flex-col justify-center items-center gap-1 p-1">
                <div class="w-3 h-1 bg-[#4461F2] rounded-full"></div>
                <div class="w-2.5 h-0.5 bg-black/10 rounded-full"></div>
                <div class="w-2.5 h-0.5 bg-black/10 rounded-full"></div>
              </div>
              <div class="w-2/3 bg-[#F5F7FA] p-1.5 flex flex-col justify-between">
                <div class="h-2 bg-white rounded border border-[#E7EAF0] w-3/4"></div>
                <div class="h-3 bg-white rounded border border-[#E7EAF0] w-full"></div>
              </div>
            </div>

            <div class="flex items-center justify-between">
              <div>
                <div class="font-bold text-xs">雾白工作室</div>
                <div class="text-[10px] text-[var(--text-muted)]">轻量明亮 · 清爽通透</div>
              </div>
              <el-icon v-if="isStudio" class="text-[var(--brand)] text-base font-bold"><Check /></el-icon>
            </div>
          </div>
        </div>
      </div>

      <el-divider class="!my-4 !border-[var(--border-default)]" />

      <!-- 2. 显示模式 (明亮 / 暗黑 / 跟随系统) -->
      <div>
        <div class="flex items-center justify-between mb-3">
          <span class="font-bold text-sm tracking-wide">显示模式 (Light / Dark)</span>
          <span class="text-xs text-[var(--text-muted)]">支持日间/暗房片场</span>
        </div>

        <el-radio-group
          :model-value="colorMode"
          @update:model-value="onColorModeChange"
          class="w-full !flex"
        >
          <el-radio-button value="light" class="flex-1 !text-center">
            <span class="text-xs flex items-center justify-center gap-1">
              <el-icon><Sunny /></el-icon> 明亮
            </span>
          </el-radio-button>
          <el-radio-button value="dark" class="flex-1 !text-center">
            <span class="text-xs flex items-center justify-center gap-1">
              <el-icon><Moon /></el-icon> 暗黑
            </span>
          </el-radio-button>
          <el-radio-button value="system" class="flex-1 !text-center">
            <span class="text-xs flex items-center justify-center gap-1">
              <el-icon><Monitor /></el-icon> 系统
            </span>
          </el-radio-button>
        </el-radio-group>
      </div>

      <el-divider class="!my-4 !border-[var(--border-default)]" />

      <!-- 3. 排版密度 -->
      <div>
        <div class="flex items-center justify-between mb-3">
          <span class="font-bold text-sm tracking-wide">排版显示密度</span>
          <span class="text-xs text-[var(--text-muted)]">微调控件间距与字体</span>
        </div>

        <el-radio-group
          :model-value="density"
          @update:model-value="onDensityChange"
          class="w-full !flex"
        >
          <el-radio-button value="comfortable" class="flex-1 !text-center">
            <span class="text-xs">舒适 (推荐)</span>
          </el-radio-button>
          <el-radio-button value="compact" class="flex-1 !text-center">
            <span class="text-xs">紧凑模式</span>
          </el-radio-button>
        </el-radio-group>
      </div>

      <el-divider class="!my-4 !border-[var(--border-default)]" />

      <!-- 4. 无障碍与动效 -->
      <div>
        <div class="font-bold text-sm tracking-wide mb-3">辅助与动效选项</div>
        <div class="flex items-center justify-between p-3 rounded-xl bg-[var(--surface-muted)] border border-[var(--border-default)]">
          <div>
            <div class="text-xs font-semibold">减少非必要动画</div>
            <div class="text-[11px] text-[var(--text-muted)] mt-0.5">针对对晕动敏感或追求极速响应的用户</div>
          </div>
          <el-switch
            :model-value="reduceMotion"
            @update:model-value="onReduceMotionChange"
          />
        </div>
      </div>

      <!-- 5. 底部重置与提示 -->
      <div class="pt-4 flex items-center justify-between">
        <el-button link size="small" class="!text-[var(--text-secondary)] hover:!text-[var(--danger)]" @click="resetToDefault">
          <el-icon class="mr-1"><RefreshLeft /></el-icon> 恢复默认外观
        </el-button>
        <el-button type="primary" size="small" @click="visible = false">
          完成
        </el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Check, RefreshLeft, Sunny, Moon, Monitor } from '@element-plus/icons-vue'
import { useAppStore } from '@/store/app'
import { useAppearance } from '@/composables/useAppearance'
import type { ColorMode } from '@/theme/types'

const appStore = useAppStore()

const visible = computed({
  get: () => appStore.themePanelVisible,
  set: (val: boolean) => {
    appStore.themePanelVisible = val
  }
})

const {
  colorMode,
  density,
  reduceMotion,
  isObsidian,
  isStudio,
  changeTheme,
  changeColorMode,
  changeDensity,
  toggleReduceMotion,
  resetToDefault
} = useAppearance()

function onColorModeChange(val: any) {
  if (val === 'light' || val === 'dark' || val === 'system') {
    changeColorMode(val as ColorMode)
  }
}

function onDensityChange(val: string | number | boolean | undefined) {
  if (val === 'compact' || val === 'comfortable') {
    changeDensity(val)
  }
}

function onReduceMotionChange(val: string | number | boolean) {
  toggleReduceMotion(Boolean(val))
}
</script>

<style scoped>
:deep(.theme-panel-drawer .el-drawer__body) {
  padding: 16px 20px;
  background-color: var(--surface);
}
:deep(.theme-panel-drawer .el-drawer__header) {
  margin-bottom: 0;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-default);
  font-weight: bold;
}
</style>
