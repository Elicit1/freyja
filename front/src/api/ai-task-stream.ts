import { useTaskCenterStore, type PromptEvent } from '@/store/taskCenter'
import { request } from './request'

/** HTTP creates the task; all output and replay use the shared task-center socket. */
export async function runAiTask(
  url: string,
  data: unknown,
  options: {
    onEvent: (event: PromptEvent) => void
    onDone?: () => void
    onError: (error: Error) => void
    signal?: AbortSignal
    throwOnError?: boolean
  }
): Promise<void> {
  if (options.signal?.aborted) {
    if (options.throwOnError) throw new DOMException('Aborted', 'AbortError')
    return
  }
  let taskId: string
  try { taskId = await request<string>({ url, method: 'post', data }) }
  catch (error) {
    if (options.throwOnError) throw error
    if (!options.signal?.aborted) options.onError(error as Error)
    return
  }
  return watchAiTask(taskId, options)
}

/** Attach to an existing task and replay its Redis event journal from the beginning. */
export function watchAiTask(
  taskId: string,
  options: {
    onEvent: (event: PromptEvent) => void
    onDone?: () => void
    onError: (error: Error) => void
    signal?: AbortSignal
    throwOnError?: boolean
  }
): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const store = useTaskCenterStore()
    let settled = false
    let unsubscribe: (() => void) | undefined
    const finish = (error?: Error) => {
      if (settled) return
      settled = true
      unsubscribe?.()
      options.signal?.removeEventListener('abort', abort)
      if (error && options.throwOnError) reject(error)
      else resolve()
    }
    const abort = () => finish(new DOMException('Aborted', 'AbortError'))
    options.signal?.addEventListener('abort', abort, { once: true })
    unsubscribe = store.subscribeAi(String(taskId), event => {
      if (settled) return
      if (event.type === 'error') {
        options.onError(new Error(event.data || 'AI 任务失败'))
      } else if (event.type === 'done') {
        options.onDone?.()
        finish()
      } else {
        options.onEvent(event)
      }
    })
    if (options.signal?.aborted) abort()
  })
}

/** Await a one-result AI task while receiving the result from the same socket. */
export async function runAiTaskResult<T>(url: string, data?: unknown): Promise<T> {
  let result: T | undefined
  let failure: Error | undefined
  await runAiTask(url, data ?? {}, {
    onEvent(event) {
      if (event.type === 'result') {
        try { result = JSON.parse(event.data) as T }
        catch (error) { failure = error as Error }
      }
    },
    onError(error) { failure = error },
    throwOnError: true
  })
  if (failure) throw failure
  if (result === undefined) throw new Error('AI 任务未返回结果')
  return result
}
