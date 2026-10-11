import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { agentApi } from '@/api/agent'
import { useTaskCenterStore } from '@/store/taskCenter'
import { isActiveRun } from '@/types/agent'
import type { AgentEvent, AgentProfile, AgentSession, AgentMessage, AgentRun, AvatarState } from '@/types/agent'

export const useAgentStore = defineStore('agent', () => {
  const profiles = ref<AgentProfile[]>([])
  const sessions = ref<AgentSession[]>([])
  const selectedSession = ref<AgentSession>()
  const messages = ref<AgentMessage[]>([])
  const runs = ref<Record<string, AgentRun>>({})
  const notifications = ref<AgentRun[]>([])
  const visible = ref(false)
  const avatarCollapsed = ref(localStorage.getItem('freyja-agent-collapsed') === 'true')
  const loading = ref(false)
  const phase = ref<AvatarState>('IDLE')
  const focusRunId = ref<string>()
  const taskCenter = useTaskCenterStore()
  const subscriptions = new Map<string, () => void>()
  let started = false
  let selectGeneration = 0
  const activeRun = computed(() => selectedSession.value?.activeRunId ? runs.value[selectedSession.value.activeRunId] : undefined)
  const currentProfile = computed(() => profiles.value[0])
  const unreadCount = computed(() => notifications.value.length)
  watch(avatarCollapsed, value => localStorage.setItem('freyja-agent-collapsed', String(value)), { flush: 'sync' })

  function mergeMessage(message: AgentMessage) {
    const existing = messages.value.findIndex(item => item.seq === message.seq)
    if (existing >= 0) messages.value[existing] = message
    else messages.value.push(message)
    messages.value.sort((a, b) => a.seq - b.seq)
  }
  async function refreshRun(id: string) {
    const run = await agentApi.run(id)
    runs.value[id] = run
    const session = sessions.value.find(s => s.id === run.sessionId)
    function syncActive(target: AgentSession) {
      if (!isActiveRun(run.status)) {
        if (target.activeRunId === run.id) target.activeRunId = undefined
      } else {
        const current = target.activeRunId ? runs.value[target.activeRunId] : undefined
        if (!current || (current.createTime || '') <= (run.createTime || '')) target.activeRunId = run.id
      }
    }
    if (session) syncActive(session)
    if (selectedSession.value?.id === run.sessionId) syncActive(selectedSession.value)
    if (run.runType === 'NORMAL' && !isActiveRun(run.status) && !run.readAt) {
      const index = notifications.value.findIndex(n => n.id === run.id)
      if (index >= 0) notifications.value[index] = run
      else notifications.value.unshift(run)
      if (visible.value && selectedSession.value?.id === run.sessionId) await markRead(run.id)
    }
    return run
  }
  function receive(event: AgentEvent) {
    const message = event.payload
    if (selectedSession.value?.id === event.sessionId) mergeMessage(message)
    if (event.type === 'MODEL_STARTED') phase.value = 'THINKING'
    if (event.type === 'TOOL_STARTED' || event.type === 'WAITING_TASK') phase.value = 'WORKING'
    if (event.type === 'WAITING_USER') phase.value = 'ATTENTION'
    if (event.type === 'RUN_COMPLETED') phase.value = 'SUCCESS'
    if (event.type === 'RUN_FAILED') phase.value = 'ERROR'
    if (event.type === 'RUN_CANCELLED') phase.value = 'IDLE'
    if (event.runId && ['RUN_QUEUED', 'RUN_STARTED', 'WAITING_TASK', 'WAITING_USER', 'RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED'].includes(event.type)) {
      void refreshRun(event.runId).catch(() => {})
    }
  }
  function subscribe(session: AgentSession) {
    if (subscriptions.has(session.id)) return
    subscriptions.set(session.id, taskCenter.subscribeAgent(session.id, receive, Math.max(0, session.nextSeq - 1)))
  }
  async function refreshProfiles({ revealAvatar = false }: { revealAvatar?: boolean } = {}) {
    const avatarWasVisible = !!currentProfile.value?.avatar.visible
    profiles.value = await agentApi.profiles()
    if (currentProfile.value?.avatar.visible && (revealAvatar || !avatarWasVisible)) avatarCollapsed.value = false
  }
  async function start() {
    if (started) return
    started = true
    try {
      const [available, history, unread] = await Promise.all([agentApi.profiles(), agentApi.sessions(), agentApi.notifications()])
      profiles.value = available; sessions.value = history; notifications.value = unread
      history.forEach(subscribe)
      await Promise.all(history.filter(s => !!s.activeRunId).map(s => refreshRun(s.activeRunId!)))
    } catch { started = false }
  }
  function stop() {
    subscriptions.forEach(unsubscribe => unsubscribe()); subscriptions.clear(); started = false
  }
  async function selectSession(id: string, runId?: string) {
    const generation = ++selectGeneration
    loading.value = true
    try {
      const session = await agentApi.session(id)
      if (generation !== selectGeneration) return
      selectedSession.value = session; messages.value = []; subscribe(session)
      let cursor = 0
      const loaded: AgentMessage[] = []
      while (true) {
        const page = await agentApi.messages(id, cursor)
        loaded.push(...page)
        if (page.length < 500) break
        cursor = page[page.length - 1]!.seq
      }
      if (generation !== selectGeneration) return
      const merged = new Map([...loaded, ...messages.value].map(message => [message.seq, message]))
      messages.value = [...merged.values()].sort((a, b) => a.seq - b.seq); focusRunId.value = runId
      subscribe(session)
      const relevant = runId || session.activeRunId || loaded.filter(m => !!m.runId).at(-1)?.runId
      if (relevant) await refreshRun(relevant)
      for (const notification of [...notifications.value].filter(n => n.sessionId === id)) await markRead(notification.id)
    } finally { if (generation === selectGeneration) loading.value = false }
  }
  async function open(sessionId?: string, runId?: string) {
    visible.value = true
    await start()
    if (sessionId) await selectSession(sessionId, runId)
    else if (!selectedSession.value && sessions.value[0]) await selectSession(sessions.value[0].id)
  }
  async function createSession(dramaId?: string) {
    const code = currentProfile.value?.agentCode || 'core-assistant'
    const session = await agentApi.createSession(code, dramaId)
    sessions.value.unshift(session)
    subscribe(session)
    await selectSession(session.id)
    return session
  }
  async function send(content: string, clientRequestId: string, mode?: 'STEER' | 'ANSWER' | 'REPLACE_RUN') {
    const session = selectedSession.value || await createSession()
    const run = activeRun.value
    const intent = run && isActiveRun(run.status) ? mode || (run.status === 'WAITING_USER' ? 'ANSWER' : 'STEER') : 'START_RUN'
    const submitted = await agentApi.send(session.id, content, intent, clientRequestId, intent === 'START_RUN' ? undefined : run?.id)
    if (intent === 'REPLACE_RUN' && run) await refreshRun(run.id)
    await refreshRun(submitted.runId)
    if (selectedSession.value?.id === session.id) {
      const last = messages.value.at(-1)?.seq || 0
      const fresh = await agentApi.messages(session.id, last)
      if (selectedSession.value?.id === session.id) fresh.forEach(mergeMessage)
    }
  }
  async function cancel() {
    if (activeRun.value) { await agentApi.cancel(activeRun.value.id); await refreshRun(activeRun.value.id) }
  }
  async function markRead(id: string) {
    await agentApi.read(id)
    notifications.value = notifications.value.filter(n => n.id !== id)
    taskCenter.markRead(id, 'AGENT_RUN')
  }
  return { profiles, sessions, selectedSession, messages, runs, notifications, visible, avatarCollapsed, loading, phase,
    focusRunId, activeRun, currentProfile, unreadCount, start, stop, open, selectSession, createSession,
    send, cancel, refreshProfiles, refreshRun, markRead }
})
