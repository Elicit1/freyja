import { request } from './request'
import type {
  ComfyRenderSubmitDTO,
  ComfyRenderTaskVO,
  ComfyWorkflowTemplateVO
} from '@/types/comfy'

/**
 * 提交 ComfyUI 渲染生成任务
 */
export function submitComfyTask(data: ComfyRenderSubmitDTO): Promise<ComfyRenderTaskVO> {
  return request<ComfyRenderTaskVO>({
    url: '/v1/render/comfy/submit',
    method: 'post',
    data
  })
}

/**
 * 根据任务 ID 查询渲染状态与实时进度
 */
export function getComfyTask(taskId: string): Promise<ComfyRenderTaskVO> {
  return request<ComfyRenderTaskVO>({
    url: `/v1/render/comfy/task/${taskId}`,
    method: 'get'
  })
}

/**
 * 根据 Prompt ID 查询任务状态
 */
export function getComfyTaskByPrompt(promptId: string): Promise<ComfyRenderTaskVO> {
  return request<ComfyRenderTaskVO>({
    url: `/v1/render/comfy/task/by-prompt/${promptId}`,
    method: 'get'
  })
}

/**
 * 获取系统内置预设工作流模板列表
 */
export function getComfyTemplates(): Promise<ComfyWorkflowTemplateVO[]> {
  return request<ComfyWorkflowTemplateVO[]>({
    url: '/v1/render/comfy/templates',
    method: 'get'
  })
}

/**
 * 上传参考图/素材至 ComfyUI input 目录
 */
export function uploadComfyReferenceImage(
  file: File,
  providerId?: number | string,
  subfolder: string = '',
  overwrite: boolean = true
): Promise<any> {
  const formData = new FormData()
  formData.append('file', file)
  if (providerId) {
    formData.append('providerId', String(providerId))
  }
  formData.append('subfolder', subfolder)
  formData.append('overwrite', String(overwrite))

  return request<any>({
    url: '/v1/render/comfy/upload-ref',
    method: 'post',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}
