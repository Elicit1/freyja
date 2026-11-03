export type RenderSubmissionStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELED'

export interface RenderSubmissionVO {
  taskId: string
  promptId?: string
  status: RenderSubmissionStatus
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
