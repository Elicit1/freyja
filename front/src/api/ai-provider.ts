import { request } from './request'
import type { PageResult } from '@/types/api'
import type {
  AiProviderVO,
  AiProviderDTO,
  AiProviderQuery,
  AiModel,
  AiModelDTO,
  AiModelQuery
} from '@/types/ai-provider'

/**
 * AI 提供商管理接口
 */
export const aiProviderApi = {
  // 分页查询提供商
  getPage(params?: AiProviderQuery) {
    return request<PageResult<AiProviderVO>>({
      url: '/ai/provider/page',
      method: 'get',
      params
    })
  },

  // 启用中的提供商列表（下拉使用）
  getListEnabled() {
    return request<AiProviderVO[]>({
      url: '/ai/provider/list/enabled',
      method: 'get'
    })
  },

  // 查询提供商详情
  getDetail(id: number | string) {
    return request<AiProviderVO>({
      url: `/ai/provider/${id}`,
      method: 'get'
    })
  },

  // 新增提供商
  create(data: AiProviderDTO) {
    return request<void>({
      url: '/ai/provider',
      method: 'post',
      data
    })
  },

  // 修改提供商
  update(data: AiProviderDTO) {
    return request<void>({
      url: '/ai/provider',
      method: 'put',
      data
    })
  },

  // 删除提供商
  delete(id: number | string) {
    return request<void>({
      url: `/ai/provider/${id}`,
      method: 'delete'
    })
  },

  // 连通性测试
  test(id: number | string, modelCode?: string) {
    return request<void>({
      url: `/ai/provider/${id}/test`,
      method: 'post',
      params: modelCode ? { modelCode } : undefined
    })
  },

  // 查询指定提供商下启用的模型列表（下拉/快速选择，可选按 modelType 逗号分隔过滤）
  getModelList(providerId: number | string, modelType?: string) {
    return request<AiModel[]>({
      url: `/ai/provider/${providerId}/model/list`,
      method: 'get',
      params: modelType ? { modelType } : undefined
    })
  }
}

/**
 * AI 模型管理接口
 */
export const aiModelApi = {
  // 分页查询模型
  getPage(params?: AiModelQuery) {
    return request<PageResult<AiModel>>({
      url: '/ai/model/page',
      method: 'get',
      params
    })
  },

  // 查询模型详情
  getDetail(id: number | string) {
    return request<AiModel>({
      url: `/ai/model/${id}`,
      method: 'get'
    })
  },

  // 新增模型
  create(data: AiModelDTO) {
    return request<void>({
      url: '/ai/model',
      method: 'post',
      data
    })
  },

  // 修改模型
  update(data: AiModelDTO) {
    return request<void>({
      url: '/ai/model',
      method: 'put',
      data
    })
  },

  // 删除模型
  delete(id: number | string) {
    return request<void>({
      url: `/ai/model/${id}`,
      method: 'delete'
    })
  }
}
