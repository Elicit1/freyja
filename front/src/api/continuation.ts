import { request } from './request'
import type { ContinuationDraft, ContinuationPlan } from '@/types/continuation'
export const continuationApi = {
  prepare: (id: string, providerId: string, modelCode: string, size: string, planHash: string) => request<{ id: string; status: string; errorMessage?: string }>({ url: `/drama/shot/${id}/continuation/prepare`, method: 'post', data: { providerId, modelCode, size, planHash } }),
  preparation: (id: string, preparationId: string) => request<{ id: string; status: string; errorMessage?: string }>({ url: `/drama/shot/${id}/continuation/preparation/${preparationId}`, method: 'get' }),
  get: (id: string) => request<ContinuationDraft>({ url: `/drama/shot/${id}/continuation`, method: 'get' }),
  save: (id: string, draft: ContinuationDraft) => request<void>({ url: `/drama/shot/${id}/continuation`, method: 'put', data: draft }),
  preview: (id: string, draft: ContinuationDraft, auto: boolean, providerId: string, modelCode: string) =>
    request<ContinuationPlan>({ url: `/drama/shot/${id}/continuation/preview`, method: 'post', data: { draft, auto, providerId, modelCode }, timeout: 120000 })
}
