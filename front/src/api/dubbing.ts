import { request } from './request'
import type { PageResult } from '@/types/api'

export type ParameterValue = number | boolean | string | number[]
export interface Step { type: string; params: Record<string, ParameterValue>; enabled?: boolean }
export interface Effect { id: string; name: string; description?: string; stepsJson: string; version: number; enabled: number }
export interface Selection { effectId: string; steps?: Step[] }
export interface Snapshot extends Selection { name: string; version: number; steps: Step[] }
export interface FilterParameter {
  key: string; label: string; kind: 'number' | 'select' | 'boolean' | 'numberList'; unit?: string; description?: string;
  min?: number; max?: number; initial: ParameterValue; step?: number; advanced?: boolean; maxItems?: number;
  options?: { value: string; label: string }[]; option?: string;
}
export interface Filter { type: string; label: string; category: string; description: string; ffmpegFilter: string; parameters: FilterParameter[]; available: boolean; missingFilters?: string[] }
export interface VoiceAudio {
  id: string; characterId: string; baseAudioId?: string; kind: string; name: string; text: string; emotion?: string;
  voiceDesc?: string; rawAudioUrl?: string; rawDuration?: number; processedAudioUrl?: string; processedDuration?: number;
  modelRequestJson?: string;
  revision: number; status: string; errorMessage?: string; providerId?: string; modelCode?: string; taskId?: string; createTime?: string;
}
export interface AudioView { audio: VoiceAudio; effects: Snapshot[]; playbackUrl?: string; duration?: number; references: number }
export interface Character { id: string; name: string; drama_id?: string; drama_title?: string; has_base: number }
export interface Model { providerId: string; providerName: string; modelCode: string; modelName: string }
export interface CreateAudio { name: string; providerId: string; modelCode: string; method: 'DESIGN' | 'CLONE'; modelRequest: Record<string, unknown>; effects: Selection[]; replaceBaseId?: string }
async function effectOptions(): Promise<Effect[]> {
  const result: Effect[] = []
  for (let current = 1; ; current++) {
    const page = await request<PageResult<Effect>>({ url: '/dubbing/effects', params: { current, size: 100 } })
    result.push(...page.records)
    if (page.records.length < 100 || result.length >= Number(page.total)) return result
  }
}
export const dubbingApi = {
  effectOptions,
  pickerCharacters: (params: Record<string, unknown>) => request<PageResult<Character>>({ url: '/dubbing/picker/characters', params, paramsSerializer: { indexes: null } }),
  pickerBase: (id: string, params: Record<string, unknown>) => request<AudioView | null>({ url: `/dubbing/picker/characters/${id}/base`, params, paramsSerializer: { indexes: null } }),
  pickerAudios: (id: string, params: Record<string, unknown>) => request<PageResult<AudioView>>({ url: `/dubbing/picker/characters/${id}/audios`, params, paramsSerializer: { indexes: null } }),
  characters: (params: Record<string, unknown>) => request<{ page: PageResult<Character>; selectedId?: string }>({ url: '/dubbing/characters', params }),
  base: (id: string) => request<AudioView | null>({ url: `/dubbing/characters/${id}/base` }),
  audios: (id: string, params: Record<string, unknown>) => request<PageResult<AudioView>>({ url: `/dubbing/characters/${id}/audios`, params, paramsSerializer: { indexes: null } }),
  replacements: (id: string) => request<AudioView[]>({ url: `/dubbing/characters/${id}/replacements` }),
  detail: (id: string) => request<AudioView>({ url: `/dubbing/audios/${id}` }),
  create: (id: string, data: CreateAudio) => request<AudioView>({ url: `/dubbing/characters/${id}/audios`, method: 'post', data }),
  updateEffects: (id: string, revision: number, effects: Selection[]) => request<AudioView>({ url: `/dubbing/audios/${id}/effects`, method: 'put', data: { revision, effects } }),
  rename: (id: string, revision: number, name: string) => request<AudioView>({ url: `/dubbing/audios/${id}`, method: 'put', data: { revision, name } }),
  remove: (id: string, revision: number) => request<void>({ url: `/dubbing/audios/${id}`, method: 'delete', params: { revision } }),
  retry: (id: string, revision: number) => request<AudioView>({ url: `/dubbing/audios/${id}/retry`, method: 'post', params: { revision } }),
  upload: (id: string, data: FormData) => request<AudioView>({ url: `/dubbing/characters/${id}/upload-base`, method: 'post', data, headers: { 'Content-Type': 'multipart/form-data' }, timeout: 90000 }),
  effects: (params: Record<string, unknown>) => request<PageResult<Effect>>({ url: '/dubbing/effects', params }),
  saveEffect: (data: { id?: string; name: string; description?: string; steps: Step[]; enabled: boolean; version?: number }) => request<Effect>({ url: '/dubbing/effects', method: 'post', data }),
  filters: () => request<Filter[]>({ url: '/dubbing/effects/filters' }),
  models: () => request<Model[]>({ url: '/dubbing/models' }),
  preview: (data: FormData) => request<{ audioUrl: string; duration: number }>({ url: '/dubbing/effects/preview', method: 'post', data, headers: { 'Content-Type': 'multipart/form-data' }, timeout: 90000 })
}
