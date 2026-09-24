import { request } from './request'
import { runAiTaskResult } from './ai-task-stream'
import type { PageResult } from '@/types/api'
import type {
  Drama,
  DramaQuery,
  DramaOption,
  DramaStats,
  DramaTree,
  DramaEpisode,
  DramaEpisodeBatch,
  DramaScene,
  DramaShot,
  DramaShotReorder,
  DramaShotBatchAssemble,
  DramaShotFirstFrame,
  DramaShotRenderRequest,
  ShotPromptDeriveDTO,
  ShotPromptPackageVO,
  ShotPromptTask,
  ShotPromptParseRequestDTO,
  ShotPromptValidationResult,
  PreviousVideoTailResult,
  ShotVideoTake,
  ShotVideoTakeSelectResult
} from '@/types/drama'
import type { ShotAiVisualPlan } from '@/types/drama'
import type { PromptAssembleResult } from '@/types/resource'
import type { ComfyRenderTaskVO } from '@/types/comfy'

// ==========================================
// 1. 短剧管理 API
// ==========================================

export const dramaApi = {
  getPage(params: DramaQuery) {
    return request<PageResult<Drama>>({
      url: '/drama/page',
      method: 'get',
      params
    })
  },

  getById(id: string | number) {
    return request<Drama>({
      url: `/drama/${id}`,
      method: 'get'
    })
  },

  create(data: Partial<Drama>) {
    return request<number>({
      url: '/drama',
      method: 'post',
      data
    })
  },

  update(data: Partial<Drama>) {
    return request<void>({
      url: '/drama',
      method: 'put',
      data
    })
  },

  delete(id: string | number) {
    return request<void>({
      url: `/drama/${id}`,
      method: 'delete'
    })
  },

  getOptions() {
    return request<DramaOption[]>({
      url: '/drama/options',
      method: 'get'
    })
  },

  getTree(id: string | number) {
    return request<DramaTree>({
      url: `/drama/tree/${id}`,
      method: 'get'
    })
  },

  getStats(id: string | number) {
    return request<DramaStats>({
      url: `/drama/stats/${id}`,
      method: 'get'
    })
  }
}

// ==========================================
// 2. 剧集管理 API
// ==========================================

export const episodeApi = {
  getListByDramaId(dramaId: string | number) {
    return request<DramaEpisode[]>({
      url: `/drama/episode/list/${dramaId}`,
      method: 'get'
    })
  },

  getById(id: string | number) {
    return request<DramaEpisode>({
      url: `/drama/episode/${id}`,
      method: 'get'
    })
  },

  create(data: Partial<DramaEpisode>) {
    return request<number>({
      url: '/drama/episode',
      method: 'post',
      data
    })
  },

  batchCreate(data: DramaEpisodeBatch) {
    return request<number[]>({
      url: '/drama/episode/batch',
      method: 'post',
      data
    })
  },

  update(data: Partial<DramaEpisode>) {
    return request<void>({
      url: '/drama/episode',
      method: 'put',
      data
    })
  },

  delete(id: string | number) {
    return request<void>({
      url: `/drama/episode/${id}`,
      method: 'delete'
    })
  }
}

// ==========================================
// 3. 情景场次管理 API
// ==========================================

export const dramaSceneApi = {
  getListByEpisodeId(episodeId: string | number) {
    return request<DramaScene[]>({
      url: `/drama/scene/list/${episodeId}`,
      method: 'get'
    })
  },

  getById(id: string | number) {
    return request<DramaScene>({
      url: `/drama/scene/${id}`,
      method: 'get'
    })
  },

  create(data: Partial<DramaScene>) {
    return request<number>({
      url: '/drama/scene',
      method: 'post',
      data
    })
  },

  update(data: Partial<DramaScene>) {
    return request<void>({
      url: '/drama/scene',
      method: 'put',
      data
    })
  },

  delete(id: string | number) {
    return request<void>({
      url: `/drama/scene/${id}`,
      method: 'delete'
    })
  }
}

// ==========================================
// 4. 分镜镜头管理 API
// ==========================================

export const shotApi = {
  startPromptTask(data: ShotPromptDeriveDTO) {
    return request<string>({ url: '/drama/shot/derive-prompt-task', method: 'post', data })
  },
  getListBySceneId(sceneId: string | number) {
    return request<DramaShot[]>({
      url: `/drama/shot/list/${sceneId}`,
      method: 'get'
    })
  },

  getListByEpisodeId(episodeId: string | number) {
    return request<DramaShot[]>({
      url: '/drama/shot/list-by-episode',
      method: 'get',
      params: { episodeId }
    })
  },

  getById(id: string | number) {
    return request<DramaShot>({
      url: `/drama/shot/${id}`,
      method: 'get'
    })
  },

  create(data: Partial<DramaShot>) {
    return request<string | number>({
      url: '/drama/shot',
      method: 'post',
      data
    })
  },

  update(data: Partial<DramaShot>) {
    return request<void>({
      url: '/drama/shot',
      method: 'put',
      data
    })
  },

  delete(id: string | number) {
    return request<void>({
      url: `/drama/shot/${id}`,
      method: 'delete'
    })
  },

  clone(id: string | number) {
    return request<string | number>({
      url: `/drama/shot/${id}/clone`,
      method: 'post'
    })
  },

  reorder(data: DramaShotReorder) {
    return request<void>({
      url: '/drama/shot/reorder',
      method: 'put',
      data
    })
  },

  previewPrompt(id: string | number) {
    return request<PromptAssembleResult>({
      url: `/drama/shot/${id}/preview-prompt`,
      method: 'get'
    })
  },

  assemblePrompt(id: string | number) {
    return request<PromptAssembleResult>({
      url: `/drama/shot/${id}/assemble-prompt`,
      method: 'post'
    })
  },

  batchAssemblePrompts(data: DramaShotBatchAssemble) {
    return request<number>({
      url: '/drama/shot/batch-assemble',
      method: 'post',
      data
    })
  },

  generateFirstFrame(id: string | number, data?: DramaShotFirstFrame) {
    return runAiTaskResult<ComfyRenderTaskVO>(`/ai/tasks/shot/${String(id)}/frame`, data)
  },

  generateAiVisualPlan(id: string | number, data?: { providerId?: number | string; modelCode?: string; instruction?: string }) {
    return runAiTaskResult<ShotAiVisualPlan>(`/ai/tasks/shot/${String(id)}/visual-plan`, data)
  },

  applyAiVisualPlan(id: string | number, data: ShotAiVisualPlan) {
    return request<ShotAiVisualPlan>({
      url: `/drama/shot/${id}/ai-visual-plan/apply`,
      method: 'post',
      data: {
        firstFramePrompt: data.firstFramePrompt,
        negativePrompt: data.negativePrompt,
        videoPrompt: data.videoPrompt
      }
    })
  },


  derivePromptPackage(data: ShotPromptDeriveDTO) {
    return request<ShotPromptPackageVO>({
      url: '/drama/shot/derive-prompt-package',
      method: 'post',
      data
    })
  },

  getPromptTask(taskId: string | number) {
    return request<ShotPromptTask | null>({
      url: `/drama/shot/prompt-task/${String(taskId)}`,
      method: 'get'
    })
  },

  getLatestPromptTask(shotId: string | number) {
    return request<ShotPromptTask | null>({
      url: `/drama/shot/${String(shotId)}/prompt-task/latest`,
      method: 'get'
    })
  },

  parseDerivedPrompt(data: ShotPromptParseRequestDTO) {
    return request<ShotPromptValidationResult>({
      url: '/drama/shot/parse-derived-prompt',
      method: 'post',
      data
    })
  },

  setFirstFrame(id: number | string, previewImageUrl: string) {
    return request<void>({
      url: `/drama/shot/${id}/set-first-frame`,
      method: 'put',
      data: { previewImageUrl }
    })
  },

  inheritPreviousVideoTail(
    currentShotId: string | number,
    data?: { forceExtract?: boolean; tailOffsetMs?: number }
  ) {
    return request<PreviousVideoTailResult>({
      url: `/drama/shot/${currentShotId}/inherit-previous-video-tail`,
      method: 'post',
      data
    })
  },

  setEndFrame(id: number | string, endFrameImageUrl: string) {
    return request<void>({
      url: `/drama/shot/${id}/set-end-frame`,
      method: 'put',
      data: { endFrameImageUrl }
    })
  },

  submitRender(id: string | number, data?: DramaShotRenderRequest) {
    return request<ComfyRenderTaskVO>({
      url: `/drama/shot/${id}/render`,
      method: 'post',
      data: data || {}
    })
  },

  getVideoTakes(shotId: string | number, params?: { current?: number; size?: number; status?: string }) {
    return request<PageResult<ShotVideoTake>>({
      url: `/drama/shot/${shotId}/video-takes`,
      method: 'get',
      params
    })
  },

  selectVideoTake(shotId: string | number, takeId: string | number) {
    return request<ShotVideoTakeSelectResult>({
      url: `/drama/shot/${shotId}/video-takes/${takeId}/select`,
      method: 'post'
    })
  },
  deleteVideoTake(shotId: string | number, takeId: string | number) {
    return request<void>({
      url: `/drama/shot/${String(shotId)}/video-takes/${String(takeId)}`,
      method: 'delete'
    })
  }
}

// ==========================================
// 5. 连续镜头组管理 API
// ==========================================

export const shotGroupApi = {
  getListBySceneId(sceneId: string | number) {
    return request<import('@/types/drama').DramaShotGroup[]>({
      url: `/drama/shot-group/list/${sceneId}`,
      method: 'get'
    })
  },

  getListByEpisodeId(episodeId: string | number) {
    return request<import('@/types/drama').DramaShotGroup[]>({
      url: '/drama/shot-group/list-by-episode',
      method: 'get',
      params: { episodeId }
    })
  },

  getById(id: string | number) {
    return request<import('@/types/drama').DramaShotGroup>({
      url: `/drama/shot-group/${id}`,
      method: 'get'
    })
  },

  create(data: Partial<import('@/types/drama').DramaShotGroup>) {
    return request<string | number>({
      url: '/drama/shot-group',
      method: 'post',
      data
    })
  },

  update(data: Partial<import('@/types/drama').DramaShotGroup>) {
    return request<void>({
      url: '/drama/shot-group',
      method: 'put',
      data
    })
  },

  delete(id: string | number) {
    return request<void>({
      url: `/drama/shot-group/${id}`,
      method: 'delete'
    })
  },

  split(data: import('@/types/drama').DramaShotGroupSplit) {
    return request<string | number>({
      url: '/drama/shot-group/split',
      method: 'post',
      data
    })
  },

  merge(data: import('@/types/drama').DramaShotGroupMerge) {
    return request<string | number>({
      url: '/drama/shot-group/merge',
      method: 'post',
      data
    })
  },

  reorderShots(groupId: string | number, shotIds: (string | number)[]) {
    return request<void>({
      url: `/drama/shot-group/${groupId}/reorder-shots`,
      method: 'put',
      data: shotIds
    })
  },

  reorderGroups(data: import('@/types/drama').DramaShotGroupReorder) {
    return request<void>({
      url: '/drama/shot-group/reorder-groups',
      method: 'put',
      data
    })
  }
}
