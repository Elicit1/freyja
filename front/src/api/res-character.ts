import { request } from './request'
import { runAiTask, runAiTaskResult } from './ai-task-stream'
import type { PageResult } from '@/types/api'
import type {
  ResCharacter,
  ResCharacterQuery,
  ResCharacterOption,
  ResCharacterLook,
  ResCharacterOutfit,
  ResCharacterAlias,
  ResCharacterEvidence,
  ResCharacterResolution,
  CharacterMergeParam,
  CharacterVisualPromptDeriveDTO,
  CharacterVisualPromptDeriveVO,
  OutfitPromptDeriveDTO,
  OutfitPromptDeriveVO,
  AssetPromptPackageVO
} from '@/types/resource'

export const characterApi = {
  // 构建角色身份层视觉提示词任务导出包 (专供外部 AI 手工生成通道复制使用)
  deriveVisualPromptPackage(data: CharacterVisualPromptDeriveDTO) {
    return request<AssetPromptPackageVO>({
      url: '/res/character/derive-visual-prompt-package',
      method: 'post',
      data
    })
  },
  // AI 智能衍生角色身份层视觉提示词 (外观 appearancePrompt 与负向 negativePrompt)
  deriveVisualPrompt(data: CharacterVisualPromptDeriveDTO) {
    return runAiTaskResult<CharacterVisualPromptDeriveVO>('/ai/tasks/character-prompt', data)
  },
  // AI 智能衍生角色身份层视觉提示词 (流式 SSE)
  async deriveVisualPromptStream(
    data: CharacterVisualPromptDeriveDTO,
    options: {
      onChunk?: (chunk: string) => void
      onResult: (result: CharacterVisualPromptDeriveVO) => void
      onError: (err: Error) => void
      onDone?: () => void
      signal?: AbortSignal
    }
  ) {
    return runAiTask('/ai/tasks/character-prompt', data, {
      signal: options.signal,
      onEvent(event) {
        if (event.type === 'chunk' || event.type === 'stage') options.onChunk?.(event.data)
        if (event.type === 'result') {
          try { options.onResult(JSON.parse(event.data) as CharacterVisualPromptDeriveVO) }
          catch (error) { options.onError(error as Error) }
        }
      },
      onError: options.onError,
      onDone: options.onDone
    })
  },
  // 分页获取人物列表
  getPage(params: ResCharacterQuery) {
    return request<PageResult<ResCharacter>>({
      url: '/res/character/page',
      method: 'get',
      params
    })
  },

  // 获取人物详情
  getById(id: string | number) {
    return request<ResCharacter>({
      url: `/res/character/${id}`,
      method: 'get'
    })
  },

  // 新增人物
  create(data: Partial<ResCharacter>) {
    return request<string | number>({
      url: '/res/character',
      method: 'post',
      data
    })
  },

  // 更新人物
  update(data: Partial<ResCharacter>) {
    return request<void>({
      url: '/res/character',
      method: 'put',
      data
    })
  },

  // 删除人物
  delete(id: string | number) {
    return request<void>({
      url: `/res/character/${id}`,
      method: 'delete'
    })
  },

  // 下拉选择项
  getOptions(dramaId?: string | number) {
    return request<ResCharacterOption[]>({
      url: '/res/character/options',
      method: 'get',
      params: { dramaId }
    })
  },

  // ============ 角色消歧与合并相关接口 ============
  // 合并角色
  merge(data: CharacterMergeParam) {
    return request<void>({
      url: '/res/character/merge',
      method: 'post',
      data
    })
  },

  // 获取角色别名列表
  getAliases(characterId: string | number) {
    return request<ResCharacterAlias[]>({
      url: `/res/character/${characterId}/aliases`,
      method: 'get'
    })
  },

  // 新增角色别名
  addAlias(data: Partial<ResCharacterAlias>) {
    return request<string | number>({
      url: '/res/character/aliases',
      method: 'post',
      data
    })
  },

  // 更新角色别名
  updateAlias(data: Partial<ResCharacterAlias>) {
    return request<void>({
      url: '/res/character/aliases',
      method: 'put',
      data
    })
  },

  // 删除角色别名
  deleteAlias(aliasId: string | number) {
    return request<void>({
      url: `/res/character/aliases/${aliasId}`,
      method: 'delete'
    })
  },

  // 获取角色身份证据链
  getEvidences(characterId: string | number) {
    return request<ResCharacterEvidence[]>({
      url: `/res/character/${characterId}/evidences`,
      method: 'get'
    })
  },

  // 获取待人工决议消歧列表
  getPendingResolutions(dramaId?: string | number) {
    return request<ResCharacterResolution[]>({
      url: '/res/character/resolutions/pending',
      method: 'get',
      params: { dramaId }
    })
  },

  // 人工决议处理消歧
  resolveResolution(data: { resolutionId: string | number; action: string; targetCharacterId?: string | number; remark?: string }) {
    return request<void>({
      url: '/res/character/resolutions/resolve',
      method: 'post',
      data
    })
  },

  // ============ 造型标准相关接口 (Look API) ============
  getLookList(characterId: string | number) {
    return request<ResCharacterLook[]>({
      url: `/res/character/${characterId}/looks`,
      method: 'get'
    })
  },

  getDefaultLook(characterId: string | number) {
    return request<ResCharacterLook>({
      url: `/res/character/${characterId}/default-look`,
      method: 'get'
    })
  },

  createLook(characterId: string | number, data: Partial<ResCharacterLook>) {
    return request<string | number>({
      url: `/res/character/${characterId}/looks`,
      method: 'post',
      data
    })
  },

  updateLook(id: string | number, data: Partial<ResCharacterLook>) {
    return request<void>({
      url: `/res/character/look/${id}`,
      method: 'put',
      data
    })
  },

  deleteLook(id: string | number) {
    return request<void>({
      url: `/res/character/look/${id}`,
      method: 'delete'
    })
  },

  setDefaultLook(id: string | number) {
    return request<void>({
      url: `/res/character/look/${id}/set-default`,
      method: 'put'
    })
  },
  // ============ 兼容旧造型变体接口 (Outfit API) ============
  getOutfitList(characterId: string | number) {
    return this.getLookList(characterId)
  },

  createOutfit(data: Partial<ResCharacterOutfit>) {
    return request<number>({
      url: '/res/character/outfit',
      method: 'post',
      data
    })
  },

  updateOutfit(data: Partial<ResCharacterOutfit>) {
    return request<void>({
      url: '/res/character/outfit',
      method: 'put',
      data
    })
  },

  deleteOutfit(id: string | number) {
    return this.deleteLook(id)
  },

  setDefaultOutfit(id: string | number) {
    return this.setDefaultLook(id)
  },

  // 构建造型提示词任务导出包 (专供外部 AI 手工生成通道复制使用)
  deriveOutfitPromptPackage(data: OutfitPromptDeriveDTO) {
    return request<AssetPromptPackageVO>({
      url: '/res/character/look/derive-prompt-package',
      method: 'post',
      data
    })
  },

  // AI 智能衍生角色造型专属提示词
  deriveOutfitPrompt(data: OutfitPromptDeriveDTO) {
    return runAiTaskResult<OutfitPromptDeriveVO>('/ai/tasks/look-prompt', data)
  },

  // AI 智能衍生角色造型专属提示词 (流式 SSE)
  async deriveOutfitPromptStream(
    data: OutfitPromptDeriveDTO,
    options: {
      onChunk?: (chunk: string) => void
      onResult: (result: OutfitPromptDeriveVO) => void
      onError: (err: Error) => void
      onDone?: () => void
      signal?: AbortSignal
    }
  ) {
    return runAiTask('/ai/tasks/look-prompt', data, {
      signal: options.signal,
      onEvent(event) {
        if (event.type === 'chunk' || event.type === 'stage') options.onChunk?.(event.data)
        if (event.type === 'result') {
          try { options.onResult(JSON.parse(event.data) as OutfitPromptDeriveVO) }
          catch (error) { options.onError(error as Error) }
        }
      },
      onError: options.onError,
      onDone: options.onDone
    })
  }
}
