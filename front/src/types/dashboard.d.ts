export interface RecentDramaItem {
  id: number
  title: string
  genre?: string
  coverUrl?: string
  status?: string
  episodeCount: number
  shotCount: number
  updateTime?: string
}

export interface DashboardStats {
  dramaCount: number
  episodeCount: number
  sceneCount: number
  shotCount: number

  characterCount: number
  sceneAssetCount: number
  propCount: number
  totalAssetCount: number

  renderedVideoCount: number
  renderedImageCount: number

  aiProviderCount: number
  aiTaskCount: number
  aiServiceReady: boolean
  storageReady: boolean

  genreDistribution?: Record<string, number>
  recentDramas: RecentDramaItem[]
}
