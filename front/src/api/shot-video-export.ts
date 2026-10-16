import { request } from './request'

export interface ExportNode { key: string; id?: string; type: string; label: string; shotCount: number; videoCount: number; children: ExportNode[]; searchText?: string }
export interface ExportRequest { dramaId: string; shotIds: string[]; name?: string; missingVideoPolicy: 'SKIP' | 'FAIL'; clientRequestId?: string; expectedPreviewHash?: string }
export interface ExportItem { id?: string; shotId: string; name: string; episodeName: string; sceneName: string; groupName: string; takeId?: string; takeNo?: number; zipEntryPath: string; status: string; errorMessage?: string; size?: string | number; sourceSize?: string | number; duration?: number; checksum?: string; executionOrder: number }
export interface ExportPreview { shotIds: string[]; items: ExportItem[]; accepted: number; missing: number; blocked: number; knownBytes: string | number; previewHash: string }
export interface ExportTask { id: string; dramaId: string; name: string; dramaTitle: string; status: string; progress: number; total: number; processed: number; succeeded: number; failed: number; skipped: number; bytesRead: number | string; currentStage: string; errorMessage?: string; size?: number | string; filename: string; expiresAt?: string; createdAt: string; downloadAvailable: boolean; revision: number }
interface Page<T> { records: T[]; total: number; current: number; size: number }
const base = '/drama/shot-video-exports'
export const videoExportApi = {
  tree: (dramaId: string) => request<ExportNode[]>({ url: `${base}/selection-tree`, method: 'get', params: { dramaId } }),
  preview: (data: ExportRequest) => request<ExportPreview>({ url: `${base}/preview`, method: 'post', data, timeout: 120000 }),
  create: (data: ExportRequest) => request<ExportTask>({ url: base, method: 'post', data, timeout: 120000 }),
  list: (dramaId?: string, current = 1) => request<Page<ExportTask>>({ url: base, method: 'get', params: { dramaId, current, size: 20 } }),
  detail: (id: string) => request<ExportTask>({ url: `${base}/${id}`, method: 'get' }),
  items: (id: string, current = 1) => request<Page<ExportItem>>({ url: `${base}/${id}/items`, method: 'get', params: { current, size: 50 } }),
  cancel: (id: string) => request<{ cancelled: boolean; message: string }>({ url: `${base}/${id}/cancel`, method: 'post' }),
  retry: (id: string, clientRequestId: string) => request<ExportTask>({ url: `${base}/${id}/retry`, method: 'post', data: { clientRequestId } }),
  download: (id: string) => request<{ url: string; filename: string; expiresAt: string }>({ url: `${base}/${id}/download-link`, method: 'post' })
}
export const exportLabels: Record<string, string> = { QUEUED: '排队中', READY: '可导出', MISSING_VIDEO: '无当前视频', BLOCKED: '不可导出', PACKAGING: '打包中', UPLOADING: '正在上传 ZIP', SUCCESS: '成功', PARTIAL_SUCCESS: '部分成功', FAILED: '失败', SKIPPED: '已跳过', CANCELLED: '已取消' }
export function exportBytes(value?: number | string) { const bytes = Number(value || 0); return bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(2)} GiB` : `${(bytes / 1024 ** 2).toFixed(1)} MiB` }
