/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 执行模块 API
 */
import request from './request'

/**
 * 测试执行模块 API（M9）
 */

export function startExecution(planId: number, data?: { environmentId?: number; triggerType?: string }) {
  return request.post(`/v1/plans/${planId}/executions`, data || {})
}

export function getExecutions(projectId: number, params?: {
  planName?: string
  environmentId?: number
  status?: string
  triggerType?: string
  startedAtFrom?: string
  startedAtTo?: string
  finishedAtFrom?: string
  finishedAtTo?: string
  page?: number
  pageSize?: number
}) {
  return request.get(`/v1/projects/${projectId}/executions`, { params })
}

export function getExecution(executionId: number) {
  return request.get(`/v1/executions/${executionId}`)
}

export function getExecutionResults(executionId: number) {
  return request.get(`/v1/executions/${executionId}/results`)
}

export function cancelExecution(executionId: number) {
  return request.post(`/v1/executions/${executionId}/cancel`)
}

export function updateManualCaseResult(executionId: number, data: {
  resultId: number
  status: 'PASSED' | 'FAILED' | 'SKIPPED'
  actualResult?: string
  errorMessage?: string
}) {
  return request.post(`/v1/executions/${executionId}/manual-results`, data)
}

// ===== 手动计划执行工作台（执行页） =====

/** 获取计划级自定义结果列定义（按 sortNo 升序） */
export function getPlanResultColumns(planId: number) {
  return request.get(`/v1/plans/${planId}/result-columns`)
}

/** 添加计划级结果列 */
export function createResultColumn(planId: number, data: { columnName: string }) {
  return request.post(`/v1/plans/${planId}/result-columns`, data)
}

/** 重命名结果列 */
export function renameResultColumn(columnId: number, data: { columnName: string }) {
  return request.post(`/v1/result-columns/${columnId}`, data)
}

/** 上移/下移结果列 */
export function moveResultColumn(columnId: number, data: { direction: 'up' | 'down' }) {
  return request.post(`/v1/result-columns/${columnId}/move`, data)
}

/** 删除结果列（进行中单的历史值保留不清理） */
export function deleteResultColumn(columnId: number) {
  return request.post(`/v1/result-columns/${columnId}/delete`)
}

/** 获取或创建计划当前进行中的手动执行单（执行页加载时调用） */
export function getCurrentExecution(planId: number) {
  return request.get(`/v1/plans/${planId}/current-execution`)
}

/** 更新多轮结果单元格（覆盖式；status 与 remark 皆空则清除该格） */
export function updateRoundResult(executionId: number, data: {
  resultId: number
  columnId: number
  status?: string
  remark?: string
}) {
  return request.post(`/v1/executions/${executionId}/round-results`, data)
}

/** 手动执行完成（快照列定义，形成测试记录） */
export function completeExecution(executionId: number) {
  return request.post(`/v1/executions/${executionId}/complete`)
}
