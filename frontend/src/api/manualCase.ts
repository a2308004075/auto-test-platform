/**
 * @author HXN
 * @date 2026-08-30
 * @description 手动化用例模块 API
 */
import request from './request'

// ===== 手动化用例 API =====

export function getManualCases(projectId: number, params?: {
  groupId?: number; keyword?: string; caseStatus?: string; customFilters?: string;
  page?: number; pageSize?: number
}) {
  return request.get(`/v1/projects/${projectId}/manual-cases`, { params })
}

export function getManualCase(projectId: number, caseId: number) {
  return request.get(`/v1/projects/${projectId}/manual-cases/${caseId}`)
}

export function createManualCase(projectId: number, data: any) {
  return request.post(`/v1/projects/${projectId}/manual-cases`, data)
}

export function updateManualCase(projectId: number, caseId: number, data: any) {
  return request.post(`/v1/projects/${projectId}/manual-cases/${caseId}`, data)
}

export function deleteManualCase(projectId: number, caseId: number) {
  return request.post(`/v1/projects/${projectId}/manual-cases/${caseId}/delete`)
}

export function toggleManualCaseStatus(projectId: number, caseId: number, targetStatus?: number) {
  return request.post(`/v1/projects/${projectId}/manual-cases/${caseId}/status`, null, {
    params: targetStatus != null ? { targetStatus } : undefined,
  })
}

// ===== 附件 API =====

export function addManualCaseAttachment(projectId: number, caseId: number, params: { fileName: string; fileUrl: string; fileSize?: number }) {
  return request.post(`/v1/projects/${projectId}/manual-cases/${caseId}/attachments`, null, { params })
}

export function deleteManualCaseAttachment(projectId: number, caseId: number, attachmentId: number) {
  return request.post(`/v1/projects/${projectId}/manual-cases/${caseId}/attachments/${attachmentId}/delete`)
}

// ===== 手动化用例分组 API =====

export function getManualCaseGroups(projectId: number) {
  return request.get(`/v1/projects/${projectId}/manual-case-groups`)
}

export function createManualCaseGroup(projectId: number, data: { parentId?: number | null; name: string; description?: string }) {
  return request.post(`/v1/projects/${projectId}/manual-case-groups`, data)
}

export function updateManualCaseGroup(projectId: number, groupId: number, data: { parentId?: number | null; name?: string; description?: string }) {
  return request.post(`/v1/projects/${projectId}/manual-case-groups/${groupId}`, data)
}

export function deleteManualCaseGroup(projectId: number, groupId: number) {
  return request.post(`/v1/projects/${projectId}/manual-case-groups/${groupId}/delete`)
}

export function clearManualGroupCases(projectId: number, groupId: number) {
  return request.post(`/v1/projects/${projectId}/manual-case-groups/${groupId}/clear-cases`)
}

export function clearManualProjectCases(projectId: number) {
  return request.post(`/v1/projects/${projectId}/manual-case-groups/clear-all-cases`)
}
