import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { renderTaskApi, type RenderTaskVO } from '@/api/render-task'
import { useTaskCenterStore } from '@/store/taskCenter'
import { ElNotification } from 'element-plus'

export const useRenderTaskStore = defineStore('renderTask', () => {
  const activeTasks = ref<RenderTaskVO[]>([])
  const taskCenterStore = useTaskCenterStore()
  const connected = computed(() => taskCenterStore.connected)
  let listening = false

  const activeCount = computed(() => activeTasks.value.length)

  // Render events share the task-center WebSocket; no second connection is opened.
  function initWebSocket() {
    if (listening) return
    listening = true
    window.addEventListener('task-center-render-event', (event: Event) => {
      handleWsMessage((event as CustomEvent).detail)
    })
    window.addEventListener('task-center-connected', () => { void refreshActiveTasks() })
    taskCenterStore.start()
    void refreshActiveTasks()
  }
  // 集中分发长连接事件
  function handleWsMessage(msg: { event: string; activeCount?: number; data?: any }) {
    switch (msg.event) {
      case 'INITIAL_STATE':
        if (Array.isArray(msg.data)) {
          activeTasks.value = msg.data
        }
        break

      case 'TASK_QUEUED': {
        const newTask: RenderTaskVO = msg.data
        const index = activeTasks.value.findIndex((t) => t.taskId === newTask.taskId)
        if (index >= 0) {
          activeTasks.value[index] = newTask
        } else {
          activeTasks.value.unshift(newTask)
        }
        break
      }

      case 'TASK_PROGRESS': {
        const updatedTask: RenderTaskVO = msg.data
        const index = activeTasks.value.findIndex((t) => t.taskId === updatedTask.taskId)
        if (index >= 0) {
          activeTasks.value[index] = { ...activeTasks.value[index], ...updatedTask }
        } else {
          activeTasks.value.unshift(updatedTask)
        }
        break
      }

      case 'TASK_SUCCESS': {
        const finishedTask: RenderTaskVO = msg.data
        const idx = activeTasks.value.findIndex((t) => t.taskId === finishedTask?.taskId)
        if (idx >= 0) {
          activeTasks.value.splice(idx, 1)
        }
        ElNotification.success({
          title: '🎬 渲染完成',
          message: `${finishedTask?.taskName || '分镜任务'} 已渲染完成并归档！`,
          position: 'bottom-right',
          duration: 3500
        })
        window.dispatchEvent(new CustomEvent('render-task-status-change', { detail: { event: 'TASK_SUCCESS', task: finishedTask } }))
        break
      }

      case 'TASK_FAILED': {
        const failedTask: RenderTaskVO = msg.data
        const idx = activeTasks.value.findIndex((t) => t.taskId === failedTask?.taskId)
        if (idx >= 0) {
          activeTasks.value.splice(idx, 1)
        }
        ElNotification.error({
          title: '❌ 渲染异常',
          message: `${failedTask?.taskName || '分镜任务'} 渲染失败: ${failedTask?.errorMessage || '未知错误'}`,
          position: 'bottom-right',
          duration: 4500
        })
        window.dispatchEvent(new CustomEvent('render-task-status-change', { detail: { event: 'TASK_FAILED', task: failedTask } }))
        break
      }

      case 'TASK_CANCELLED': {
        const cancelledTask: RenderTaskVO = msg.data
        const idx = activeTasks.value.findIndex((t) => t.taskId === cancelledTask?.taskId)
        if (idx >= 0) {
          activeTasks.value.splice(idx, 1)
        }
        window.dispatchEvent(new CustomEvent('render-task-status-change', { detail: { event: 'TASK_CANCELLED', task: cancelledTask } }))
        break
      }

      case 'PONG':
        // 心跳回包
        break
    }
  }

  // 手动刷新兜底 (调用 Redis 接口)
  async function refreshActiveTasks() {
    try {
      activeTasks.value = await renderTaskApi.getActiveTasks()
    } catch (e) {
      console.warn('刷新活跃渲染任务失败:', e)
    }
  }

  // 取消任务
  async function cancelTask(taskId: string) {
    await renderTaskApi.cancelTask(taskId)
    const idx = activeTasks.value.findIndex((t) => t.taskId === taskId)
    let task: RenderTaskVO | undefined = undefined
    if (idx >= 0) {
      task = activeTasks.value[idx]
      activeTasks.value.splice(idx, 1)
    }
    window.dispatchEvent(new CustomEvent('render-task-status-change', { detail: { event: 'TASK_CANCELLED', task: task || { taskId } } }))
  }

  return {
    activeTasks,
    activeCount,
    connected,
    initWebSocket,
    refreshActiveTasks,
    cancelTask
  }
})
