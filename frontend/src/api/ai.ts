/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 渗透测试模块 API
 */
import request from './request'

/**
 * AI 渗透测试 API
 */

// ===== 扫描控制 =====

/** 启动扫描 */
export function startScan(projectId: number, data: {
  repos: string[]
  envUrl: string
  authType?: string
  authConfig?: {
    loginUrl?: string
    usernameField?: string
    passwordField?: string
    username?: string
    password?: string
    token?: string
    totpSecret?: string
  }
  excludePaths?: string[]
}) {
  return request.post(`/v1/projects/${projectId}/ai-pentest/scan`, data)
}

/** 停止扫描 */
export function stopScan(projectId: number, taskId: number) {
  return request.post(`/v1/projects/${projectId}/ai-pentest/scan/${taskId}/stop`)
}

// ===== 任务查询 =====

/** 查询项目下的扫描任务列表 */
export function getScanTasks(projectId: number) {
  return request.get(`/v1/projects/${projectId}/ai-pentest/tasks`)
}

/** 查询扫描任务详情（含进度） */
export function getScanTask(projectId: number, taskId: number) {
  return request.get(`/v1/projects/${projectId}/ai-pentest/tasks/${taskId}`)
}

// ===== 扫描结果 =====

/** 查询任务发现的端点列表 */
export function getScanEndpoints(projectId: number, taskId: number) {
  return request.get(`/v1/projects/${projectId}/ai-pentest/tasks/${taskId}/endpoints`)
}

/** 查询任务发现的漏洞列表 */
export function getScanVulnerabilities(projectId: number, taskId: number) {
  return request.get(`/v1/projects/${projectId}/ai-pentest/tasks/${taskId}/vulnerabilities`)
}

// ===== 报告 =====

/** 获取扫描报告 */
export function getScanReport(projectId: number, taskId: number) {
  return request.get(`/v1/projects/${projectId}/ai-pentest/tasks/${taskId}/report`)
}

/** 下载报告文件 */
export function downloadReport(projectId: number, taskId: number, format: string = 'markdown') {
  return request.get(`/v1/projects/${projectId}/ai-pentest/tasks/${taskId}/report/download`, {
    params: { format },
    responseType: 'blob',
  })
}
