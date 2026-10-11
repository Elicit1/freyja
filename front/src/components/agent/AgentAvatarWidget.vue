<template>
  <div ref="widget" class="agent-widget" :style="positionStyle">
    <div v-if="bubble && !dismissed.has(bubble.id)" class="agent-bubble">
      <button class="bubble-content" @click="openBubble">{{ bubble.text }}</button>
      <button class="bubble-close" aria-label="收起提醒" @click="dismiss">×</button>
    </div>
    <el-badge :value="store.unreadCount" :hidden="!store.unreadCount">
      <button class="agent-launcher" :class="{ compact: !avatarExpanded }"
        :aria-label="store.visible ? '关闭 Agent 聊天' : '打开 Agent 聊天'" :aria-expanded="store.visible" @pointerdown="startDrag" @click="openChat">
        <el-icon v-if="!avatarExpanded"><ChatDotRound /></el-icon>
        <AgentAvatarMedia v-else :avatar="store.currentProfile?.avatar" :state="displayState" :animation-row="dragRow" :label="store.currentProfile?.displayName" />
      </button>
    </el-badge>
    <button v-if="store.currentProfile?.avatar.visible" class="widget-toggle" @click="collapsed = !collapsed" :aria-label="collapsed ? '展开形象' : '收起形象'">{{ collapsed ? '展开' : '收起' }}</button>
  </div>
  <AgentChatDrawer />
</template>
<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import { useAgentStore } from '@/store/agent'
import { agentApi } from '@/api/agent'
import type { AvatarState } from '@/types/agent'
import AgentAvatarMedia from './AgentAvatarMedia.vue'
import AgentChatDrawer from './AgentChatDrawer.vue'
const store = useAgentStore()
const widget = ref<HTMLElement>()
const collapsed = computed({ get: () => store.avatarCollapsed, set: value => { store.avatarCollapsed = value } })
const avatarExpanded = computed(() => !!store.currentProfile?.avatar.visible && !collapsed.value)
function readDismissed(): string[] {
  try { const value = JSON.parse(localStorage.getItem('freyja-agent-dismissed') || '[]'); return Array.isArray(value) ? value.filter((item: unknown) => typeof item === 'string') : [] }
  catch { return [] }
}
const dismissed = ref(new Set<string>(readDismissed()))
const position = ref<{ x: number; y: number }>()
let moved = false
const dragRow = ref<number>()
let cleanupDrag: (() => void) | undefined
const waiting = computed(() => Object.values(store.runs).find(r => r.status === 'WAITING_USER'))
const missingCounts = ref<Record<string, number | null>>({})
watch(() => store.notifications.filter(r => r.status === 'COMPLETED').map(r => r.id).join(','), async () => {
  await Promise.allSettled(store.notifications.filter(r => r.status === 'COMPLETED' && missingCounts.value[r.id] === undefined).map(async r => {
    const report = await agentApi.productionReport(r.id)
    missingCounts.value[r.id] = report ? report.missingMaterials.length : null
  }))
}, { immediate: true })
const bubble = computed(() => {
  if (waiting.value && store.currentProfile?.avatar.notifications.needsAttention)
    return { id: waiting.value.id + '/waiting', runId: waiting.value.id, sessionId: waiting.value.sessionId, text: '需要你的回答，点击继续', state: 'ATTENTION' as AvatarState }
  const notice = store.notifications.find(run => run.status !== 'CANCELLED' && !dismissed.value.has(run.id + '/' + run.finishedAt))
  if (!notice) return undefined
  const avatar = store.profiles.find(p => p.agentCode === notice.agentCode)?.avatar || store.currentProfile?.avatar
  if (notice.status === 'FAILED' && !avatar?.notifications.failed) return undefined
  if (notice.status === 'COMPLETED' && !avatar?.notifications.completed) return undefined
  const missing = missingCounts.value[notice.id]
  const attention = typeof missing === 'number' && missing > 0
  return { id: notice.id + '/' + notice.finishedAt, runId: notice.id, sessionId: notice.sessionId,
    text: notice.status === 'FAILED' ? '任务遇到问题，点击查看' : attention ? `分镜规划已完成，${missing} 项资产需要补图` : missing === 0 ? '分镜准备完成，点击查看' : '任务已完成，点击查看结果',
    state: (notice.status === 'FAILED' ? 'ERROR' : attention ? 'ATTENTION' : 'SUCCESS') as AvatarState }
})
const displayState = computed<AvatarState>(() => {
  if (waiting.value) return 'ATTENTION'
  if (bubble.value) return bubble.value.state
  const active = Object.values(store.runs).filter(r => ['QUEUED', 'RUNNING', 'WAITING_TASK'].includes(r.status))
  if (!active.length) return 'IDLE'
  if (active.some(r => r.status === 'WAITING_TASK')) return 'WORKING'
  if (store.phase === 'THINKING' || store.phase === 'WORKING') return store.phase
  return active.every(r => r.status === 'QUEUED') ? 'THINKING' : 'WORKING'
})
const positionStyle = computed(() => position.value ? { left: position.value.x + 'px', top: position.value.y + 'px' } : { right: '90px', bottom: '34px' })
function dismiss() {
  if (bubble.value) dismissed.value = new Set([...dismissed.value, bubble.value.id])
  localStorage.setItem('freyja-agent-dismissed', JSON.stringify([...dismissed.value].slice(-200)))
}
function openChat() { if (moved) { moved = false; return }; if (store.visible) store.visible = false; else void store.open() }
function openBubble() { if (bubble.value) void store.open(bubble.value.sessionId, bubble.value.runId) }
function startDrag(event: PointerEvent) {
  if (event.button !== 0 || !widget.value) return
  const box = widget.value.getBoundingClientRect()
  const startX = event.clientX, startY = event.clientY
  moved = false
  const move = (current: PointerEvent) => {
    if (Math.abs(current.clientX - startX) + Math.abs(current.clientY - startY) < 5 && !moved) return
    moved = true
    dragRow.value = current.clientX < startX ? 2 : 1
    position.value = {
      x: Math.max(8, Math.min(window.innerWidth - box.width - 8, box.left + current.clientX - startX)),
      y: Math.max(8, Math.min(window.innerHeight - box.height - 8, box.top + current.clientY - startY))
    }
  }
  const end = () => { dragRow.value = undefined; if (position.value) localStorage.setItem('freyja-agent-position', JSON.stringify(position.value)); cleanupDrag?.() }
  cleanupDrag = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', end); window.removeEventListener('pointercancel', end); cleanupDrag = undefined }
  window.addEventListener('pointermove', move); window.addEventListener('pointerup', end); window.addEventListener('pointercancel', end)
}
function requested(event: Event) { const detail = (event as CustomEvent<{ sessionId: string; runId?: string }>).detail; void store.open(detail.sessionId, detail.runId) }
function clampPosition() {
  if (!position.value || !widget.value) return
  const box = widget.value.getBoundingClientRect()
  position.value = { x: Math.max(8, Math.min(window.innerWidth - box.width - 8, position.value.x)), y: Math.max(8, Math.min(window.innerHeight - box.height - 8, position.value.y)) }
}
onMounted(() => {
  try {
    const saved = JSON.parse(localStorage.getItem('freyja-agent-position') || 'null')
    if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) { position.value = saved; clampPosition() }
  } catch {}
  void store.start()
  window.addEventListener('agent-open-chat', requested)
  window.addEventListener('resize', clampPosition)
})
onBeforeUnmount(() => { cleanupDrag?.(); store.stop(); window.removeEventListener('agent-open-chat', requested); window.removeEventListener('resize', clampPosition) })
watch(avatarExpanded, clampPosition, { flush: 'post' })
</script>
<style scoped>
.agent-widget { position: fixed; z-index: 1995; display: flex; flex-direction: column; align-items: center; gap: 2px; }
.agent-launcher { width: 224px; height: calc(224px * 208 / 192); padding: 0; cursor: grab; touch-action: none; background: transparent; border: none; box-shadow: none; }
.agent-launcher:active { cursor: grabbing; }
.agent-launcher.compact .el-icon { font-size: 28px; color: var(--brand); }
.agent-launcher.compact { width: 46px; height: 46px; background: var(--surface); border: 1px solid var(--border-default); border-radius: 50%; }
.widget-toggle { font-size: 10px; color: var(--text-secondary); border: none; background: var(--surface); border-radius: 8px; padding: 2px 7px; cursor: pointer; }
.agent-bubble { position: absolute; right: 0; bottom: 100%; width: 220px; display: flex; background: var(--surface); border: 1px solid var(--border-default); border-radius: 14px; box-shadow: 0 8px 25px rgba(0,0,0,.12); margin-bottom: 10px; }
.bubble-content { padding: 13px; text-align: left; font-size: 12px; color: var(--text-primary); border: none; background: none; cursor: pointer; line-height: 1.6; }
.bubble-close { align-self: flex-start; border: none; background: none; padding: 4px 8px; color: var(--text-secondary); cursor: pointer; }
</style>
