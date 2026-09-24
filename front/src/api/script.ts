import { request } from './request'
import { runAiTask, runAiTaskResult, watchAiTask } from './ai-task-stream'
import type {
  ScriptDecomposeRequest,
  ScriptDecomposeResult,
  ScriptDecomposeCommit,
  DecomposedEpisode
} from '@/types/script'

export const scriptApi = {
  /** 创建后台拆解任务，返回可在任务中心重新打开的任务 ID。 */
  startDecomposeTask(data: ScriptDecomposeRequest) {
    return request<string>({ url: '/script/decompose/task', method: 'post', data })
  },
  /**
   * 一键 AI 剧本智能拆解 (同步方式)
   */
  decompose(data: ScriptDecomposeRequest) {
    return runAiTaskResult<ScriptDecomposeResult>('/script/decompose/task', data)
  },

  /**
   * 流式 (SSE) 一键 AI 剧本智能拆解
   */
  async decomposeStream(
    data: ScriptDecomposeRequest | undefined,
    options: {
      onChunk?: (chunk: string) => void
      onSegmentsInit?: (segments: any[]) => void
      onAssetsDiscovered?: (assets: { characters: any[], scenes: any[], props: any[] }) => void
      onChannelChunk?: (channel: string, chunk: string) => void
      onWorkerStatus?: (channel: string, status: string, shotsCount?: number, duration?: number) => void
      onSkillEvent?: (event: import('@/types/script').ScriptSkillEvent) => void
      onChannelReset?: (channel: string) => void
      onResult: (result: ScriptDecomposeResult) => void
      onError: (err: Error) => void
      onDone?: () => void
      signal?: AbortSignal
    },
    existingTaskId?: string
  ) {
    const callbacks: Parameters<typeof watchAiTask>[1] = {
      signal: options.signal,
      onEvent(event) {
        try {
          if (event.type === 'segments_init') options.onSegmentsInit?.(JSON.parse(event.data))
          else if (event.type === 'assets_discovered') options.onAssetsDiscovered?.(JSON.parse(event.data))
          else if (event.type === 'channel_chunk') {
            const payload = JSON.parse(event.data)
            options.onChannelChunk?.(payload.channel || 'ALL', payload.chunk || '')
            if (payload.chunk) options.onChunk?.(payload.chunk)
          } else if (event.type === 'worker_status') {
            const payload = JSON.parse(event.data)
            options.onWorkerStatus?.(payload.channel, payload.status, payload.shotsCount, payload.duration)
          } else if (event.type === 'skill_event') {
            options.onSkillEvent?.(JSON.parse(event.data) as import('@/types/script').ScriptSkillEvent)
          } else if (event.type === 'channel_reset') {
            options.onChannelReset?.(JSON.parse(event.data).channel)
          } else if (event.type === 'chunk') options.onChunk?.(event.data)
          else if (event.type === 'result') options.onResult(JSON.parse(event.data) as ScriptDecomposeResult)
        } catch (error) {
          options.onError(error as Error)
        }
      },
      onError: options.onError,
      onDone: options.onDone
    }
    return existingTaskId
      ? watchAiTask(existingTaskId, callbacks)
      : runAiTask('/script/decompose/task', data, callbacks)
  },

  /**
   * 确认并持久化一键入库
   */
  commit(data: ScriptDecomposeCommit) {
    return request<number>({
      url: '/script/commit',
      method: 'post',
      data
    })
  },

  /**
   * 单集细化拆解
   */
  decomposeEpisode(data: ScriptDecomposeRequest) {
    return runAiTaskResult<DecomposedEpisode>('/ai/tasks/episode/decompose', data)
  },

  /**
   * 查询章节拆解历史记录 (支持短剧和剧集强隔离)
   */
  getHistory(dramaId?: number | null, episodeId?: number | null) {
    return request<import('@/types/script').ChapterDecomposeHistory[]>({
      url: '/script/task/history',
      method: 'get',
      params: {
        dramaId: dramaId || undefined,
        episodeId: episodeId || undefined
      }
    })
  },

  /**
   * 获取指定任务落库的完整拆解预览大纲 (用于历史记录一键载入恢复)
   */
  getTaskPreview(taskId: string | number) {
    return request<ScriptDecomposeResult>({
      url: `/script/task/${taskId}/preview`,
      method: 'get'
    })
  },

  getTaskDraft(taskId: string | number) {
    return request<ScriptDecomposeRequest>({
      url: `/script/task/${taskId}/draft`,
      method: 'get'
    })
  },

  /**
   * 针对失败的单 Worker 分段进行局部提示词微调并重新执行合并
   */
  retryWorker(taskId: string | number, data: import('@/types/script').WorkerRetryRequest) {
    return runAiTaskResult<ScriptDecomposeResult>(`/ai/tasks/worker/${String(taskId)}/retry`, data)
  },

  /**
   * 删除拆解任务记录及关联子任务
   */
  deleteTask(taskId: string | number) {
    return request<boolean>({
      url: `/script/task/${taskId}`,
      method: 'delete'
    })
  }
}

/**
 * 健壮的 JSON 提取与修复解析函数 (应对大模型输出的 markdown 代码块与前后注释)
 */
export function tryExtractJson(text: string): any {
  if (!text) return null
  let cleaned = text.trim()
  if (cleaned.startsWith('```')) {
    cleaned = cleaned.replace(/^```[a-zA-Z]*\s*/, '').replace(/\s*```$/, '').trim()
  }
  const firstBrace = cleaned.indexOf('{')
  const lastBrace = cleaned.lastIndexOf('}')
  if (firstBrace !== -1 && lastBrace !== -1 && lastBrace > firstBrace) {
    cleaned = cleaned.substring(firstBrace, lastBrace + 1)
  }
  try {
    return JSON.parse(cleaned)
  } catch (e) {
    console.warn('[tryExtractJson] 解析失败:', e)
    return null
  }
}

