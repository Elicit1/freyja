import { request } from './request'
import type { ContinuationDraft, ContinuationPlan, ContinuationPreset } from '@/types/continuation'
import type { ShotRefVideo } from '@/types/drama'
export interface ContinuationPreparationState { id: string; status: string; errorMessage?: string; progressMessage?: string }
export const continuationApi = {
  plan: (id: string, draft: ContinuationDraft, auto = false) => request<ContinuationPlan>({ url: `/drama/shot/${id}/continuation/plan`, method: 'post', data: { draft, auto } }),
  presets: (id: string) => request<ContinuationPreset[]>({ url: `/drama/shot/${id}/continuation/presets`, method: 'get' }),
  splitReference: (id: string, draft: ContinuationDraft, referenceVideo: ShotRefVideo, targetSegmentIds?: string[]) => request<ContinuationPlan>({ url: `/drama/shot/${id}/continuation/split-reference`, method: 'post', data: { draft, referenceVideo, targetSegmentIds }, timeout: 180000 }),
  prepare: (id: string, providerId: string, modelCode: string, size: string, planHash: string) => request<ContinuationPreparationState>({ url: `/drama/shot/${id}/continuation/prepare`, method: 'post', data: { providerId, modelCode, size, planHash } }),
  preparation: (id: string, preparationId: string) => request<ContinuationPreparationState>({ url: `/drama/shot/${id}/continuation/preparation/${preparationId}`, method: 'get' }),
  get: (id: string) => request<ContinuationDraft>({ url: `/drama/shot/${id}/continuation`, method: 'get' }),
  save: (id: string, draft: ContinuationDraft) => request<void>({ url: `/drama/shot/${id}/continuation`, method: 'put', data: draft }),
  preview: (id: string, draft: ContinuationDraft, auto: boolean, providerId: string, modelCode: string) =>
    request<ContinuationPlan>({ url: `/drama/shot/${id}/continuation/preview`, method: 'post', data: { draft, auto, providerId, modelCode }, timeout: 120000 })
}
