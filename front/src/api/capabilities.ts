import { request } from './request'

export interface CapabilityBinding {
  id: string
  capabilityId: string
  versionId: string
  handlerKey: string
  consumer: 'MCP' | 'INTERNAL_AGENT'
  consumerCode: string
  name: string
  description: string
  schema: Record<string, unknown>
  enabled: boolean
  required: boolean
  contextMode: string
  projectRequired: boolean
  effect: 'READ' | 'WRITE'
  recoveryMode: string
}
export interface CapabilityRelease {
  id: string | null
  revision: string | number
  bindings: CapabilityBinding[]
  instructions: string
  rules: string
}
export interface CapabilityView {
  revision: string | number
  active: CapabilityRelease
  draft: CapabilityRelease
  releases: { id: string; revision: string | number; note: string; createTime: string }[]
  instanceId: string
  issues: { source: 'active' | 'draft'; bindingId: string; name: string; message: string }[]
  repairs: Record<string, Record<string, unknown>>
}
export const capabilitiesApi = {
  get: () => request<CapabilityView>({ url: '/system/ai-capabilities', method: 'get' }),
  save: (revision: string | number, draft: CapabilityRelease) => request<CapabilityView>({ url: '/system/ai-capabilities/draft', method: 'put',
    data: { revision, bindings: draft.bindings, instructions: draft.instructions, rules: draft.rules } }),
  publish: (revision: string | number, note: string) => request<CapabilityView>({ url: '/system/ai-capabilities/publish', method: 'post', data: { revision, note } }),
  rollback: (revision: string | number, releaseId: string) => request<CapabilityView>({ url: '/system/ai-capabilities/rollback', method: 'post', data: { revision, releaseId } }),
}
