import { request } from './request'
import type { AssetUploadResult } from '@/types/resource'

export const assetApi = {
  upload(file: File, category: string = 'general') {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('category', category)
    return request<AssetUploadResult>({
      url: '/res/asset/upload',
      method: 'post',
      data: formData,
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
  }
}
