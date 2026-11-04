import { request } from '@/api/request'
import type { LoginSession } from '@/utils/auth-session'

export const authApi = {
  login: (username: string, password: string) => request<LoginSession>({ url: '/auth/login', method: 'post', data: { username, password } }),
  me: () => request<{ username: string }>({ url: '/auth/me', method: 'get' }),
  logout: () => request<void>({ url: '/auth/logout', method: 'post' }),
  changePassword: (oldPassword: string, newPassword: string) => request<void>({ url: '/auth/password', method: 'post', data: { oldPassword, newPassword } }),
  websocketTicket: () => request<{ ticket: string }>({ url: '/auth/ws-ticket', method: 'post' })
}
