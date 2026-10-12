export interface ChatCapabilities {
  contextWindow?: number; maxOutputTokens?: number; recommendedOutputTokens?: number
  reasoningProtocol?: 'NONE' | 'DEEPSEEK_CHAT' | 'OPENAI' | 'OLLAMA'; reasoningEfforts?: string[]; toolCalling?: boolean
  outputTokenParameter?: 'max_tokens' | 'max_completion_tokens'
}
export function modelCapabilities(model?: { paramsJson?: string }): ChatCapabilities {
  try { return JSON.parse(model?.paramsJson || '{}').capabilities || {} } catch { return {} }
}
export const reasoningModes = [
  { value: 'INHERIT', label: '跟随模型' }, { value: 'DISABLED', label: '关闭思考' },
  { value: 'LOW', label: '低' }, { value: 'MEDIUM', label: '中' }, { value: 'HIGH', label: '高' }, { value: 'MAX', label: '最高' }
]
export function supportsReasoning(mode: string, caps: ChatCapabilities) {
  return mode === 'INHERIT' || mode === 'DISABLED' || !caps.reasoningEfforts?.length || caps.reasoningEfforts.includes(mode.toLowerCase())
}
