import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { ElNotification } from 'element-plus'
import { shotApi } from '@/api/drama'
import type { ShotPromptDeriveVO, ShotPromptTask } from '@/types/drama'

const STORAGE_KEY = 'freyja:shot-prompt-task-ids'

export interface PromptTaskSnapshot {
  taskId: string
  shotId?: string
  status: string
  result?: ShotPromptDeriveVO
  errorMessage?: string
  finishedAt?: string
}

function readStoredIds(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed.map(String).filter(Boolean) : []
  } catch {
    return []
  }
}

function writeStoredIds(ids: string[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(ids))
  } catch {
    // localStorage 不可用时仍保留内存任务状态
  }
}

export const usePromptTaskStore = defineStore('promptTask', () => {
  const tasks = ref<Record<string, PromptTaskSnapshot>>({})
  const polling = ref(false)
  let listenerAttached = false

  const activeTasks = computed(() => Object.values(tasks.value).filter(task =>
    task.status === 'PENDING' || task.status === 'RUNNING' || task.status === 'RETRYING'
  ))
  const activeCount = computed(() => activeTasks.value.length)

  function upsert(task: PromptTaskSnapshot) {
    const taskId = String(task.taskId)
    tasks.value[taskId] = { ...tasks.value[taskId], ...task }
    const ids = Object.keys(tasks.value).filter(id => {
      const status = tasks.value[id]?.status
      return status === 'PENDING' || status === 'RUNNING' || status === 'RETRYING'
    })
    writeStoredIds(ids)
  }

  function parseResult(task: ShotPromptTask): ShotPromptDeriveVO | undefined {
    if (!task.outputPayload) return undefined
    try {
      return JSON.parse(task.outputPayload) as ShotPromptDeriveVO
    } catch {
      return undefined
    }
  }

  function register(taskId: string, shotId?: string) {
    const strId = String(taskId)
    upsert({ taskId: strId, shotId: shotId ? String(shotId) : undefined, status: 'PENDING' })
    ensureListener()
  }

  // 单次按需拉取单个任务的完整详情并解析结果
  async function refreshTask(taskId: string) {
    try {
      const serverTask = await shotApi.getPromptTask(taskId)
      if (!serverTask) return
      const previous = tasks.value[String(taskId)]
      const status = serverTask.status || previous?.status || 'PENDING'
      const result = status === 'SUCCESS' ? parseResult(serverTask) : previous?.result
      upsert({
        taskId: String(serverTask.id),
        shotId: serverTask.targetId ? String(serverTask.targetId) : previous?.shotId,
        status,
        result,
        errorMessage: serverTask.errorMessage,
        finishedAt: serverTask.finishedAt
      })

      if (status === 'SUCCESS' && previous?.status !== 'SUCCESS') {
        ElNotification.success({
          title: 'AI 提示词分析完成',
          message: `分镜 ${serverTask.targetId || ''} 的提示词已生成，可重新打开镜头查看。`,
          duration: 6000
        })
        window.dispatchEvent(new CustomEvent('shot-prompt-task-completed', {
          detail: { taskId: String(serverTask.id), shotId: serverTask.targetId ? String(serverTask.targetId) : undefined, result }
        }))
      } else if (status === 'FAILED' && previous?.status !== 'FAILED') {
        ElNotification.error({
          title: 'AI 提示词分析失败',
          message: serverTask.errorMessage || '请重新分析',
          duration: 8000
        })
        window.dispatchEvent(new CustomEvent('shot-prompt-task-failed', {
          detail: { taskId: String(serverTask.id), shotId: serverTask.targetId ? String(serverTask.targetId) : undefined, errorMessage: serverTask.errorMessage }
        }))
      }
    } catch (e) {
      console.warn('[PromptTaskStore] 读取任务详情异常: taskId=', taskId, e)
    }
  }

  // 监听来自全局任务中心 WebSocket 的推流事件
  function handleTaskCenterUpdate(event: Event) {
    const item = (event as CustomEvent<any>).detail
    if (!item?.taskId) return

    const taskId = String(item.taskId)
    // 只处理当前 store 跟踪的提示词任务，或者 taskType 为 SHOT_PROMPT_DERIVE 的任务
    if (!tasks.value[taskId] && item.taskType !== 'SHOT_PROMPT_DERIVE') {
      return
    }

    const status = String(item.status || '')
    if (status === 'SUCCESS' || status === 'FAILED') {
      // 终态时单次拉取完整 payload 结果
      void refreshTask(taskId)
    } else if (tasks.value[taskId]) {
      // 中间态直接在内存中同步状态，不需要发送 HTTP 查询
      tasks.value[taskId] = {
        ...tasks.value[taskId],
        status
      }
    }
  }

  function ensureListener() {
    if (listenerAttached || typeof window === 'undefined') return
    listenerAttached = true
    window.addEventListener('task-center-task-updated', handleTaskCenterUpdate)
  }

  // 兼容旧接口：不再执行 setInterval 轮询，改为单次按需刷新
  async function refreshAll() {
    const ids = Object.keys(tasks.value)
    await Promise.all(ids.map(id => refreshTask(id).catch(() => undefined)))
  }

  function ensurePolling() {
    ensureListener()
  }

  function stopPolling() {
    // WebSocket 模式下无需轮询计时器
  }

  // 初始化从 localStorage 恢复
  async function restore() {
    ensureListener()
    const ids = readStoredIds()
    if (ids.length === 0) return

    ids.forEach(taskId => {
      tasks.value[taskId] = { taskId, status: 'PENDING' }
    })

    // 仅在页面初次启动时单次检查一次历史遗留任务的最新状态（避免死任务长驻）
    for (const taskId of ids) {
      await refreshTask(taskId).catch(() => undefined)
    }
  }

  function getTask(taskId?: string | number) {
    return taskId === undefined ? undefined : tasks.value[String(taskId)]
  }

  restore()

  return {
    tasks,
    activeTasks,
    activeCount,
    polling,
    register,
    refreshTask,
    refreshAll,
    ensurePolling,
    stopPolling,
    getTask
  }
})
