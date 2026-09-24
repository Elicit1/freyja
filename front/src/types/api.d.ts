/**
 * 统一后端返回结构 R<T>
 */
export interface R<T = any> {
  code: number
  msg: string
  data: T
}

/**
 * 分页数据结构 (MyBatis-Plus Page)
 */
export interface PageResult<T = any> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

/**
 * 分页查询基础入参
 */
export interface PageQuery {
  pageNum?: number
  pageSize?: number
}
