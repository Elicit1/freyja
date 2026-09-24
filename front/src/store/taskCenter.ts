import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { taskCenterApi, type TaskCenterItem } from '@/api/task-center'

const ACTIVE_STATUSES = new Set(['QUEUED', 'PENDING', 'RUNNING', 'RETRYING', 'RENDERING'])

export interface PromptEvent {
  taskId: string
  seq: number
  type: 'task_created' | 'stage' | 'chunk' | 'result' | 'error' | 'done' | 'segments_init' | 'assets_discovered' | 'channel_chunk' | 'worker_status' | 'skill_event' | 'channel_reset'
  data: string
}

export const useTaskCenterStore = defineStore('taskCenter', () => {
  const activeTasks = ref<TaskCenterItem[]>([])
  const recentTasks = ref<TaskCenterItem[]>([])
  const initialized = ref(false)
  const loading = ref(false)
  const connected = ref(false)

  let socket: WebSocket | null = null
  let pingTimer: ReturnType<typeof setInterval> | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let retryCount = 0
  type AiListener = (event: PromptEvent) => void
  type ListenerState = { afterSeq: number; pending: Map<number, PromptEvent> }
  const promptSubscriptions = new Map<string, Map<AiListener, ListenerState>>()

  function sendPromptSubscription(taskId: string, afterSeq: number) {
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ event: 'AI_SUBSCRIBE', taskId, afterSeq }))
    }
  }

  function subscribePrompt(taskId: string, listener: (event: PromptEvent) => void, afterSeq = 0) {
    const id = String(taskId)
    let entry = promptSubscriptions.get(id)
    if (!entry) {
      entry = new Map()
      promptSubscriptions.set(id, entry)
    }
    entry.set(listener, { afterSeq, pending: new Map() })
    sendPromptSubscription(id, afterSeq)
    start()
    return () => {
      const current = promptSubscriptions.get(id)
      current?.delete(listener)
      if (current?.size === 0) {
        promptSubscriptions.delete(id)
        if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ event: 'AI_UNSUBSCRIBE', taskId: id }))
      }
    }
  }

  const runningCount = computed(() => activeTasks.value.length)
  const attentionCount = computed(() => activeTasks.value.length + recentTasks.value.filter(t => t.status === 'FAILED' || t.unread).length)

  function isActiveStatus(status?: string): boolean {
    return status ? ACTIVE_STATUSES.has(status) : false
  }

  function mergeActive(tasks: TaskCenterItem[]) {
    activeTasks.value = tasks.map(item => ({ ...item, taskId: String(item.taskId) }))
  }

  // 接收增量任务推送
  function handleTaskUpdated(task: TaskCenterItem) {
    const strTaskId = String(task.taskId)
    const normalizedTask: TaskCenterItem = { ...task, taskId: strTaskId }

    const activeIndex = activeTasks.value.findIndex(
      t => t.sourceType === normalizedTask.sourceType && String(t.taskId) === strTaskId
    )

    if (isActiveStatus(normalizedTask.status)) {
      if (activeIndex >= 0) {
        activeTasks.value[activeIndex] = { ...activeTasks.value[activeIndex], ...normalizedTask }
      } else {
        activeTasks.value.unshift(normalizedTask)
      }

      // 如果之前在最近任务列表中，移除它
      const recentIndex = recentTasks.value.findIndex(
        t => t.sourceType === normalizedTask.sourceType && String(t.taskId) === strTaskId
      )
      if (recentIndex >= 0) {
        recentTasks.value.splice(recentIndex, 1)
      }
    } else {
      // 任务进入终态 (SUCCESS / PARTIAL_SUCCESS / FAILED / CANCELLED)
      if (activeIndex >= 0) {
        activeTasks.value.splice(activeIndex, 1)
      }

      const recentIndex = recentTasks.value.findIndex(
        t => t.sourceType === normalizedTask.sourceType && String(t.taskId) === strTaskId
      )
      const finishedItem: TaskCenterItem = { ...normalizedTask, unread: true }
      if (recentIndex >= 0) {
        recentTasks.value[recentIndex] = finishedItem
      } else {
        recentTasks.value.unshift(finishedItem)
        if (recentTasks.value.length > 50) {
          recentTasks.value.pop()
        }
      }
    }
  }

  function handleWsMessage(msg: { event: string; activeCount?: number; data?: any }) {
    switch (msg.event) {
      case 'INITIAL_STATE':
        if (Array.isArray(msg.data)) {
          mergeActive(msg.data)
        }
        break

      case 'TASK_UPDATED':
        if (msg.data && typeof msg.data === 'object') {
          handleTaskUpdated(msg.data)
          window.dispatchEvent(new CustomEvent('task-center-task-updated', { detail: msg.data }))
        }
        break

      case 'AI_EVENT':
      case 'PROMPT_EVENT': {
        const event = msg.data as PromptEvent
        const entry = promptSubscriptions.get(String(event?.taskId))
        if (!entry || !Number.isSafeInteger(event?.seq)) break
        entry.forEach((state, listener) => {
          if (event.seq <= state.afterSeq) return
          state.pending.set(event.seq, event)
          while (state.pending.has(state.afterSeq + 1)) {
            const next = state.pending.get(state.afterSeq + 1)!
            state.pending.delete(next.seq)
            state.afterSeq = next.seq
            try { listener(next) }
            catch (error) { console.warn('[TaskCenterWS] AI 事件处理失败:', error) }
          }
        })
        break
      }

      case 'RENDER_EVENT':
        window.dispatchEvent(new CustomEvent('task-center-render-event', { detail: msg.data }))
        break

      case 'PONG':
        // 心跳响应
        break

      default:
        break
    }
  }

  function startHeartbeat() {
    stopHeartbeat()
    pingTimer = setInterval(() => {
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send('PING')
      }
    }, 30000)
  }

  function stopHeartbeat() {
    if (pingTimer) {
      clearInterval(pingTimer)
      pingTimer = null
    }
  }

  function scheduleReconnect() {
    if (reconnectTimer || !initialized.value) return
    const delay = Math.min(1000 * Math.pow(2, retryCount), 30000)
    retryCount++
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      if (initialized.value) {
        initWebSocket()
      }
    }, delay)
  }

  function initWebSocket() {
    if (typeof window === 'undefined') return
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
      return
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const wsUrl = `${protocol}//${host}/ws/task-center`

    try {
      socket = new WebSocket(wsUrl)

      socket.onopen = () => {
        connected.value = true
        retryCount = 0
        startHeartbeat()
        window.dispatchEvent(new Event('task-center-connected'))
        promptSubscriptions.forEach((entry, taskId) => {
          const oldestCursor = Math.min(...Array.from(entry.values(), state => state.afterSeq))
          sendPromptSubscription(taskId, oldestCursor)
        })
      }

      socket.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data)
          handleWsMessage(msg)
        } catch (e) {
          console.warn('[TaskCenterWS] 解析推流消息失败:', e)
        }
      }

      socket.onclose = () => {
        connected.value = false
        stopHeartbeat()
        scheduleReconnect()
      }

      socket.onerror = (err) => {
        console.warn('[TaskCenterWS] 连接异常:', err)
        socket?.close()
      }
    } catch (e) {
      console.error('[TaskCenterWS] 启动 WebSocket 异常:', e)
      scheduleReconnect()
    }
  }

  // 手动按需拉取活跃任务（供刷新按钮兜底使用，不再作为自动轮询）
  async function refreshActive() {
    try {
      loading.value = true
      const tasks = await taskCenterApi.getActive()
      mergeActive(tasks || [])
    } catch (error) {
      console.warn('刷新任务中心失败:', error)
    } finally {
      loading.value = false
    }
  }

  async function refreshHistory(limit = 50) {
    try {
      const result = await taskCenterApi.getHistory(limit)
      recentTasks.value = (result?.records || []).map(item => ({ ...item, taskId: String(item.taskId) }))
    } catch (error) {
      console.warn('加载任务历史失败:', error)
    }
  }

  function start() {
    if (initialized.value || typeof window === 'undefined') return
    initialized.value = true
    initWebSocket()
    void refreshHistory()
  }

  function stop() {
    initialized.value = false
    stopHeartbeat()
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (socket) {
      socket.close()
      socket = null
    }
    connected.value = false
  }

  function markRead(taskId: string, sourceType?: string) {
    recentTasks.value = recentTasks.value.map(item => {
      if (String(item.taskId) === String(taskId) && (!sourceType || item.sourceType === sourceType)) {
        return { ...item, unread: false }
      }
      return item
    })
  }

  function getTask(taskId: string, sourceType?: string) {
    return [...activeTasks.value, ...recentTasks.value].find(item =>
      String(item.taskId) === String(taskId) && (!sourceType || item.sourceType === sourceType)
    )
  }

  return {
    activeTasks,
    recentTasks,
    initialized,
    loading,
    connected,
    runningCount,
    attentionCount,
    start,
    stop,
    refreshActive,
    refreshHistory,
    markRead,
    getTask,
    subscribePrompt,
    subscribeAi: subscribePrompt
  }
})
