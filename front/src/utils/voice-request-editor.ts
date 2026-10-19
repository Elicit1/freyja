export interface VoiceFormFields { instruction: string; text: string }
type Message = Record<string, unknown> & { role: 'user' | 'assistant'; content: string }

export function parseVoiceRequest(source: string): Record<string, unknown> {
  let parsed: unknown
  try { parsed = JSON.parse(source) } catch { throw new Error('JSON 格式有误，请检查引号、逗号和括号') }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('模型请求必须为 JSON 对象')
  return parsed as Record<string, unknown>
}

function formMessages(request: Record<string, unknown>): Message[] {
  const messages = request.messages
  const error = '这份请求包含普通表单无法表达的消息，请继续使用 JSON 编辑'
  if (!Array.isArray(messages) || messages.length < 1 || messages.length > 2) throw new Error(error)
  if (messages.some(m => !m || typeof m !== 'object' || Array.isArray(m)
    || !['user', 'assistant'].includes(m.role) || typeof m.content !== 'string')) throw new Error(error)
  const typed = messages as Message[]
  if (typed.at(-1)?.role !== 'assistant' || typed.filter(m => m.role === 'assistant').length !== 1
    || typed.filter(m => m.role === 'user').length > 1) throw new Error(error)
  return typed
}

export function readVoiceForm(request: Record<string, unknown>): VoiceFormFields {
  const messages = formMessages(request)
  return {
    instruction: messages.find(m => m.role === 'user')?.content || '',
    text: messages.find(m => m.role === 'assistant')!.content,
  }
}

/** Replace only the two edited contents, preserving all advanced parameters and message metadata. */
export function writeVoiceForm(request: Record<string, unknown>, fields: VoiceFormFields): Record<string, unknown> {
  const messages: Message[] = formMessages(request).map(m => ({ ...m, content: m.role === 'user' ? fields.instruction : fields.text }))
  if (!messages.some(m => m.role === 'user') && fields.instruction) messages.unshift({ role: 'user', content: fields.instruction })
  return { ...request, messages }
}

export function emptyVoiceRequest(): Record<string, unknown> {
  return { messages: [{ role: 'user', content: '' }, { role: 'assistant', content: '' }] }
}

export function formatVoiceRequest(source: string): string {
  return JSON.stringify(parseVoiceRequest(source), null, 2)
}
