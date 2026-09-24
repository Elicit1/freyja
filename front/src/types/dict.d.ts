import type { PageQuery } from './api'

/**
 * 字典类型实体 sys_dict_type
 */
export interface SysDictType {
  id?: number | string
  dictType: string
  dictName: string
  status: number // 0-停用 1-启用
  remark?: string
  createTime?: string
  updateTime?: string
  createBy?: number | string
  updateBy?: number | string
}

/**
 * 字典数据项实体 sys_dict_data
 */
export interface SysDictData {
  id?: number | string
  dictType: string
  dictLabel: string
  dictValue: string
  sortOrder?: number
  status: number // 0-停用 1-启用
  remark?: string
  createTime?: string
  updateTime?: string
  createBy?: number | string
  updateBy?: number | string
}

/**
 * 字典类型查询入参
 */
export interface DictTypeQuery extends PageQuery {
  dictType?: string
  dictName?: string
  status?: number
}

/**
 * 字典数据项查询入参
 */
export interface DictDataQuery extends PageQuery {
  dictType?: string
  dictLabel?: string
  status?: number
}
