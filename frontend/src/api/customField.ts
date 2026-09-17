/**
 * @author HXN
 * @date 2026-08-30
 * @description 自定义字段管理 API
 */
import request from './request'

/**
 * 自定义字段管理 API
 */

export function getCustomFields(params: { projectId?: number; module?: string; viewType?: string }) {
  return request.get('/v1/custom-fields', { params })
}

export function getCustomFieldsForRender(params: { projectId: number; module: string; viewType: string }) {
  return request.get('/v1/custom-fields/render', { params })
}

export function createCustomField(data: any) {
  return request.post('/v1/custom-fields', data)
}

export function updateCustomField(id: number, data: any) {
  return request.post(`/v1/custom-fields/${id}`, data)
}

export function sortCustomFields(orderedIds: number[]) {
  return request.post('/v1/custom-fields/sort', { orderedIds })
}

export function deleteCustomField(id: number) {
  return request.post(`/v1/custom-fields/${id}/delete`)
}
