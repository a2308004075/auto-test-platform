/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 测试计划模块 API
 */
import request from './request'

/**
 * 测试计划模块 API（M9）
 */

export function getPlans(projectId: number, params?: {
  keyword?: string
  groupId?: number | null
  triggerType?: string
  environmentId?: number | null
  status?: number
  updateBegin?: string
  updateEnd?: string
  suiteKeyword?: string
  planType?: string
  page?: number
  pageSize?: number
}) {
  return request.get(`/v1/projects/${projectId}/plans`, { params })
}

export function getPlan(planId: number) {
  return request.get(`/v1/plans/${planId}`)
}

export function createPlan(projectId: number, data: any) {
  return request.post(`/v1/projects/${projectId}/plans`, data)
}

export function updatePlan(planId: number, data: any) {
  return request.post(`/v1/plans/${planId}`, data)
}

export function deletePlan(planId: number) {
  return request.post(`/v1/plans/${planId}/delete`)
}

// ===== 计划分组 API =====

export function getPlanGroups(projectId: number) {
  return request.get(`/v1/projects/${projectId}/plan-groups`)
}

export function createPlanGroup(projectId: number, data: { name: string; description?: string; parentId?: number | null }) {
  return request.post(`/v1/projects/${projectId}/plan-groups`, data)
}

export function updatePlanGroup(groupId: number, data: { name: string; description?: string; parentId?: number | null }) {
  return request.post(`/v1/plan-groups/${groupId}`, data)
}

export function deletePlanGroup(groupId: number) {
  return request.post(`/v1/plan-groups/${groupId}/delete`)
}

/**
 * 清空分组及其子孙分组中的所有计划
 */
export function clearGroupPlans(projectId: number, groupId: number) {
  return request.post(`/v1/projects/${projectId}/plan-groups/${groupId}/clear-plans`)
}

/**
 * 清空项目下所有计划
 */
export function clearProjectPlans(projectId: number) {
  return request.post(`/v1/projects/${projectId}/plan-groups/clear-all-plans`)
}
