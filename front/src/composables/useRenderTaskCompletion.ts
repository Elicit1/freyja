import { onUnmounted } from 'vue'
import { renderTaskApi, type RenderTaskVO } from '@/api/render-task'
import { useTaskCenterStore } from '@/store/taskCenter'

/** Subscribe before submission so fast completions cannot be lost before the HTTP response. */
export function useRenderTaskCompletion() {
  const taskCenter = useTaskCenterStore()
  const latest = new Map<string, RenderTaskVO>()
  const waiting = new Map<string, { receive: (task: RenderTaskVO) => void; reject: (error: Error) => void }>()
  let disposed = false

  function receive(task?: RenderTaskVO) {
    if (!task?.taskId) return
    const id = String(task.taskId)
    const previous = latest.get(id)
    if (previous && ['SUCCESS', 'FAILED', 'CANCELLED'].includes(previous.status)) return
    latest.set(id, task)
    waiting.get(id)?.receive(task)
  }
  function onEvent(event: Event) { receive((event as CustomEvent).detail?.data) }
  async function snapshot(id: string) {
    try { receive(await renderTaskApi.getTaskById(id)) }
    catch (error) { console.warn('读取渲染任务快照失败，继续等待 WebSocket 事件', error) }
  }
  function onConnected() { for (const id of waiting.keys()) void snapshot(id) }
  window.addEventListener('task-center-render-event', onEvent)
  window.addEventListener('task-center-connected', onConnected)
  taskCenter.start()

  function waitForTask(taskId: string, onProgress: (task: RenderTaskVO) => void): Promise<RenderTaskVO> {
    if (disposed) return Promise.reject(new Error('渲染窗口已关闭'))
    const id = String(taskId)
    return new Promise((resolve, reject) => {
      waiting.set(id, { reject, receive(task) {
        onProgress(task)
        if (task.status === 'SUCCESS') { waiting.delete(id); resolve(task) }
        else if (task.status === 'FAILED' || task.status === 'CANCELLED') {
          waiting.delete(id); reject(new Error(task.errorMessage || (task.status === 'CANCELLED' ? '渲染任务已取消' : '渲染失败')))
        }
      } })
      const cached = latest.get(id)
      if (cached) waiting.get(id)?.receive(cached)
      if (waiting.has(id)) void snapshot(id)
    })
  }

  onUnmounted(() => {
    disposed = true
    window.removeEventListener('task-center-render-event', onEvent)
    window.removeEventListener('task-center-connected', onConnected)
    waiting.forEach(entry => entry.reject(new Error('渲染窗口已关闭')))
    waiting.clear(); latest.clear()
  })
  return { waitForTask }
}
