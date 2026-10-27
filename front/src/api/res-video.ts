import type { AxiosProgressEvent } from 'axios'
import { request } from './request'
import type { PageResult } from '@/types/api'
import type { ResVideo, ResVideoQuery } from '@/types/resource'

export const videoAssetApi = {
  getPage: (params: ResVideoQuery) => request<PageResult<ResVideo>>({ url: '/res/video/page', method: 'get', params }),
  getById: (id: string) => request<ResVideo>({ url: `/res/video/${id}`, method: 'get' }),
  upload(file: File, dramaId?: string | number, shotId?: string, name?: string, sourceType: 'MANUAL_UPLOAD' | 'SHOT_UPLOAD' = 'MANUAL_UPLOAD', metadata?: Partial<ResVideo>, onUploadProgress?: (event: AxiosProgressEvent) => void) {
    const data = new FormData(); data.append('file', file)
    data.append('sourceType', sourceType)
    if (dramaId != null && String(dramaId) !== '') data.append('dramaId', String(dramaId))
    if (shotId) data.append('shotId', shotId)
    if (name?.trim()) data.append('name', name.trim())
    for (const key of ['description', 'remark', 'sortOrder', 'status'] as const) {
      const value = metadata?.[key]
      if (value != null) data.append(key, String(value))
    }
    return request<ResVideo>({ url: '/res/video/upload', method: 'post', data, onUploadProgress, timeout: 180000, headers: { 'Content-Type': 'multipart/form-data' } })
  },
  update: (data: Partial<ResVideo>) => request<void>({ url: '/res/video', method: 'put', data }),
  delete: (id: string) => request<void>({ url: `/res/video/${id}`, method: 'delete' })
}
