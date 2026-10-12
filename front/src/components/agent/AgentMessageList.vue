<template>
  <div class="agent-messages">
    <div v-for="message in messages" :key="message.id" class="message-row" :class="message.messageType.toLowerCase()">
      <template v-if="message.messageType === 'USER' || message.messageType === 'ASSISTANT'">
        <span class="message-author">{{ message.messageType === 'USER' ? '你' : 'Freyja agent' }}</span>
        <p v-if="message.body.content" class="message-content">{{ message.body.content }}</p>
        <span v-if="message.body.toolCalls?.length" class="text-xs text-[var(--text-secondary)]">准备执行 {{ message.body.toolCalls.length }} 项操作</span>
      </template>
      <template v-else-if="message.messageType === 'TOOL_RESULT'">
        <span class="text-xs" :class="message.body.result?.errorCode ? 'text-[var(--danger)]' : 'text-[var(--text-secondary)]'">
          {{ toolLabel(message.body.toolName) }} · {{ message.body.result?.errorCode ? message.body.result.message : '已完成' }}
        </span>
        <details v-if="message.body.result?.data" class="tool-details">
          <summary>查看操作结果</summary>
          <pre>{{ JSON.stringify(message.body.result.data, null, 2) }}</pre>
        </details>
      </template>
      <span v-else-if="eventLabel(message)" class="event-line">{{ eventLabel(message) }}</span>
    </div>
    <div v-if="preview?.text" class="message-row assistant stream-preview" aria-live="off">
      <span class="message-author">Freyja agent · 正在回复</span>
      <p class="message-content">{{ preview.text }}<span class="stream-cursor" aria-hidden="true">▍</span></p>
    </div>
  </div>
</template>
<script setup lang="ts">
import type { AgentMessage, AgentStreamEvent } from '@/types/agent'
defineProps<{ messages: AgentMessage[]; preview?: AgentStreamEvent }>()
const toolNames: Record<string, string> = {
  prepare_script_decomposition: '保存剧本拆解要求', start_script_decomposition: '拆解小说', commit_decomposition: '保存拆解结果', list_preparation_shots: '读取制作分镜',
  get_reference_candidates: '盘点参考素材', ensure_text_assets: '保存文字资产', save_shot_production_plans: '应用分镜制作方案',
  prepare_shot_prompts: '准备分镜提示词', inspect_preparation: '检查并保存交付报告', get_preparation_report: '读取交付报告',
  get_project_overview: '读取项目概要', list_project_assets: '查询资产', get_shot_detail: '查询分镜',
  save_session_note: '保存明确规则', list_session_notes: '读取规则', load_skill: '加载技能', read_skill_file: '读取技能参考', request_user_input: '确认需求'
}
function toolLabel(name?: string) { return name ? toolNames[name] || name : '工具操作' }
function eventLabel(message: AgentMessage) {
  const labels: Record<string, string> = {
    RUN_QUEUED: '目标已接收，等待执行', RUN_STARTED: '开始处理', MODEL_STARTED: '正在思考…',
    WAITING_TASK: '等待任务结果…', RUN_COMPLETED: '本次任务已完成', RUN_CANCELLED: '已取消后续执行'
  }
  if (message.eventType === 'WAITING_USER') return message.body.questions?.map(q => q.question).join('\n') || message.body.question || '需要你的回答'
  if (message.eventType === 'MODEL_RETRY') return message.body.message || '模型请求重试中'
  if (message.eventType === 'RUN_FAILED') return message.body.summary || '任务失败'
  if (message.eventType === 'TOOL_STARTED') return toolLabel(message.body.tool) + '…'
  return message.eventType ? labels[message.eventType] : undefined
}
</script>
<style scoped>
.agent-messages { display: flex; flex-direction: column; gap: 12px; }
.message-row { max-width: 100%; font-size: 13px; }
.message-row.user, .message-row.assistant { padding: 14px; border-radius: 12px; border: 1px solid var(--border-default); background: var(--surface); }
.message-row.user { background: var(--brand-soft); margin-left: 24px; }
.message-author { font-size: 11px; color: var(--text-secondary); }
.message-content { white-space: pre-wrap; overflow-wrap: anywhere; margin-top: 6px; line-height: 1.7; }
.event-line { font-size: 11px; color: var(--text-secondary); white-space: pre-wrap; }
.tool-details { margin-top: 5px; font-size: 11px; color: var(--text-secondary); }
.tool-details summary { cursor: pointer; }
.tool-details pre { max-height: 260px; overflow: auto; padding: 10px; white-space: pre-wrap; overflow-wrap: anywhere; }
.message-row.system { display: none; }
.stream-cursor { color: var(--text-secondary); animation: stream-blink 1s step-end infinite; }
@keyframes stream-blink { 50% { opacity: 0; } }
@media (prefers-reduced-motion: reduce) { .stream-cursor { animation: none; } }
</style>
