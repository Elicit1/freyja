import { request } from './request'
import type { AgentConfig, AgentCatalog, ValidationResult, AgentProfile, AgentSession, AgentMessage, AgentRun, AgentSubmission, AgentPet } from '@/types/agent'
import type { ProductionStart, ProductionReport, ProductionPlanView, AgentQuestionReply } from '@/types/agent'

export const agentConfigApi = {
  list: () => request<AgentConfig[]>({ url: '/system/agent/configs', method: 'get' }),
  get: (code: string) => request<AgentConfig>({ url: `/system/agent/configs/${code}`, method: 'get' }),
  catalog: (code: string) => request<AgentCatalog>({ url: `/system/agent/configs/${code}/catalog`, method: 'get' }),
  save: (code: string, config: AgentConfig) => request<AgentConfig>({ url: `/system/agent/configs/${code}`, method: 'put', data: config }),
  validate: (code: string, config: AgentConfig) => request<ValidationResult>({ url: `/system/agent/configs/${code}/validate`, method: 'post', data: config }),
  test: (code: string, config: AgentConfig, content: string, clientRequestId: string, dramaId?: string) =>
    request<AgentSubmission>({ url: `/system/agent/configs/${code}/test-runs`, method: 'post', data: { config, content, clientRequestId, dramaId: dramaId || undefined } }),
  uploadAvatar: (file: File) => {
    const data = new FormData(); data.append('file', file)
    return request<AgentPet>({
      url: '/system/agent/avatar/upload', method: 'post', data, headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}
export const agentApi = {
  startProduction: (data: ProductionStart) => request<AgentSubmission>({ url: '/agent/production/start', method: 'post', data }),
  productionReport: (id: string) => request<ProductionReport | null>({ url: `/agent/production/runs/${id}/report`, method: 'get' }),
  productionPlan: (id: string) => request<ProductionPlanView>({ url: `/agent/production/shots/${id}/plan`, method: 'get' }),
  refreshProduction: (dramaId: string, shotIds: string[]) => request<ProductionPlanView[]>({ url: '/agent/production/refresh', method: 'post', data: { dramaId, shotIds } }),
  lockProduction: (id: string, expectedRevision: number, fields: string[]) => request<ProductionPlanView>({ url: `/agent/production/shots/${id}/locks`, method: 'put', data: { expectedRevision, fields } }),
  profiles: () => request<AgentProfile[]>({ url: '/agent/profiles', method: 'get' }),
  sessions: () => request<AgentSession[]>({ url: '/agent/sessions', method: 'get' }),
  session: (id: string) => request<AgentSession>({ url: `/agent/sessions/${id}`, method: 'get' }),
  createSession: (agentCode: string, dramaId?: string, title?: string) => request<AgentSession>({ url: '/agent/sessions', method: 'post', data: { agentCode, dramaId: dramaId || undefined, title } }),
  messages: (id: string, afterSeq = 0) => request<AgentMessage[]>({ url: `/agent/sessions/${id}/messages`, method: 'get', params: { afterSeq } }),
  send: (id: string, content: string, intent: 'START_RUN' | 'REPLACE_RUN' | 'STEER' | 'ANSWER', clientRequestId: string, expectedRunId?: string, reply?: AgentQuestionReply) =>
    request<AgentSubmission>({ url: `/agent/sessions/${id}/messages`, method: 'post', data: { content, intent, clientRequestId, expectedRunId, ...reply } }),
  run: (id: string) => request<AgentRun>({ url: `/agent/runs/${id}`, method: 'get' }),
  notifications: () => request<AgentRun[]>({ url: '/agent/notifications', method: 'get' }),
  cancel: (id: string) => request<void>({ url: `/agent/runs/${id}/cancel`, method: 'post' }),
  resume: (id: string) => request<void>({ url: `/agent/runs/${id}/resume`, method: 'post' }),
  read: (id: string) => request<void>({ url: `/agent/runs/${id}/read`, method: 'post' })
}
