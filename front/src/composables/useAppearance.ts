import { computed } from 'vue'
import { useAppStore } from '@/store/app'
import { THEMES } from '@/theme'
import type { ThemeId, ColorMode, Density } from '@/theme/types'

export function useAppearance() {
  const appStore = useAppStore()

  const currentTheme = computed(() => {
    return THEMES.find((t) => t.id === appStore.themeId) || THEMES[0]
  })

  const isObsidian = computed(() => appStore.themeId === 'obsidian')
  const isStudio = computed(() => appStore.themeId === 'studio')

  const changeTheme = (id: ThemeId) => {
    appStore.setTheme(id)
  }

  const changeColorMode = (mode: ColorMode) => {
    appStore.setColorMode(mode)
  }

  const changeDensity = (density: Density) => {
    appStore.setDensity(density)
  }

  const toggleReduceMotion = (val: boolean) => {
    appStore.setReduceMotion(val)
  }

  return {
    themeId: computed(() => appStore.themeId),
    colorMode: computed(() => appStore.colorMode),
    effectiveColorMode: computed(() => appStore.effectiveColorMode),
    density: computed(() => appStore.density),
    reduceMotion: computed(() => appStore.reduceMotion),
    currentTheme,
    isObsidian,
    isStudio,
    themes: THEMES,
    changeTheme,
    changeColorMode,
    changeDensity,
    toggleReduceMotion,
    resetToDefault: appStore.resetToDefault
  }
}
