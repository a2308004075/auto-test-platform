/**
 * @author HXN
 * @date 2026-08-30
 * @description 缺陷管理模块 API
 */
import request from './request'

// ===== 缺陷 API =====

export function getDefects(projectId: number, params?: {
  groupId?: number; keyword?: string; status?: string; severity?: string;
  assigneeId?: number; createdAtStart?: string; createdAtEnd?: string; customFilters?: string;
  page?: number; pageSize?: number
}) {
  return request.get(`/v1/projects/${projectId}/defects`, { params })
}

export function getDefect(projectId: number, defectId: number) {
  return request.get(`/v1/projects/${projectId}/defects/${defectId}`)
}

export function createDefect(projectId: number, data: any) {
  return request.post(`/v1/projects/${projectId}/defects`, data)
}

export function updateDefect(projectId: number, defectId: number, data: any) {
  return request.post(`/v1/projects/${projectId}/defects/${defectId}`, data)
}

export function deleteDefect(projectId: number, defectId: number) {
  return request.post(`/v1/projects/${projectId}/defects/${defectId}/delete`)
}

export function transitionDefectStatus(projectId: number, defectId: number, data: { targetStatus: string; remark?: string }) {
  return request.post(`/v1/projects/${projectId}/defects/${defectId}/transition`, data)
}

export function getMyDefectTasks(userId?: number) {
  // 跨项目查询，projectId 传 0 占位
  return request.get(`/v1/projects/0/defects/my-tasks`, { params: { userId } })
}

// ===== 缺陷分组 API =====

export function getDefectGroups(projectId: number) {
  return request.get(`/v1/projects/${projectId}/defect-groups`)
}

export function createDefectGroup(projectId: number, data: { parentId?: number | null; name: string; description?: string }) {
  return request.post(`/v1/projects/${projectId}/defect-groups`, data)
}

export function updateDefectGroup(projectId: number, groupId: number, data: { parentId?: number | null; name?: string; description?: string }) {
  return request.post(`/v1/projects/${projectId}/defect-groups/${groupId}`, data)
}

export function deleteDefectGroup(projectId: number, groupId: number) {
  return request.post(`/v1/projects/${projectId}/defect-groups/${groupId}/delete`)
}

export function clearDefectGroupDefects(projectId: number, groupId: number) {
  return request.post(`/v1/projects/${projectId}/defect-groups/${groupId}/clear-defects`)
}

export function clearDefectProjectDefects(projectId: number) {
  return request.post(`/v1/projects/${projectId}/defect-groups/clear-all-defects`)
}

// ===== 关联 API =====

export function addDefectRelation(projectId: number, defectId: number, data: any) {
  return request.post(`/v1/projects/${projectId}/defects/${defectId}/relations`, data)
}

export function deleteDefectRelation(projectId: number, defectId: number, relationId: number) {
  return request.post(`/v1/projects/${projectId}/defects/${defectId}/relations/${relationId}/delete`)
}

// ===== 附件 API =====

export function addDefectAttachment(projectId: number, defectId: number, params: { fileName: string; fileUrl: string; fileSize?: number }) {
  return request.post(`/v1/projects/${projectId}/defects/${defectId}/attachments`, null, { params })
}

export function deleteDefectAttachment(projectId: number, defectId: number, attachmentId: number) {
  return request.post(`/v1/projects/${projectId}/defects/${defectId}/attachments/${attachmentId}/delete`)
}
