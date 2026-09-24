import { request } from './request'
import type { PageResult } from '@/types/api'
import type { SysDictType, SysDictData, DictTypeQuery, DictDataQuery } from '@/types/dict'

/**
 * 字典类型相关接口
 */
export const dictTypeApi = {
  // 分页查询字典类型
  getPage(params?: DictTypeQuery) {
    return request<PageResult<SysDictType>>({
      url: '/dict/type/page',
      method: 'get',
      params
    })
  },

  // 查询类型详情
  getDetail(id: number | string) {
    return request<SysDictType>({
      url: `/dict/type/${id}`,
      method: 'get'
    })
  },

  // 新增字典类型
  add(data: Partial<SysDictType>) {
    return request<void>({
      url: '/dict/type',
      method: 'post',
      data
    })
  },

  // 修改字典类型
  update(data: Partial<SysDictType>) {
    return request<void>({
      url: '/dict/type',
      method: 'put',
      data
    })
  },

  // 删除字典类型
  delete(id: number | string) {
    return request<void>({
      url: `/dict/type/${id}`,
      method: 'delete'
    })
  }
}

/**
 * 字典数据项相关接口
 */
export const dictDataApi = {
  // 分页查询字典数据项
  getPage(params?: DictDataQuery) {
    return request<PageResult<SysDictData>>({
      url: '/dict/data/page',
      method: 'get',
      params
    })
  },

  // 查询数据项详情
  getDetail(id: number | string) {
    return request<SysDictData>({
      url: `/dict/data/${id}`,
      method: 'get'
    })
  },

  // 新增字典数据项
  add(data: Partial<SysDictData>) {
    return request<void>({
      url: '/dict/data',
      method: 'post',
      data
    })
  },

  // 修改字典数据项
  update(data: Partial<SysDictData>) {
    return request<void>({
      url: '/dict/data',
      method: 'put',
      data
    })
  },

  // 删除字典数据项
  delete(id: number | string) {
    return request<void>({
      url: `/dict/data/${id}`,
      method: 'delete'
    })
  }
}

/**
 * 字典公共与缓存接口
 */
export const dictCommonApi = {
  // 根据字典类型获取启用的字典项列表（前端下拉常用）
  getDataByType(dictType: string) {
    return request<SysDictData[]>({
      url: `/dict/type/${dictType}/data`,
      method: 'get'
    })
  },

  // 清除指定类型的 Redis 缓存
  clearCache(dictType: string) {
    return request<void>({
      url: `/dict/cache/${dictType}`,
      method: 'delete'
    })
  }
}
