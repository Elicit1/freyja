import { request } from './request'
import type { AiSkill, AiSkillPreview, AiSkillVersion, AiSkillSwitchVersionDTO } from '@/types/skill'

export const skillApi = {
  list() {
    return request<AiSkill[]>({ url: '/system/skills', method: 'get' })
  },

  getDetail(id: string) {
    return request<AiSkill>({ url: `/system/skills/${id}`, method: 'get' })
  },

  upload(file: File, metadata: { skillId?: string; displayName?: string; sortOrder?: number }) {
    const formData = new FormData()
    formData.append('file', file)
    Object.entries(metadata).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') formData.append(key, String(value))
    })
    return request<AiSkillPreview>({
      url: '/system/skills/upload',
      method: 'post',
      data: formData,
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },

  delete(id: string) {
    return request<void>({ url: `/system/skills/${id}`, method: 'delete' })
  },

  listVersions(id: string) {
    return request<AiSkillVersion[]>({ url: `/system/skills/${id}/versions`, method: 'get' })
  },

  previewVersion(versionId: string) {
    return request<AiSkillPreview>({ url: `/system/skills/versions/${versionId}/preview`, method: 'get' })
  },

  listVersionFiles(versionId: string) {
    return request<AiSkillPreview['files']>({ url: `/system/skills/versions/${versionId}/files`, method: 'get' })
  },

  switchVersion(id: string, versionId: string) {
    const data: AiSkillSwitchVersionDTO = { versionId }
    return request<AiSkill>({ url: `/system/skills/${id}/switch-version`, method: 'post', data })
  },

  setEnabled(id: string, enabled: number) {
    return request<void>({ url: `/system/skills/${id}/enabled`, method: 'put', params: { enabled } })
  },

  refreshCache() {
    return request<void>({ url: '/system/skills/cache/refresh', method: 'post' })
  },

  download(id: string) {
    return request<Blob>({
      url: `/system/skills/${id}/download`,
      method: 'get',
      responseType: 'blob'
    })
  },

  downloadVersion(versionId: string) {
    return request<Blob>({
      url: `/system/skills/versions/${versionId}/download`,
      method: 'get',
      responseType: 'blob'
    })
  }
}
