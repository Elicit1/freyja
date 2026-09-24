import type { PageQuery } from './api'

/**
 * 系统参数配置实体 sys_config
 */
export interface SysConfig {
  id?: number | string
  configName: string
  configKey: string
  configValue: string
  configType: 'STRING' | 'TEXT' | 'JSON' | 'NUMBER' | 'BOOLEAN' | string
  isBuiltin?: number // 0-否 1-是
  status: number // 0-停用 1-启用
  remark?: string
  createTime?: string
  updateTime?: string
  createBy?: number | string
  updateBy?: number | string
}

/**
 * 系统配置分页查询入参
 */
export interface SysConfigQuery extends PageQuery {
  configName?: string
  configKey?: string
  configType?: string
  isBuiltin?: number
  status?: number
}

/**
 * 新增/修改 DTO
 */
export interface SysConfigDTO {
  id?: number | string
  configName: string
  configKey: string
  configValue: string
  configType: string
  isBuiltin?: number
  status: number
  remark?: string
}
