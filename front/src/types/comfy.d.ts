/**
 * ComfyUI 渲染任务状态
 */
export type ComfyTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELED'

/**
 * 提交 ComfyUI 渲染生成任务参数
 */
export interface ComfyRenderSubmitDTO {
  providerId?: number | string
  workflowTemplateId?: string
  workflowJson?: string
  prompt: string
  negativePrompt?: string
  seed?: number
  steps?: number
  referenceImage?: string
  projectId?: number | string
  shotId?: number | string
  customParams?: Record<string, any>
}

/**
 * ComfyUI 渲染任务实时状态与结果 VO
 */
export interface ComfyRenderTaskVO {
  taskId: string
  promptId?: string
  status: ComfyTaskStatus
  statusDesc: string
  progress: number // 0-100
  currentNode?: string
  outputUrl?: string
  outputFilename?: string
  errorMessage?: string
  createTime?: string
  finishTime?: string
  executionTimeMs?: number
}

/**
 * 预设 ComfyUI 工作流模板
 */
export interface ComfyWorkflowTemplateVO {
  templateId: string
  templateName: string
  templateType: 'IMAGE' | 'VIDEO'
  description: string
  supportedParams: string[]
  defaultWorkflowJson: string
}
