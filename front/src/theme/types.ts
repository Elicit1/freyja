export type ThemeId = 'obsidian' | 'studio'

export type ColorMode = 'light' | 'dark' | 'system'

export type Density = 'comfortable' | 'compact'

export interface AppearanceState {
  themeId: ThemeId
  colorMode: ColorMode
  density: Density
  isCollapse: boolean
  reduceMotion: boolean
}
