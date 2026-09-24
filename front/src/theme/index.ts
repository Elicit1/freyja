import './tokens.css'
import './themes.css'
import './element-plus.css'
import './components.css'

export * from './types'

export const THEMES = [
  {
    id: 'obsidian',
    name: '曜石片场',
    subtitle: '沉浸式深色侧边栏 + 明亮工作区',
    sidebarBg: '#11131A',
    surfaceBg: '#FFFFFF',
    brandColor: '#6D5DFB'
  },
  {
    id: 'studio',
    name: '雾白工作室',
    subtitle: '纯净清爽轻量设计，适合长时间创作',
    sidebarBg: '#F8F9FC',
    surfaceBg: '#FFFFFF',
    brandColor: '#4461F2'
  }
] as const
