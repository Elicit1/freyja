import { request } from './request'

export interface UpscaleTilingOptions { enabled: boolean; tileWidth: number; tileHeight: number; overlap: number; temporal: boolean; windowFrames: number; overlapFrames: number }
export interface WorkflowOptions { upscale: boolean; faceRepair: boolean; scale: 2; upscaleDenoise: number; faceDenoise: number; upscaleTiling: UpscaleTilingOptions; upscaleEngine: 'PIXEL' | 'FREYJA'; upscaleMethod: 'LATENT_BICUBIC' | 'H3_LEARNED'; upscaleLatentModel?: string; upscaleOutput: 'DOUBLE' | 'FULL_HD'; upscaleSteps: number; upscaleMemory: 'FULL' | 'TEMPORAL' | 'SPATIAL' }
export interface WorkflowSelection { options?: WorkflowOptions; reusePrevious?: boolean; reuseFromTaskId?: string }
export interface WorkflowCapability { modelCode: string; nodeId: string; supported: boolean; legacyFixedFace: boolean; revision?: string; stages: string[]; maxTargetPixels: number; upscaleTiling?: { supported: boolean; node: string; apiVersion: string }; freyjaUpscale?: { supported: boolean; version: number; maxSteps: number; nativeLatentInput?: boolean } }
export const defaultUpscaleTiling = (): UpscaleTilingOptions => ({ enabled: false, tileWidth: 768, tileHeight: 512, overlap: 192, temporal: false, windowFrames: 56, overlapFrames: 22 })
export const defaultWorkflowOptions = (): WorkflowOptions => ({ upscale: false, faceRepair: false, scale: 2, upscaleDenoise: .25, faceDenoise: .4, upscaleTiling: defaultUpscaleTiling(), upscaleEngine: 'FREYJA', upscaleMethod: 'LATENT_BICUBIC', upscaleOutput: 'FULL_HD', upscaleSteps: 2, upscaleMemory: 'TEMPORAL' })
export const normalizeWorkflowOptions = (options: Partial<WorkflowOptions>): WorkflowOptions => ({ ...defaultWorkflowOptions(), ...options, upscaleEngine: options.upscaleEngine || 'PIXEL', upscaleOutput: options.upscaleOutput || 'DOUBLE', upscaleTiling: { ...defaultUpscaleTiling(), ...options.upscaleTiling } })
type CapabilityCatalog = { models: WorkflowCapability[] }
export interface ContinuationEnhancementSummary {
  supported: boolean; reason?: string; shotId?: string; providerId?: string; modelCode?: string; options?: WorkflowOptions
  segments: Array<{ id: string; index: number; name: string; startFrame: number; endFrame: number; videoUrl: string; allowed: boolean; reason?: string; options?: WorkflowOptions }>
}
const capabilityCache = new Map<string, { expires: number; value: CapabilityCatalog }>()
const capabilityRequests = new Map<string, Promise<CapabilityCatalog>>()
function capabilities(providerId: string, refresh = false): Promise<CapabilityCatalog> {
  const pending = capabilityRequests.get(providerId)
  if (pending) return pending
  const cached = capabilityCache.get(providerId)
  if (!refresh && cached && cached.expires > Date.now()) return Promise.resolve(cached.value)
  const pendingRequest = request<CapabilityCatalog>({ url: '/production/video-enhancements/capabilities', method: 'get', params: { providerId }, timeout: 8000 })
    .then(value => {
      if (!Array.isArray(value?.models)) throw new Error('视频增强能力返回格式无效，请更新网关')
      capabilityCache.set(providerId, { expires: Date.now() + 5000, value })
      return value
    })
    .catch(error => { capabilityCache.delete(providerId); throw error })
    .finally(() => { capabilityRequests.delete(providerId) })
  capabilityRequests.set(providerId, pendingRequest)
  return pendingRequest
}
export const videoEnhancementApi = {
  upscaleModels: (providerId: string, modelCode: string, nodeId: string) => request<{ modelCode: string; nodeId: string; models: string[] }>({ url: '/production/video-enhancements/upscale-models', method: 'get', params: { providerId, modelCode, nodeId }, timeout: 20000 }),
  segments: (taskId: string) => request<ContinuationEnhancementSummary>({ url: `/production/video-enhancements/tasks/${encodeURIComponent(taskId)}/segments`, method: 'get' }),
  replaceSegment: (taskId: string, segmentId: string, options: WorkflowOptions, requestId: string) => request<{ taskId: string }>({ url: `/production/video-enhancements/tasks/${encodeURIComponent(taskId)}/segments/${encodeURIComponent(segmentId)}/upscale`, method: 'post', data: { options, requestId }, timeout: 120000 }),
  capabilities,
  preview: (data: { providerId: string; modelId: string; size: string; selection: WorkflowSelection }) => request<{ name: string; baseSize: string; outputSize: string; options: WorkflowOptions }>({ url: '/production/video-enhancements/preview', method: 'post', data }),
  steps: (taskId: string) => request<Array<{ step_key: string; stage: string; status: string; output_url?: string; error_message?: string }>>({ url: `/production/video-enhancements/tasks/${encodeURIComponent(taskId)}/steps`, method: 'get' })
}
