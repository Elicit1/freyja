import { request } from './request'
import { runAiTask, runAiTaskResult } from './ai-task-stream'
import type { PageResult } from '@/types/api'
import type { ResProp, ResPropQuery, ResPropOption } from '@/types/resource'

export type ResPropItem = ResPropOption

export const propApi = {
  // 分页获取道具列表
  getPage(params: ResPropQuery) {
    return request<PageResult<ResProp>>({
      url: '/res/prop/page',
      method: 'get',
      params
    })
  },

  // 获取道具详情
  getById(id: string | number) {
    return request<ResProp>({
      url: `/res/prop/${id}`,
      method: 'get'
    })
  },

  // 新增道具
  create(data: Partial<ResProp>) {
    return request<string | number>({
      url: '/res/prop',
      method: 'post',
      data
    })
  },

  // 更新道具
  update(data: Partial<ResProp>) {
    return request<void>({
      url: '/res/prop',
      method: 'put',
      data
    })
  },

  // 删除道具
  delete(id: string | number) {
    return request<void>({
      url: `/res/prop/${id}`,
      method: 'delete'
    })
  },

  // 下拉选择项
  getOptions(dramaId?: string | number) {
    return request<ResPropOption[]>({
      url: '/res/prop/options',
      method: 'get',
      params: { dramaId }
    })
  },

  // 构建道具提示词任务导出包 (专供外部 AI 手工生成通道复制使用)
  derivePromptPackage(data: import('@/types/resource').PropPromptDeriveDTO) {
    return request<import('@/types/resource').AssetPromptPackageVO>({
      url: '/res/prop/derive-prompt-package',
      method: 'post',
      data
    })
  },

  // 解析并校验外部 AI 返回的道具提示词结果文本
  parseDerivedPrompt(data: import('@/types/resource').PropPromptParseRequestDTO) {
    return request<import('@/types/resource').PropPromptValidationResult>({
      url: '/res/prop/parse-derived-prompt',
      method: 'post',
      data
    })
  },

  // AI 智能衍生道具专属提示词
  derivePrompts(data: import('@/types/resource').PropPromptDeriveDTO) {
    return runAiTaskResult<import('@/types/resource').PropPromptDeriveVO>('/ai/tasks/prop-prompt', data)
  },

  // AI 智能衍生道具专属提示词 (流式 SSE，带打字机片元与结果事件)
  async derivePromptsStream(
    data: import('@/types/resource').PropPromptDeriveDTO,
    options: {
      onChunk?: (chunk: string) => void
      onResult: (result: import('@/types/resource').PropPromptDeriveVO) => void
      onError: (err: Error) => void
      onDone?: () => void
      signal?: AbortSignal
    }
  ) {
    return runAiTask('/ai/tasks/prop-prompt', data, {
      signal: options.signal,
      onEvent(event) {
        if (event.type === 'chunk' || event.type === 'stage') options.onChunk?.(event.data)
        if (event.type === 'result') {
          try { options.onResult(JSON.parse(event.data) as import('@/types/resource').PropPromptDeriveVO) }
          catch (error) { options.onError(error as Error) }
        }
      },
      onError: options.onError,
      onDone: options.onDone
    })
  }
}

// 兼容别名导出
export const resPropApi = propApi
