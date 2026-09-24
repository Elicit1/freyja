import { request } from '@/api/request'
import type { DashboardStats } from '@/types/dashboard'

/**
 * 获取工作台首页全景统计看板数据
 */
export function getDashboardStats(): Promise<DashboardStats> {
  return request<DashboardStats>({
    url: '/dashboard/stats',
    method: 'get'
  })
}
