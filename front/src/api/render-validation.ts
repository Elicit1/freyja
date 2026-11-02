import { h } from 'vue'
import { ElMessageBox } from 'element-plus'

export interface RenderValidationIssue {
  code: string; shotId?: string; label?: string; field: string; line?: number
  message: string; suggestion: string
}
export interface RenderValidationReport {
  rulesVersion: string; submissionReady: boolean; dispatchReady: boolean; inputFingerprint: string
  slots: { label: string; referenceId?: string; kind: string; role: string; pending: boolean }[]
  issues: RenderValidationIssue[]
}
export async function showRenderValidation(report: RenderValidationReport) {
  await ElMessageBox.alert(h('div', { style: 'max-height:55vh;overflow:auto' }, [
    h('p', `本次素材槽位：${report.slots.map(s => `${s.label}${s.pending ? '（等待邻镜）' : ''}`).join('、') || '无'}`),
    ...report.issues.map(issue => h('div', { style: 'margin-top:12px' }, [
      h('div', `${issue.line ? `第 ${issue.line} 行 · ` : ''}${issue.message}`),
      h('div', { style: 'color:var(--el-text-color-secondary);font-size:12px' }, issue.suggestion)
    ]))
  ]), '渲染检查未通过', { confirmButtonText: '返回修改', type: 'warning' })
}
