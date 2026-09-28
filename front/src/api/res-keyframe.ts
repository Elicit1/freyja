import { request } from './request'
import type { PageResult } from '@/types/api'
import type { ResKeyframe, ResKeyframeQuery, ResKeyframeOption } from '@/types/resource'

export const keyframeApi = {
  // 分页获取关键帧列表
  getPage(params: ResKeyframeQuery) {
    return request<PageResult<ResKeyframe>>({
      url: '/res/keyframe/page',
      method: 'get',
      params
    })
  },

  // 获取关键帧详情
  getById(id: string | number) {
    return request<ResKeyframe>({
      url: `/res/keyframe/${id}`,
      method: 'get'
    })
  },

  // 新增关键帧
  create(data: Partial<ResKeyframe>) {
    return request<string | number>({
      url: '/res/keyframe',
      method: 'post',
      data
    })
  },

  // 更新关键帧
  update(data: Partial<ResKeyframe>) {
    return request<void>({
      url: '/res/keyframe',
      method: 'put',
      data
    })
  },

  // 删除关键帧
  delete(id: string | number) {
    return request<void>({
      url: `/res/keyframe/${id}`,
      method: 'delete'
    })
  },

  // 状态启停切换
  changeStatus(id: string | number, status: number) {
    return request<void>({
      url: `/res/keyframe/${id}/status`,
      method: 'put',
      params: { status }
    })
  },

  // 下拉选择项
  getOptions(params?: { dramaId?: string | number; shotId?: string | number }) {
    return request<ResKeyframeOption[]>({
      url: '/res/keyframe/options',
      method: 'get',
      params
    })
  }
}
