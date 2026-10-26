export function projectDate(value?: string): string {
  if (!value) return '暂无更新记录'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '暂无更新记录'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}

export function completionPercent(completed = 0, total = 0): number {
  return total > 0 ? Math.min(100, Math.max(0, Math.round(completed / total * 100))) : 0
}
