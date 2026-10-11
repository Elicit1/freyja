export type AvatarState = 'IDLE' | 'THINKING' | 'WORKING' | 'SUCCESS' | 'ATTENTION' | 'ERROR'
export interface StateMedia { url: string; contentType: 'image/png' | 'image/webp' | 'image/gif'; objectPath?: string }
export interface AgentPet {
  id: string; displayName: string; description: string; spritesheetUrl: string; contentType: 'image/png' | 'image/webp'; objectPath?: string
  spriteVersionNumber: number; frameWidth: number; frameHeight: number; columns: number; rows: number
}
export interface AgentAvatar {
  visible: boolean
  pet: AgentPet
  stateMedia: Partial<Record<AvatarState, StateMedia>>
  notifications: { completed: boolean; failed: boolean; needsAttention: boolean }
}
export interface AgentConfig {
  agentCode: string; displayName: string; description: string; enabled: boolean
  revision: number; expectedRevision: number
  model: { providerId?: string | null; modelCode?: string | null; temperature?: number | null; topP?: number | null; maxTokens: number }
  instructions: { systemPrompt: string }
  tools: { names: string[] }
  skills: { names: string[]; dynamicLoading: boolean }
  memory: { summaryEnabled: boolean; recentMessages: number; contextTokens: number; scopes: string[] }
  runtime: { maxModelTurns: number; maxToolCalls: number; executionTimeoutSeconds: number; modelTimeoutSeconds: number; externalWaitTimeoutSeconds: number; maxRetries: number; maxConcurrentRuns: number }
  avatar: AgentAvatar
}
export interface AgentToolDescriptor {
  name: string; version: number; description: string; effect: 'READ' | 'WRITE'
  recoveryMode: string; projectRequired: boolean; inputSchema: string
}
export interface AgentCatalog {
  tools: AgentToolDescriptor[]; requiredTools: string[]
  skills: { name: string; displayName: string; description: string; versionId: string; version: string }[]
  fixedInstructions: string; defaults: AgentConfig; profileVersion: number
}
export interface ValidationResult {
  valid: boolean
  errors: { field: string; code: string; message: string }[]
  warnings: { field: string; code: string; message: string }[]
}
export interface AgentProfile { agentCode: string; displayName: string; description: string; avatar: AgentAvatar }
export interface AgentSession {
  id: string; agentCode: string; dramaId?: string; title: string; sessionType: string
  nextSeq: number; activeRunId?: string; updateTime?: string
}
export interface ToolResult { kind: string; data?: unknown; errorCode?: string; message?: string }
export interface AgentQuestion {
  id: string; header: string; question: string
  options: { label: string; description: string; recommended: boolean }[]
}
export interface AgentAnswer { questionId: string; optionLabel?: string; customAnswer?: string }
export interface AgentQuestionReply { questionStepId: string; answers: AgentAnswer[] }
export interface AgentMessage {
  id: string; sessionId: string; runId?: string; seq: number; messageType: string; eventType?: string; toolCallId?: string
  body: { content?: string; toolCalls?: { id: string; name: string; arguments: string }[]; toolName?: string; result?: ToolResult; status?: string; question?: string; questions?: AgentQuestion[]; questionStepId?: string; summary?: string; tool?: string; message?: string; hasError?: boolean }
  createTime?: string
}
export type AgentRunStatus = 'QUEUED' | 'RUNNING' | 'WAITING_TASK' | 'WAITING_USER' | 'CANCELLING' | 'COMPLETED' | 'PARTIAL_SUCCESS' | 'FAILED' | 'CANCELLED'
export interface AgentRun {
  id: string; sessionId: string; agentCode: string; runType: string; status: AgentRunStatus; objective: string
  modelTurns: number; toolCalls: number; executionMillis: string; promptTokens?: number; completionTokens?: number
  usageUnknown?: boolean; result?: { summary: string; status: string }; errorMessage?: string
  configRevision: number; createTime?: string; finishedAt?: string; readAt?: string
}
export interface AgentEvent { sessionId: string; runId?: string; seq: number; type: string; payload: AgentMessage }
export interface AgentSubmission { sessionId: string; messageId: string; runId: string }
export interface ProductionStart {
  clientRequestId: string; dramaId?: string; dramaTitle?: string; chapterTitle?: string; sourceText: string
  instruction?: string; aspectRatio: string; stylePreset?: string; styleTone?: string; pacingPreset: string
}
export interface ProductionShot {
  shotId: string; shotName: string; shotNo: number; generationMode: string; status: string; decisionReason: string; planRevision: number
}
export interface ProductionMaterial {
  requirementId: string; materialKey: string; sourceType: string; sourceId: string; characterId?: string
  name: string; description: string; prompt?: string; status: string; shotIds: string[]; purposes: string[]
}
export interface ProductionReport {
  runId: string; dramaId: string; dramaTitle: string; shotCount: number; plannedCount: number; readyCount: number; waitingImageCount: number
  complete: boolean; message: string; shots: ProductionShot[]; missingMaterials: ProductionMaterial[]
  assets: { type: string; id: string; characterId?: string; name: string; created: boolean; imageStatus: string }[]
}
export interface ProductionPlanView {
  shotId: string; expectedRevision: number; expectedFingerprint: string; preparationStatus: string; lockedFields: string[]
  plan?: { generationMode: string; decisionReason: string; characterBindings: unknown[]; visualAnchor?: unknown; propBindings: unknown[]; referenceSlots: unknown[]; firstFrameRequirement?: unknown; lastFrameRequirement?: unknown }
}
export const isActiveRun = (status?: string) => !!status && ['QUEUED', 'RUNNING', 'WAITING_TASK', 'WAITING_USER', 'CANCELLING'].includes(status)
export const agentStatusLabels: Record<string, string> = {
  QUEUED: '等待执行', RUNNING: '正在工作', WAITING_TASK: '等待任务结果', WAITING_USER: '需要你的回答',
  CANCELLING: '正在取消', COMPLETED: '已完成', PARTIAL_SUCCESS: '部分完成', FAILED: '执行失败', CANCELLED: '已取消'
}
