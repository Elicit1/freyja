import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import type { ThemeId, ColorMode, Density, AppearanceState } from '@/theme/types'

const STORAGE_KEY = 'freyja:appearance:v1'

const DEFAULT_STATE: AppearanceState = {
  themeId: 'obsidian',
  colorMode: 'light',
  density: 'comfortable',
  isCollapse: false,
  reduceMotion: false
}

function loadInitialState(): AppearanceState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      const validMode: ColorMode = ['light', 'dark', 'system'].includes(parsed.colorMode) ? parsed.colorMode : 'light'
      return {
        themeId: parsed.themeId === 'studio' ? 'studio' : 'obsidian',
        colorMode: validMode,
        density: parsed.density === 'compact' ? 'compact' : 'comfortable',
        isCollapse: Boolean(parsed.isCollapse),
        reduceMotion: Boolean(parsed.reduceMotion)
      }
    }
  } catch (e) {
    console.warn('Failed to parse stored appearance state:', e)
  }
  return { ...DEFAULT_STATE }
}

export const useAppStore = defineStore('app', () => {
  const initial = loadInitialState()

  const themeId = ref<ThemeId>(initial.themeId)
  const colorMode = ref<ColorMode>(initial.colorMode)
  const density = ref<Density>(initial.density)
  const isCollapse = ref<boolean>(initial.isCollapse)
  const reduceMotion = ref<boolean>(initial.reduceMotion)
  const themePanelVisible = ref<boolean>(false)

  // 系统暗色侦测
  const isSystemDark = ref(
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia('(prefers-color-scheme: dark)').matches
      : false
  )

  if (typeof window !== 'undefined' && window.matchMedia) {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    mq.addEventListener('change', (e) => {
      isSystemDark.value = e.matches
      if (colorMode.value === 'system') {
        applyDOM()
      }
    })
  }

  const effectiveColorMode = computed<'light' | 'dark'>(() => {
    if (colorMode.value === 'system') {
      return isSystemDark.value ? 'dark' : 'light'
    }
    return colorMode.value
  })

  const openThemePanel = () => {
    themePanelVisible.value = true
  }

  const closeThemePanel = () => {
    themePanelVisible.value = false
  }

  // 立即同步 HTML 根标签属性
  const applyDOM = () => {
    if (typeof document !== 'undefined') {
      const eff = effectiveColorMode.value
      document.documentElement.setAttribute('data-theme', themeId.value)
      document.documentElement.setAttribute('data-color-mode', eff)
      document.documentElement.setAttribute('data-density', density.value)
      document.documentElement.setAttribute('data-reduce-motion', String(reduceMotion.value))
      if (eff === 'dark') {
        document.documentElement.classList.add('dark')
      } else {
        document.documentElement.classList.remove('dark')
      }
    }
  }

  // 持久化
  const persist = () => {
    try {
      const state: AppearanceState = {
        themeId: themeId.value,
        colorMode: colorMode.value,
        density: density.value,
        isCollapse: isCollapse.value,
        reduceMotion: reduceMotion.value
      }
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
    } catch (e) {
      console.warn('Failed to persist appearance state:', e)
    }
  }

  const setTheme = (theme: ThemeId) => {
    themeId.value = theme
    applyDOM()
    persist()
  }

  const setColorMode = (mode: ColorMode) => {
    colorMode.value = mode
    applyDOM()
    persist()
  }

  const setDensity = (val: Density) => {
    density.value = val
    applyDOM()
    persist()
  }

  const setReduceMotion = (val: boolean) => {
    reduceMotion.value = val
    applyDOM()
    persist()
  }

  const toggleSidebar = () => {
    isCollapse.value = !isCollapse.value
    persist()
  }

  const setCollapse = (value: boolean) => {
    isCollapse.value = value
    persist()
  }

  const resetToDefault = () => {
    themeId.value = DEFAULT_STATE.themeId
    colorMode.value = DEFAULT_STATE.colorMode
    density.value = DEFAULT_STATE.density
    reduceMotion.value = DEFAULT_STATE.reduceMotion
    applyDOM()
    persist()
  }

  // 监听变动保证 DOM 同步
  watch([themeId, colorMode, density, reduceMotion, isSystemDark], () => {
    applyDOM()
  }, { immediate: true })

  return {
    themeId,
    colorMode,
    effectiveColorMode,
    density,
    isCollapse,
    reduceMotion,
    themePanelVisible,
    openThemePanel,
    closeThemePanel,
    setTheme,
    setColorMode,
    setDensity,
    setReduceMotion,
    toggleSidebar,
    setCollapse,
    resetToDefault
  }
})
