import { request } from './request'
import { runAiTask, runAiTaskResult } from './ai-task-stream'
import type { PageResult } from '@/types/api'
import type { ResScene, ResSceneQuery, ResSceneOption } from '@/types/resource'

export const sceneApi = {
  // 分页获取场景列表
  getPage(params: ResSceneQuery) {
    return request<PageResult<ResScene>>({
      url: '/res/scene/page',
      method: 'get',
      params
    })
  },

  // 获取场景详情
  getById(id: string | number) {
    return request<ResScene>({
      url: `/res/scene/${id}`,
      method: 'get'
    })
  },

  // 新增场景
  create(data: Partial<ResScene>) {
    return request<string | number>({
      url: '/res/scene',
      method: 'post',
      data
    })
  },

  // 更新场景
  update(data: Partial<ResScene>) {
    return request<void>({
      url: '/res/scene',
      method: 'put',
      data
    })
  },

  // 删除场景
  delete(id: string | number) {
    return request<void>({
      url: `/res/scene/${id}`,
      method: 'delete'
    })
  },

  // 下拉选择项
  getOptions(dramaId?: string | number) {
    return request<ResSceneOption[]>({
      url: '/res/scene/options',
      method: 'get',
      params: { dramaId }
    })
  },

  // 构建场景提示词任务导出包 (专供外部 AI 手工生成通道复制使用)
  derivePromptPackage(data: import('@/types/resource').ScenePromptDeriveDTO) {
    return request<import('@/types/resource').AssetPromptPackageVO>({
      url: '/res/scene/derive-prompt-package',
      method: 'post',
      data
    })
  },

  // 解析并校验外部 AI 返回的场景提示词结果文本
  parseDerivedPrompt(data: import('@/types/resource').ScenePromptParseRequestDTO) {
    return request<import('@/types/resource').ScenePromptValidationResult>({
      url: '/res/scene/parse-derived-prompt',
      method: 'post',
      data
    })
  },

  // AI 智能衍生场景专属提示词
  derivePrompts(data: import('@/types/resource').ScenePromptDeriveDTO) {
    return runAiTaskResult<import('@/types/resource').ScenePromptDeriveVO>('/ai/tasks/scene-prompt', data)
  },

  // AI 智能衍生场景专属提示词 (流式 SSE，带打字机片元与结果事件)
  async derivePromptsStream(
    data: import('@/types/resource').ScenePromptDeriveDTO,
    options: {
      onChunk?: (chunk: string) => void
      onResult: (result: import('@/types/resource').ScenePromptDeriveVO) => void
      onError: (err: Error) => void
      onDone?: () => void
      signal?: AbortSignal
    }
  ) {
    return runAiTask('/ai/tasks/scene-prompt', data, {
      signal: options.signal,
      onEvent(event) {
        if (event.type === 'chunk' || event.type === 'stage') options.onChunk?.(event.data)
        if (event.type === 'result') {
          try { options.onResult(JSON.parse(event.data) as import('@/types/resource').ScenePromptDeriveVO) }
          catch (error) { options.onError(error as Error) }
        }
      },
      onError: options.onError,
      onDone: options.onDone
    })
  }
}
