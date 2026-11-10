import { request } from './request'

export interface UpscaleTilingOptions { enabled: boolean; tileWidth: number; tileHeight: number; overlap: number; temporal: boolean; windowFrames: number; overlapFrames: number }
export interface WorkflowOptions { upscale: boolean; faceRepair: boolean; scale: 2; upscaleDenoise: number; faceDenoise: number; upscaleTiling: UpscaleTilingOptions; upscaleEngine: 'PIXEL' | 'FREYJA'; upscaleMethod: 'LATENT_BICUBIC' | 'H3_LEARNED'; upscaleLatentModel?: string; upscaleOutput: 'DOUBLE' | 'FULL_HD'; upscaleSteps: number; upscaleMemory: 'FULL' | 'TEMPORAL' | 'SPATIAL' }
export interface WorkflowSelection { options?: WorkflowOptions; reusePrevious?: boolean; reuseFromTaskId?: string }
export interface WorkflowCapability { modelCode: string; nodeId: string; supported: boolean; legacyFixedFace: boolean; revision?: string; stages: string[]; maxTargetPixels: number; upscaleTiling?: { supported: boolean; node: string; apiVersion: string }; freyjaUpscale?: { supported: boolean; version: number; maxSteps: number } }
export const defaultUpscaleTiling = (): UpscaleTilingOptions => ({ enabled: false, tileWidth: 768, tileHeight: 512, overlap: 192, temporal: false, windowFrames: 56, overlapFrames: 22 })
export const defaultWorkflowOptions = (): WorkflowOptions => ({ upscale: false, faceRepair: false, scale: 2, upscaleDenoise: .25, faceDenoise: .4, upscaleTiling: defaultUpscaleTiling(), upscaleEngine: 'FREYJA', upscaleMethod: 'LATENT_BICUBIC', upscaleOutput: 'FULL_HD', upscaleSteps: 2, upscaleMemory: 'TEMPORAL' })
export const normalizeWorkflowOptions = (options: Partial<WorkflowOptions>): WorkflowOptions => ({ ...defaultWorkflowOptions(), ...options, upscaleEngine: options.upscaleEngine || 'PIXEL', upscaleOutput: options.upscaleOutput || 'DOUBLE', upscaleTiling: { ...defaultUpscaleTiling(), ...options.upscaleTiling } })
export const videoEnhancementApi = {
  capabilities: (providerId: string) => request<{ models: WorkflowCapability[] }>({ url: '/production/video-enhancements/capabilities', method: 'get', params: { providerId } }),
  preview: (data: { providerId: string; modelId: string; size: string; selection: WorkflowSelection }) => request<{ name: string; baseSize: string; outputSize: string; options: WorkflowOptions }>({ url: '/production/video-enhancements/preview', method: 'post', data }),
  steps: (taskId: string) => request<Array<{ step_key: string; stage: string; status: string; output_url?: string; error_message?: string }>>({ url: `/production/video-enhancements/tasks/${encodeURIComponent(taskId)}/steps`, method: 'get' })
}
