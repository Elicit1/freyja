import type { AxiosProgressEvent } from 'axios'
import { request } from './request'
import type { PageResult } from '@/types/api'
import type { ResAudio, ResAudioQuery } from '@/types/resource'

export const audioAssetApi = {
  getPage: (params: ResAudioQuery) => request<PageResult<ResAudio>>({ url: '/res/audio/page', method: 'get', params }),
  getById: (id: string) => request<ResAudio>({ url: `/res/audio/${id}`, method: 'get' }),
  upload(file: File, dramaId?: string | number, shotId?: string, name?: string, sourceType: 'MANUAL_UPLOAD' | 'SHOT_UPLOAD' = 'MANUAL_UPLOAD', metadata?: Partial<ResAudio>, onUploadProgress?: (event: AxiosProgressEvent) => void) {
    const data = new FormData(); data.append('file', file)
    data.append('sourceType', sourceType)
    if (metadata?.audioKind) data.append('audioKind', metadata.audioKind)
    if (metadata?.text) data.append('text', metadata.text)
    if (metadata?.language) data.append('language', metadata.language)
    if (dramaId != null && String(dramaId) !== '') data.append('dramaId', String(dramaId))
    if (shotId) data.append('shotId', shotId)
    if (name?.trim()) data.append('name', name.trim())
    for (const key of ['description', 'remark', 'sortOrder', 'status'] as const) {
      const value = metadata?.[key]
      if (value != null) data.append(key, String(value))
    }
    return request<ResAudio>({ url: '/res/audio/upload', method: 'post', data, onUploadProgress, timeout: 180000, headers: { 'Content-Type': 'multipart/form-data' } })
  },
  update: (data: Partial<ResAudio>) => request<void>({ url: '/res/audio', method: 'put', data }),
  delete: (id: string) => request<void>({ url: `/res/audio/${id}`, method: 'delete' })
}
