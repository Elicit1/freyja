import { request } from './request'
import type { PageResult } from '@/types/api'
import type { SysConfig, SysConfigQuery, SysConfigDTO } from '@/types/sys-config'

/**
 * 系统参数配置 API
 */
export const sysConfigApi = {
  // 分页查询配置
  getPage(params?: SysConfigQuery) {
    return request<PageResult<SysConfig>>({
      url: '/system/config/page',
      method: 'get',
      params
    })
  },

  // 查询配置详情
  getDetail(id: number | string) {
    return request<SysConfig>({
      url: `/system/config/${id}`,
      method: 'get'
    })
  },

  // 根据 Key 查询配置实体
  getByKey(configKey: string) {
    return request<SysConfig>({
      url: `/system/config/key/${encodeURIComponent(configKey)}`,
      method: 'get'
    })
  },

  // 根据 Key 查询配置值字符串
  getValueByKey(configKey: string, defaultValue?: string) {
    return request<string>({
      url: `/system/config/value/${encodeURIComponent(configKey)}`,
      method: 'get',
      params: defaultValue ? { defaultValue } : undefined
    })
  },

  // 新增配置
  create(data: SysConfigDTO) {
    return request<void>({
      url: '/system/config',
      method: 'post',
      data
    })
  },

  // 修改配置
  update(data: SysConfigDTO) {
    return request<void>({
      url: '/system/config',
      method: 'put',
      data
    })
  },

  // 删除配置
  delete(id: number | string) {
    return request<void>({
      url: `/system/config/${id}`,
      method: 'delete'
    })
  },

  // 刷新并预热 Redis 缓存
  refreshCache() {
    return request<void>({
      url: '/system/config/refresh-cache',
      method: 'post'
    })
  }
}
