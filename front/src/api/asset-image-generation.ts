import { request } from './request'
import { runAiTaskResult } from './ai-task-stream'

export interface AssetImageGenerationRequest {
  targetType: 'CHARACTER' | 'CHARACTER_OUTFIT' | 'SCENE' | 'PROP'
  targetId?: string | number
  slot: string
  mode?: string
  providerId?: number | string
  modelCode?: string
  workflowTemplateId?: string
  prompt: string
  negativePrompt?: string
  referenceImageUrls?: string[]
  size?: string
}

export interface AssetImageGenerationTask {
  taskId: string
  mode: string
  status: string
  progress: number
  outputUrl?: string
  errorMessage?: string
}

export const assetImageGenerationApi = {
  submit(data: AssetImageGenerationRequest) {
    return runAiTaskResult<AssetImageGenerationTask>('/ai/tasks/asset-image', data)
  },
  getTask(taskId: string) {
    return request<AssetImageGenerationTask>({ url: `/assets/image-generation/task/${taskId}`, method: 'get' })
  },
  apply(targetType: string, targetId: string | number, slot: string, imageUrl: string) {
    return request<void>({ url: '/assets/image-generation/apply', method: 'post', params: { targetType, targetId, slot, imageUrl } })
  }
}
