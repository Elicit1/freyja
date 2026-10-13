import { request } from './request'

export interface McpToolInfo {
  name: string
  description: string
  effect: 'READ' | 'WRITE'
  enabled: boolean
  published: boolean
  inputSchema: Record<string, unknown>
}
export interface McpSettings {
  recordTypes: string[]
  crudCapabilities: McpCrudCapability[]
  revision: number
  enabled: boolean
  writeEnabled: boolean
  allowAllProjects: boolean
  allowCreateProjects: boolean
  allowedProjectIds: string[]
  allowedOrigins: string[]
  tokenConfigured: boolean
  endpoint: string
  protocol: string
  tools: McpToolInfo[]
}
export interface McpCrudCapability {
  type: string
  label: string
  read: string
  create: string
  update: string
  delete: string
  batchUpdate: string
}
export interface McpSettingsUpdate {
  revision: number
  enabled: boolean
  writeEnabled: boolean
  allowAllProjects: boolean
  allowCreateProjects: boolean
  allowedProjectIds: string[]
  allowedOrigins: string[]
  token?: string
}
export const mcpApi = {
  get: () => request<McpSettings>({ url: '/system/mcp', method: 'get' }),
  save: (data: McpSettingsUpdate) => request<McpSettings>({ url: '/system/mcp', method: 'put', data }),
  enabled: (revision: number, enabled: boolean) => request<McpSettings>({ url: '/system/mcp/enabled', method: 'put', data: { revision, enabled } }),
  tool: (name: string, revision: number, enabled: boolean) => request<McpSettings>({ url: `/system/mcp/tools/${encodeURIComponent(name)}`, method: 'put', data: { revision, enabled } })
}
