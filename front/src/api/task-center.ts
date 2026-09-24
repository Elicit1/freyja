import { request } from './request'

export type TaskCenterStatus = 'QUEUED' | 'PENDING' | 'RUNNING' | 'RETRYING' | 'SUCCESS' | 'PARTIAL_SUCCESS' | 'FAILED' | 'CANCELLED'

export interface TaskCenterItem {
  sourceType: 'AI_TASK' | 'RENDER_TASK' | string
  taskId: string
  category: 'AI_ANALYSIS' | 'RENDER' | 'MEDIA_PROCESS' | string
  taskType?: string
  title: string
  status: TaskCenterStatus | string
  progress?: number
  currentStage?: string
  modelCode?: string
  errorMessage?: string
  dramaId?: string
  dramaTitle?: string
  episodeId?: string
  episodeName?: string
  sceneId?: string
  sceneName?: string
  shotId?: string
  shotNo?: number
  shotName?: string
  targetType?: string
  targetId?: string
  resumeAction?: 'SHOT_PROMPT_DERIVE' | 'SHOT_RENDER' | 'SHOT_DETAIL' | 'SCRIPT_DECOMPOSE' | 'ASSET_DETAIL' | 'TASK_DETAIL' | string
  createdAt?: string
  startedAt?: string
  finishedAt?: string
  unread?: boolean
}

export interface TaskCenterHistory {
  records: TaskCenterItem[]
  total: number
}

export interface AiTaskDetails {
  id: string
  status: string
  taskType: string
  outputPayload?: string
  errorMessage?: string
}

export const taskCenterApi = {
  getAiTask(taskId: string) {
    return request<AiTaskDetails>({ url: `/ai/tasks/${String(taskId)}`, method: 'get' })
  },
  getActive() {
    return request<TaskCenterItem[]>({
      url: '/task-center/active',
      method: 'get'
    })
  },

  getHistory(limit = 50) {
    return request<TaskCenterHistory>({
      url: '/task-center/history',
      method: 'get',
      params: { limit }
    })
  },

  getTask(sourceType: string, taskId: string) {
    return request<TaskCenterItem>({
      url: `/task-center/${sourceType}/${taskId}`,
      method: 'get'
    })
  },

  cancelTask(sourceType: string, taskId: string) {
    return request<boolean>({
      url: `/task-center/${sourceType}/${taskId}/cancel`,
      method: 'post'
    })
  }
}
