<template>
  <Teleport :to="target" :disabled="!isTeleportEnabled">
    <Transition name="page-slide">
      <div
        v-if="modelValue"
        :style="pageStyle"
        :class="[
          positionClass,
          'bg-[var(--app-bg)] flex flex-col overflow-hidden text-[var(--text-primary)] antialiased pointer-events-auto',
          containerClass
        ]"
      >
        <!-- 统一顶部导航顶栏 (Page Header) -->
        <header
          class="h-14 px-6 bg-[var(--surface)] border-b border-[var(--border-default)] flex items-center justify-between shadow-2xs shrink-0 z-20"
        >
          <!-- 左侧：返回按钮 + 分割线 + 面包屑与标题 -->
          <div class="flex items-center gap-4 min-w-0">
            <el-button
              v-if="showBack"
              type="default"
              class="!rounded-lg !px-3.5 !py-1.5 !h-8 !border-[var(--border-default)] !text-[var(--text-secondary)] hover:!text-[var(--brand)] hover:!border-[var(--brand)] transition-all flex items-center gap-1.5 font-medium cursor-pointer"
              @click="handleBack"
            >
              <el-icon><ArrowLeft /></el-icon>
              <span>{{ backText }}</span>
            </el-button>

            <div v-if="showBack" class="h-4 w-px bg-[var(--border-default)] shrink-0"></div>

            <div class="flex items-center gap-3 min-w-0 truncate">
              <div v-if="breadcrumb" class="text-xs text-[var(--text-muted)] shrink-0">
                {{ breadcrumb }}
                <span class="mx-1 text-[var(--text-muted)]">/</span>
              </div>
              <slot name="title">
                <h1 class="text-sm md:text-base font-bold text-[var(--text-primary)] tracking-tight truncate">
                  {{ title }}
                </h1>
              </slot>
              <slot name="badge" />
            </div>
          </div>

          <!-- 右侧：操作区 Slot (保存、取消、刷新等) -->
          <div class="flex items-center gap-2.5 shrink-0">
            <slot name="actions" />
          </div>
        </header>

        <!-- 页面主体内容区 (自适应平滑滚动) -->
        <main class="flex-1 overflow-y-auto" :class="contentClass">
          <div :class="[maxWidth, 'mx-auto w-full h-full']">
            <slot />
          </div>
        </main>

        <!-- 可选固定底栏 -->
        <footer
          v-if="$slots.footer"
          class="py-3 px-6 bg-[var(--surface)] border-t border-[var(--border-default)] flex items-center justify-end gap-3 shrink-0 z-10 shadow-xs"
        >
          <slot name="footer" />
        </footer>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowLeft } from '@element-plus/icons-vue'
import { useZIndex } from 'element-plus'

export type FullScreenPageScope = 'workspace' | 'viewport' | 'local'

interface Props {
  modelValue: boolean
  title?: string
  breadcrumb?: string
  scope?: FullScreenPageScope
  teleportTo?: string | boolean
  showBack?: boolean
  backText?: string
  containerClass?: string
  contentClass?: string
  maxWidth?: string
  beforeClose?: () => Promise<boolean> | boolean
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  breadcrumb: '',
  scope: 'workspace',
  teleportTo: undefined,
  showBack: true,
  backText: '返回',
  containerClass: '',
  contentClass: 'p-6',
  maxWidth: 'max-w-7xl',
  beforeClose: undefined
})

const emit = defineEmits<{
  (e: 'update:modelValue', val: boolean): void
  (e: 'back'): void
  (e: 'close'): void
}>()

// 接入 Element Plus 官方动态层级分配机制
const { nextZIndex } = useZIndex()
const pageZIndex = ref<number>()

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      pageZIndex.value = nextZIndex()
    }
  },
  { immediate: true }
)

const pageStyle = computed(() => {
  return pageZIndex.value ? { zIndex: pageZIndex.value } : undefined
})

// 解析有效作用域，并向下兼容原有 teleportTo 传参
const effectiveScope = computed<FullScreenPageScope>(() => {
  if (props.teleportTo !== undefined) {
    if (
      props.teleportTo === false ||
      props.teleportTo === 'disabled' ||
      props.teleportTo === 'none' ||
      props.teleportTo === 'self'
    ) {
      return 'local'
    }
    if (props.teleportTo === 'body') {
      return 'viewport'
    }
  }
  return props.scope || 'workspace'
})

const isTeleportEnabled = computed(() => {
  return effectiveScope.value !== 'local'
})

const target = computed(() => {
  if (!isTeleportEnabled.value) {
    return 'body'
  }
  // 若显式指定了自定义 DOM 选择器字符串
  if (typeof props.teleportTo === 'string' && props.teleportTo && props.teleportTo !== 'body') {
    return props.teleportTo
  }
  if (effectiveScope.value === 'viewport') {
    return 'body'
  }
  // workspace 作用域优先挂载到统一页面层容器 #workspace-page-layer
  if (typeof document !== 'undefined') {
    if (document.querySelector('#workspace-page-layer')) {
      return '#workspace-page-layer'
    }
    if (document.querySelector('#main-layout-container')) {
      return '#main-layout-container'
    }
    if (document.querySelector('#main-content-view')) {
      return '#main-content-view'
    }
  }
  return 'body'
})

const positionClass = computed(() => {
  if (effectiveScope.value === 'local') {
    return 'absolute inset-0'
  }
  if (effectiveScope.value === 'viewport' || target.value === 'body') {
    return 'fixed inset-0'
  }
  if (target.value === '#workspace-page-layer') {
    // #workspace-page-layer 自身已定位为 top-16 inset-x-0 bottom-0
    return 'absolute inset-0'
  }
  // 降级挂载到 #main-layout-container 时避开 top-16 顶栏
  return 'absolute top-16 bottom-0 left-0 right-0'
})

async function handleBack() {
  if (props.beforeClose) {
    const canClose = await props.beforeClose()
    if (!canClose) return
  }
  emit('back')
  emit('update:modelValue', false)
  emit('close')
}
</script>

<style scoped>
.page-slide-enter-active,
.page-slide-leave-active {
  transition: all 0.22s cubic-bezier(0.16, 1, 0.3, 1);
}

.page-slide-enter-from {
  opacity: 0;
  transform: translateX(20px);
}

.page-slide-leave-to {
  opacity: 0;
  transform: translateX(20px);
}
</style>
