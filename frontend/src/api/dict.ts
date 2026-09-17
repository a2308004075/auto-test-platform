/**
 * @author HXN
 * @date 2026-08-22 13:28
 * @description 字典模块 API（字典管理功能已下线，仅保留字典数据读取）
 */
import request from './request'

export interface DictListItem {
  id: number
  dictType: string
  dictTypeName: string
  dictValue: string
  dictValueName: string
  sortNo: number
  remark?: string
  createdAt: string
  updatedAt: string
}

/** 根据字典类型查询字典值列表 */
export function getDictByType(dictType: string) {
  return request.get(`/v1/sys/dicts/type/${dictType}`)
}
