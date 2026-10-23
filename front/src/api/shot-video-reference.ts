import { request } from './request'
import type { ShotRefVideo, ShotVideoModelOption, VideoReferencePreflight } from '@/types/drama'
import type { VideoProbeInfoVO } from './videoProcessing'

export const shotVideoReferenceApi = {
  probe(shotId: string, source: ShotRefVideo) {
    return request<VideoProbeInfoVO>({ url: `/drama/shot/${shotId}/video-reference-probe`, method: 'post', data: source, timeout: 180000 })
  },
  options(providerId: string) {
    return request<ShotVideoModelOption[]>({ url: '/drama/shot/video-model-options', method: 'get', params: { providerId } })
  },
  prepare(shotId: string, providerId: string, modelCode: string) {
    return request<VideoReferencePreflight>({ url: `/drama/shot/${shotId}/video-reference-preflight`, method: 'post', data: { providerId, workflowTemplateId: modelCode } })
  },
  get(shotId: string, preflightId: string) {
    return request<VideoReferencePreflight>({ url: `/drama/shot/${shotId}/video-reference-preflight/${preflightId}`, method: 'get' })
  }
}
