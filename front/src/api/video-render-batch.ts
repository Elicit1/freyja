import { request } from './request'

export type VideoMode = 'REFERENCE_MODE' | 'FIRST_LAST_FRAME'
export interface BatchModeConfig { providerId?: string; modelId?: string; autoUsePreviousVideoTailAsFirstFrame?: boolean; autoUseNextVideoHeadAsLastFrame?: boolean }
export interface VideoBatchRequest {
  clientRequestId?: string; name?: string; dramaId: string; shotIds: string[]; expectedPreviewHash?: string
  existingVideoPolicy: 'SKIP' | 'CREATE_NEW_TAKE'; aspectRatio: string
  size?: string
  modeConfigs: Partial<Record<VideoMode, BatchModeConfig>>
  retryOfBatchId?: string; inputSource?: 'ORIGINAL_SNAPSHOT' | 'CURRENT_SHOT'
}
export interface BatchSelectionNode { key: string; id: string; label: string; type: string; children: BatchSelectionNode[] }
export interface BatchCandidate { shotId: string; name: string; context: string; generationMode: VideoMode; status: string; reason?: string; firstFrameSource?: string; lastFrameSource?: string; size?: string; executionOrder: number }
export interface BatchPreview { shotIds: string[]; items: BatchCandidate[]; previewHash: string; accepted: number; blocked: number; skipped: number; duration: number }
export interface VideoBatchItem { id: string; batchId?: string; shotId: string; taskId: string; name: string; phase: string; status: string; errorMessage?: string; outputUrl?: string; upstreamStatus?: string; archiveRecoverable?: boolean; failurePhase?: string }
export interface VideoBatch {
  id: string; dramaId: string; name: string; status: string; cancelRequested: boolean; options: VideoBatchRequest
  total: number; processed: number; succeeded: number; failed: number; cancelled: number; skipped: number; progress: number
  refTotal: number; refProcessed: number; flTotal: number; flProcessed: number; currentPhase: string; currentShot?: string; upstreamStatus?: string; items: VideoBatchItem[]
}
const base = '/drama/video-render-batches'
export const videoBatchApi = {
  tree: (dramaId: string) => request<BatchSelectionNode[]>({ url: `${base}/selection-tree`, method: 'get', params: { dramaId } }),
  preview: (data: VideoBatchRequest) => request<BatchPreview>({ url: `${base}/preview`, method: 'post', data }),
  create: (data: VideoBatchRequest) => request<VideoBatch>({ url: base, method: 'post', data }),
  detail: (id: string) => request<VideoBatch>({ url: `${base}/${id}`, method: 'get' }),
  cancel: (id: string) => request<{ message: string; upstreamStatus: string }>({ url: `${base}/${id}/cancel`, method: 'post' }),
  reconcile: (id: string, taskId: string, evidence: string) => request<void>({ url: `${base}/${id}/reconcile`, method: 'post', data: { taskId, evidence } }),
  retryArchive: (id: string, taskId: string) => request<void>({ url: `${base}/${id}/retry-archive`, method: 'post', data: { taskId } }),
  recoverResult: (id: string, taskId: string) => request<{ status: string; message: string }>({ url: `${base}/${id}/recover-result`, method: 'post', data: { taskId } })
}
