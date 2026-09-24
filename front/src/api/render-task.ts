import { request } from './request'

export interface RenderTaskVO {
  id?: number
  taskId: string
  taskType: 'SHOT_FRAME' | 'SHOT_VIDEO' | 'ASSET_IMAGE' | 'GROUP_SERIAL' // GROUP_SERIAL is legacy history only
  taskName: string
  dramaId?: number
  dramaTitle?: string
  episodeId?: number
  episodeName?: string
  sceneId?: number
  shotId?: number
  shotNo?: number
  shotGroupId?: number
  assetType?: string
  assetId?: number
  assetName?: string
  assetSlot?: string
  providerId?: number
  providerName?: string
  modelCode?: string
  status: 'QUEUED' | 'RENDERING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
  progress: number
  currentNode?: string
  prompt?: string
  negativePrompt?: string
  referenceImages?: string
  outputUrl?: string
  lastFrameUrl?: string
  errorMessage?: string
  errorDetail?: string
  submitTime?: string
  startTime?: string
  finishTime?: string
  costMs?: number
}

export interface RenderTaskQuery {
  current?: number
  size?: number
  dramaId?: number
  episodeId?: number
  taskType?: string
  status?: string
  keyword?: string
  startTime?: string
  endTime?: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

export const renderTaskApi = {
  // 获取当前实时活跃任务列表 (从 Redis 缓存)
  getActiveTasks() {
    return request<RenderTaskVO[]>({
      url: '/render/tasks/active',
      method: 'get'
    })
  },

  // 获取当前活跃任务总数 (Redis)
  getActiveCount() {
    return request<number>({
      url: '/render/tasks/active/count',
      method: 'get'
    })
  },

  // 根据任务 ID 查询详情
  getTaskById(taskId: string) {
    return request<RenderTaskVO>({
      url: `/render/tasks/${taskId}`,
      method: 'get'
    })
  },

  // 分页查询历史归档记录 (走 MySQL)
  getHistoryTasks(params: RenderTaskQuery) {
    return request<PageResult<RenderTaskVO>>({
      url: '/render/tasks/history',
      method: 'get',
      params
    })
  },

  // 主动取消任务
  cancelTask(taskId: string) {
    return request<boolean>({
      url: `/render/tasks/${taskId}/cancel`,
      method: 'post'
    })
  }
}
