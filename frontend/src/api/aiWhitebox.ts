/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试模块 API
 */
import request from './request'

/**
 * AI 白盒测试 API
 */

// ===== 任务控制 =====

/** 启动白盒测试任务 */
export function startWhiteboxTask(projectId: number, data: {
  repositoryId: number
  requirementVersionId?: number
  baselineCommit?: string
}) {
  return request.post(`/v1/projects/${projectId}/ai-whitebox/tasks`, data)
}

/** 停止白盒测试任务 */
export function stopWhiteboxTask(projectId: number, taskId: number) {
  return request.post(`/v1/projects/${projectId}/ai-whitebox/tasks/${taskId}/stop`)
}

// ===== 任务查询 =====

/** 查询项目下的白盒测试任务列表 */
export function getWhiteboxTasks(projectId: number) {
  return request.get(`/v1/projects/${projectId}/ai-whitebox/tasks`)
}

/** 查询白盒测试任务详情（含进度与日志） */
export function getWhiteboxTask(projectId: number, taskId: number) {
  return request.get(`/v1/projects/${projectId}/ai-whitebox/tasks/${taskId}`)
}

// ===== 测试结果 =====

/** 查询任务的变更方法列表 */
export function getWhiteboxMethods(projectId: number, taskId: number) {
  return request.get(`/v1/projects/${projectId}/ai-whitebox/tasks/${taskId}/methods`)
}

/** 查询任务的生成测试列表 */
export function getWhiteboxTests(projectId: number, taskId: number) {
  return request.get(`/v1/projects/${projectId}/ai-whitebox/tasks/${taskId}/tests`)
}

/** 保存生成用例到手动用例库 */
export function saveWhiteboxTests(projectId: number, taskId: number, testIds: number[]) {
  return request.post(`/v1/projects/${projectId}/ai-whitebox/tasks/${taskId}/tests/save`, { testIds })
}

// ===== 报告 =====

/** 获取测试报告 */
export function getWhiteboxReport(projectId: number, taskId: number) {
  return request.get(`/v1/projects/${projectId}/ai-whitebox/tasks/${taskId}/report`)
}

/** 下载报告文件 */
export function downloadWhiteboxReport(projectId: number, taskId: number, format: string = 'markdown') {
  return request.get(`/v1/projects/${projectId}/ai-whitebox/tasks/${taskId}/report/download`, {
    params: { format },
    responseType: 'blob',
  })
}
