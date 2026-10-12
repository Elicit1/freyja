import type { PromptEvent } from '@/store/taskCenter'
import type { AgentMessage, AgentRun, AgentTaskActivity } from '@/types/agent'

export const agentToolNames: Record<string, string> = {
  update_task_plan: '更新任务计划', request_user_input: '确认需求',
  prepare_script_decomposition: '保存剧本拆解要求', start_script_decomposition: '拆解小说', retry_decomposition_segment: '重试失败段落',
  commit_decomposition: '保存拆解结果', list_preparation_shots: '读取制作分镜', get_reference_candidates: '盘点参考素材',
  ensure_text_assets: '保存文字资产', save_shot_production_plans: '应用分镜制作方案', prepare_shot_prompts: '准备分镜提示词',
  inspect_preparation: '检查并保存交付报告', get_preparation_report: '读取交付报告', get_project_overview: '读取项目概要',
  list_project_assets: '查询资产', get_shot_detail: '查询分镜', save_session_note: '保存明确规则', list_session_notes: '读取规则',
  load_skill: '加载技能', read_skill_file: '读取技能参考'
}
export function agentToolLabel(name?: string) { return name ? agentToolNames[name] || name : '工具操作' }

export function applyAgentProgress(run: AgentRun, message: AgentMessage) {
  if (message.messageType !== 'EVENT') return
  const body = message.body
  if (message.eventType === 'PLAN_UPDATED' && body.plan) {
    run.progress ||= { tools: [] }
    run.progress.plan = body.plan
  }
  if (!body.stepId || !body.tool || !['TOOL_STARTED', 'WAITING_TASK', 'TOOL_COMPLETED'].includes(message.eventType || '')) return
  run.progress ||= { tools: [] }
  let tool = run.progress.tools.find(t => t.stepId === body.stepId)
  if (!tool) { tool = { stepId: body.stepId, name: body.tool, status: 'PENDING' }; run.progress.tools.push(tool) }
  // Historical start/wait events cannot undo a more recent durable checkpoint.
  if (message.eventType !== 'TOOL_COMPLETED' && ['SUCCEEDED', 'FAILED', 'CANCELLED', 'WAITING_USER'].includes(tool.status)) return
  tool.status = message.eventType === 'WAITING_TASK' ? 'WAITING_EXTERNAL' : message.eventType === 'TOOL_COMPLETED' ? body.hasError ? 'FAILED' : 'SUCCEEDED' : 'RUNNING'
  if (body.taskId) tool.taskId = body.taskId
  if (body.eventTaskId) tool.eventTaskId = body.eventTaskId
}

/** Only public stages and structured execution status enter the activity feed. */
export function applyTaskActivity(activity: AgentTaskActivity, event: PromptEvent) {
  if (event.seq <= activity.afterSeq) return
  activity.afterSeq = event.seq
  let text: string | undefined
  let error = false
  if (event.type === 'stage') text = event.data
  if (event.type === 'error' || event.type === 'cancelled') { text = event.data; error = event.type === 'error' }
  if (event.type === 'done') activity.finished = true
  try {
    if (event.type === 'segments_init') {
      const data = JSON.parse(event.data)
      if (Array.isArray(data)) {
        data.forEach(segment => { if (typeof segment?.id === 'string') activity.workers[segment.id] ||= { status: 'PENDING' } })
        text = `剧情分段完成，共 ${data.length} 段，开始逐段生成分镜`
      }
    }
    if (event.type === 'channel_chunk') {
      const data = JSON.parse(event.data)
      if (data.channel === 'ALL' && typeof data.chunk === 'string') text = data.chunk
    }
    if (event.type === 'worker_status') {
      const data = JSON.parse(event.data)
      if (typeof data.channel === 'string' && typeof data.status === 'string') {
        activity.workers[data.channel] = { status: data.status, shotsCount: data.shotsCount }
        const status = ({ PENDING: '待执行', RUNNING: '正在生成分镜', SUCCESS: '已完成', SUCCEEDED: '已完成', FAILED: '执行失败', CANCELLED: '已取消' } as Record<string, string>)[data.status] || data.status
        text = `${data.channel} · ${status}${typeof data.shotsCount === 'number' ? ` · ${data.shotsCount} 个镜头` : ''}`
        error = data.status === 'FAILED'
      }
    }
    if (event.type === 'normalizer_status') {
      const data = JSON.parse(event.data)
      const status = ({ RUNNING: '正在整理故事结构', SUCCESS: '故事结构整理完成', SUCCEEDED: '故事结构整理完成', FAILED: '故事结构整理失败', SKIPPED: '已跳过故事结构整理' } as Record<string, string>)[data.status]
      if (typeof data.channel === 'string' && status) { text = `${data.channel} · ${status}`; error = data.status === 'FAILED' }
    }
  } catch { /* A malformed optional event must not stop later progress. */ }
  if (!text?.trim()) return
  text = text.trim().slice(0, 4000)
  if (activity.logs.at(-1)?.text === text) return
  activity.logs.push({ seq: event.seq, text, error })
  if (activity.logs.length > 200) activity.logs.splice(0, activity.logs.length - 200)
}
