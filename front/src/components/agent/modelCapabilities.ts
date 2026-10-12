export interface ChatCapabilities {
  contextWindow?: number; maxOutputTokens?: number; recommendedOutputTokens?: number
  reasoningProtocol?: 'NONE' | 'DEEPSEEK_CHAT' | 'OPENAI' | 'OLLAMA' | 'MIMO_CHAT'; reasoningEfforts?: string[]; toolCalling?: boolean
  outputTokenParameter?: 'max_tokens' | 'max_completion_tokens'
}
export function providerCapabilities(provider?: { chatCapabilitiesJson?: string } | null): ChatCapabilities {
  try { const value = JSON.parse(provider?.chatCapabilitiesJson || '{}'); return value && typeof value === 'object' && !Array.isArray(value) ? value : {} } catch { return {} }
}
export function modelCapabilities(model?: { paramsJson?: string }, provider?: { chatCapabilitiesJson?: string } | null): ChatCapabilities {
  const defaults = providerCapabilities(provider)
  try { const overrides = JSON.parse(model?.paramsJson || '{}').capabilities || {}; return { ...defaults, ...Object.fromEntries(Object.entries(overrides).filter(([, value]) => value != null)) } } catch { return defaults }
}
export const reasoningProtocols = [
  { value: 'NONE', label: '未声明 / 不控制思考' }, { value: 'DEEPSEEK_CHAT', label: 'DeepSeek Chat' },
  { value: 'OPENAI', label: 'OpenAI reasoning_effort' }, { value: 'OLLAMA', label: 'Ollama think' },
  { value: 'MIMO_CHAT', label: 'MiMo thinking' }
]
export const reasoningModes = [
  { value: 'INHERIT', label: '跟随模型' }, { value: 'DISABLED', label: '关闭思考' },
  { value: 'ENABLED', label: '开启思考' },
  { value: 'LOW', label: '低' }, { value: 'MEDIUM', label: '中' }, { value: 'HIGH', label: '高' }, { value: 'MAX', label: '最高' }
]
export function supportsReasoning(mode: string, caps: ChatCapabilities) {
  if (mode === 'INHERIT' || mode === 'DISABLED') return true
  if (mode === 'ENABLED') return ['DEEPSEEK_CHAT', 'OLLAMA', 'MIMO_CHAT'].includes(caps.reasoningProtocol || '')
  if (caps.reasoningProtocol === 'MIMO_CHAT') return false
  return !caps.reasoningEfforts?.length || caps.reasoningEfforts.includes(mode.toLowerCase())
}
