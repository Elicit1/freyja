import type { PageQuery } from './api'

/**
 * AI 提供商接入类型
 */
export type ProviderType = 'OPENAI' | 'OLLAMA'

/**
 * AI 模型类型
 * CHAT: 文本对话
 * TXT2IMG: 文生图 (纯文生图，不支持参考图输入)
 * IMG2IMG: 图生图 (纯图像转绘/滤镜，需底图)
 * TXT_IMG2IMG: 文生图/图生图 (双模，支持纯文生图，亦支持参考图引导)
 * TXT2VIDEO_FIRST_LAST: 文生视频/首尾帧参考 (支持纯文本生成视频，或可选首帧/首尾帧生成连贯视频)
 * TXT2VIDEO_REF: 文生视频/图参考 (文本+多张参考图特征引导生成连贯视频)
 * TTS: TTS 语音模型 (角色台词配音与文本转语音合成)
 * LIP_SYNC: 音画同步模型 (角色视频与配音音频口型同步对齐)
 * VIDEO_UPSCALE: 视频超分辨率 (画质放大与细节重建)
 * FRAME_INTERPOLATION: 视频帧插值/补帧 (平滑高帧率插帧)
 * EMBEDDING: 向量嵌入
 * IMAGE: 兼容历史纯图像模型
 */
export type ModelType =
  | 'CHAT'
  | 'TXT2IMG'
  | 'IMG2IMG'
  | 'TXT_IMG2IMG'
  | 'TXT2VIDEO_FIRST_LAST'
  | 'TXT2VIDEO_REF'
  | 'TTS'
  | 'LIP_SYNC'
  | 'VIDEO_UPSCALE'
  | 'FRAME_INTERPOLATION'
  | 'EMBEDDING'
  | 'IMAGE'

/**
 * AI 提供商响应视图对象 AiProviderVO
 */
export interface AiProviderVO {
  id: number | string
  providerCode: string
  providerName: string
  providerType: ProviderType
  maskedApiKey?: string
  hasApiKey?: boolean
  baseUrl?: string
  timeout?: number
  maxRetries?: number
  enableBreaker?: number // 0-关 1-开
  breakerThreshold?: number
  breakerTimeout?: number
  status: number // 0-停用 1-启用
  remark?: string
  createTime?: string
  updateTime?: string
}

/**
 * AI 提供商新增/编辑入参 AiProviderDTO
 */
export interface AiProviderDTO {
  id?: number | string
  providerCode: string
  providerName: string
  providerType: ProviderType
  apiKey?: string
  baseUrl?: string
  timeout?: number
  maxRetries?: number
  enableBreaker?: number
  breakerThreshold?: number
  breakerTimeout?: number
  status: number
  remark?: string
}

/**
 * AI 提供商查询入参 AiProviderQuery
 */
export interface AiProviderQuery extends PageQuery {
  providerName?: string
  providerType?: string
  status?: number
}

/**
 * AI 模型实体 / 响应对象 AiModel
 */
export interface AiModel {
  id?: number | string
  providerId: number | string
  modelCode: string
  modelName: string
  modelType: ModelType
  temperature?: number
  maxTokens?: number
  topP?: number
  paramsJson?: string
  maxImages?: number
  maxAudios?: number
  maxVideos?: number
  sortOrder?: number
  status: number // 0-停用 1-启用
  remark?: string
  createTime?: string
  updateTime?: string
}

/**
 * AI 模型新增/编辑入参 AiModelDTO
 */
export interface AiModelDTO {
  id?: number | string
  providerId: number | string
  modelCode: string
  modelName: string
  modelType?: ModelType
  temperature?: number
  maxTokens?: number
  topP?: number
  paramsJson?: string
  maxImages?: number
  maxAudios?: number
  maxVideos?: number
  sortOrder?: number
  status: number
  remark?: string
}

/**
 * AI 模型查询入参 AiModelQuery
 */
export interface AiModelQuery extends PageQuery {
  providerId?: number | string
  modelName?: string
  modelType?: string
  status?: number
}
