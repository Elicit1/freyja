import axios, { type AxiosInstance, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import type { R } from '@/types/api'

// 创建 Axios 实例
const service: AxiosInstance = axios.create({
  baseURL: '/api', // 由 vite.config.ts 代理到 http://127.0.0.1:8080
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json;charset=utf-8'
  }
})

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    // 后续如有 Token，可在此处统一注入 Authorization 头
    return config
  },
  (error) => {
    console.error('Request error:', error)
    return Promise.reject(error)
  }
)

// 响应拦截器
service.interceptors.response.use(
  (response: AxiosResponse<R>) => {
    const res = response.data
    // 如果没有 code 字段（如二进制流下载等），直接返回
    if (res.code === undefined) {
      return response.data
    }

    // code === 200 为成功
    if (res.code === 200) {
      return res.data
    }

    // 业务错误提示
    const errorMsg = res.msg || '业务处理失败'
    ElMessage.error(errorMsg)
    return Promise.reject(new Error(errorMsg))
  },
  (error) => {
    console.error('Response network error:', error)
    let message = '网络请求异常，请检查后端服务'
    if (error.response) {
      if (error.response.status === 404) {
        message = '请求接口未找到 (404)'
      } else if (error.response.status === 500) {
        message = error.response.data?.msg || '服务器内部错误 (500)'
      }
    } else if (error.message && error.message.includes('timeout')) {
      message = '网络请求超时'
    }
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export function request<T = any>(config: AxiosRequestConfig): Promise<T> {
  return service(config) as unknown as Promise<T>
}

export default service
