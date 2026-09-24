/**
 * @author HXN
 * @date 2026-09-22
 * @description 内容模板管理 API
 */
import request from './request'

/**
 * 内容模板管理 API（【页面配置-内容模板】）
 */

export function getContentTemplates(params: { projectId?: number; bizType?: string }) {
  return request.get('/v1/content-templates', { params })
}

export function createContentTemplate(data: any) {
  return request.post('/v1/content-templates', data)
}

export function updateContentTemplate(id: number, data: any) {
  return request.post(`/v1/content-templates/${id}`, data)
}

export function deleteContentTemplate(id: number) {
  return request.post(`/v1/content-templates/${id}/delete`)
}
