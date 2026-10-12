<template>
  <section v-if="plan || tools.length || active" class="task-progress" aria-label="任务执行进度">
    <div class="progress-heading">
      <strong>{{ plan ? '任务计划' : '执行进度' }}</strong>
      <span v-if="plan" class="progress-count">{{ completed }}/{{ plan.steps.length }} 已完成</span>
    </div>
    <p v-if="plan?.explanation" class="progress-explanation">{{ plan.explanation }}</p>
    <ol v-if="plan" class="plan-steps">
      <li v-for="step in plan.steps" :key="step.id" :class="step.status.toLowerCase()">
        <span class="step-mark" aria-hidden="true">{{ step.status === 'COMPLETED' ? '✓' : step.status === 'SKIPPED' ? '−' : step.status === 'IN_PROGRESS' ? '●' : '○' }}</span>
        <span class="step-title">{{ step.title }}</span>
        <span class="step-status">{{ planLabels[step.status] }}</span>
      </li>
    </ol>
    <p v-if="active" class="current-operation" role="status">
      <span class="activity-dot" aria-hidden="true" />{{ currentOperation }}
    </p>
    <div v-if="taskFeeds.length" class="task-feeds">
      <div v-for="feed in taskFeeds" :key="feed.id" class="task-feed">
        <div class="feed-heading">
          <strong>{{ feed.title }}</strong>
          <span v-if="Object.keys(feed.activity.workers).length">分段 {{ successfulWorkers(feed.activity) }}/{{ Object.keys(feed.activity.workers).length }}</span>
        </div>
        <p v-if="!feed.activity.logs.length" class="feed-placeholder">{{ active ? '已启动，正在接收执行进度…' : '子任务已结束，暂无可回放的过程记录' }}</p>
        <div v-else class="feed-lines" aria-live="off">
          <p v-for="line in feed.activity.logs.slice(-6)" :key="line.seq" :class="{ 'activity-error': line.error }">{{ line.text }}</p>
        </div>
        <details v-if="feed.activity.logs.length > 6" class="activity-history">
          <summary>查看过程记录（{{ feed.activity.logs.length }}）</summary>
          <div class="feed-lines full-history"><p v-for="line in feed.activity.logs" :key="line.seq" :class="{ 'activity-error': line.error }">{{ line.text }}</p></div>
        </details>
      </div>
    </div>
    <details v-if="tools.length" class="operation-history">
      <summary>操作记录（{{ tools.length }}）</summary>
      <ol><li v-for="tool in tools" :key="tool.stepId"><span>{{ agentToolLabel(tool.name) }}</span><span>{{ toolLabels[tool.status] || tool.status }}</span></li></ol>
    </details>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { agentToolLabel } from './agentProgress'
import { isActiveRun, type AgentRun, type AgentTaskActivity } from '@/types/agent'
const props = defineProps<{ run: AgentRun; activities?: Record<string, AgentTaskActivity> }>()
const plan = computed(() => props.run.progress?.plan)
const tools = computed(() => (props.run.progress?.tools || []).filter(tool => tool.name !== 'update_task_plan'))
const active = computed(() => isActiveRun(props.run.status))
const completed = computed(() => plan.value?.steps.filter(step => step.status === 'COMPLETED').length || 0)
const planLabels = { PENDING: '待执行', IN_PROGRESS: '进行中', COMPLETED: '已完成', SKIPPED: '已跳过' }
const toolLabels: Record<string, string> = { PENDING: '待执行', RUNNING: '进行中', WAITING_EXTERNAL: '执行中', WAITING_USER: '需要回答', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已停止' }
const currentOperation = computed(() => {
  if (props.run.status === 'WAITING_USER') return '需要你的回答，之后继续当前计划'
  if (props.run.status === 'BLOCKED') return '历史压缩需要调整配置，已完成的进度仍保留'
  if (props.run.status === 'QUEUED') return '目标已接收，准备执行'
  if (props.run.status === 'CANCELLING') return '正在停止后续操作'
  const tool = [...tools.value].reverse().find(item => ['RUNNING', 'WAITING_EXTERNAL'].includes(item.status))
  if (tool) return `${agentToolLabel(tool.name)} · 进行中`
  const step = plan.value?.steps.find(item => item.status === 'IN_PROGRESS')
  return step ? `${step.title} · 正在处理` : plan.value ? '正在整理结果并推进计划' : '正在分析目标并准备执行'
})
const taskFeeds = computed(() => {
  const seen = new Set<string>()
  return tools.value.flatMap(tool => {
    const id = tool.eventTaskId || tool.taskId
    if (!id || seen.has(id) || !props.activities?.[id]) return []
    seen.add(id)
    return [{ id, title: agentToolLabel(tool.name), activity: props.activities[id]! }]
  })
})
function successfulWorkers(activity: AgentTaskActivity) { return Object.values(activity.workers).filter(worker => ['SUCCESS', 'SUCCEEDED'].includes(worker.status)).length }
</script>
<style scoped>
.task-progress { margin-top: 12px; padding: 14px; border-radius: 10px; background: var(--surface); border: 1px solid var(--border-default); }
.progress-heading, .feed-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.progress-count, .feed-heading > span { font-size: 11px; color: var(--text-secondary); }
.progress-explanation { margin: 8px 0; color: var(--text-secondary); white-space: pre-wrap; overflow-wrap: anywhere; }
.plan-steps { list-style: none; padding: 0; margin: 12px 0; display: grid; gap: 9px; }
.plan-steps li { display: flex; align-items: flex-start; gap: 9px; color: var(--text-secondary); }
.step-mark { width: 16px; flex: none; }
.step-title { flex: 1; overflow-wrap: anywhere; }
.step-status { font-size: 11px; flex: none; }
.plan-steps .in_progress { color: var(--brand-primary, #8b5cf6); font-weight: 600; }
.plan-steps .completed .step-mark { color: var(--success, #22a06b); }
.plan-steps .skipped .step-title { text-decoration: line-through; }
.current-operation { display: flex; align-items: center; gap: 8px; margin-top: 10px; color: var(--text-secondary); }
.activity-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--brand-primary, #8b5cf6); flex: none; }
.task-feeds { display: grid; gap: 12px; margin-top: 14px; }
.task-feed { border-top: 1px solid var(--border-default); padding-top: 10px; }
.feed-heading { margin-bottom: 8px; font-size: 12px; }
.feed-lines, .feed-placeholder { font-size: 12px; color: var(--text-secondary); }
.feed-lines p { margin: 6px 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.6; }
.activity-error { color: var(--danger); }
.activity-history, .operation-history { margin-top: 10px; font-size: 11px; color: var(--text-secondary); }
summary { cursor: pointer; }
.full-history { max-height: 300px; overflow-y: auto; }
.operation-history ol { list-style: none; margin: 10px 0 0; padding: 0; display: grid; gap: 7px; }
.operation-history li { display: flex; justify-content: space-between; gap: 12px; }
</style>
