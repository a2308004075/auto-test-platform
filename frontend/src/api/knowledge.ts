/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库模块 API
 */
import request from './request'

// ===== 知识库 CRUD =====

/** 查询项目下的知识库列表 */
export function getKnowledgeBases(projectId: number) {
  return request.get(`/v1/projects/${projectId}/knowledge`)
}

/** 查询知识库详情 */
export function getKnowledgeBase(projectId: number, kbId: number) {
  return request.get(`/v1/projects/${projectId}/knowledge/${kbId}`)
}

/** 获取项目默认知识库（不存在则自动创建并触发资料全量同步） */
export function getDefaultKnowledgeBase(projectId: number) {
  return request.get(`/v1/projects/${projectId}/knowledge/default`)
}

/** 创建知识库 */
export function createKnowledgeBase(projectId: number, data: { name: string; description?: string }) {
  return request.post(`/v1/projects/${projectId}/knowledge`, data)
}

/** 删除知识库 */
export function deleteKnowledgeBase(projectId: number, kbId: number) {
  return request.delete(`/v1/projects/${projectId}/knowledge/${kbId}`)
}

// ===== 资料同步 =====

/** 手动触发全量同步：项目资料 → 知识库 */
export function syncKnowledgeDocuments(projectId: number, kbId: number) {
  return request.post(`/v1/projects/${projectId}/knowledge/${kbId}/documents/sync`)
}

// ===== 对话问答 =====

/** 查询会话列表 */
export function getConversations(projectId: number, kbId: number) {
  return request.get(`/v1/projects/${projectId}/knowledge/${kbId}/chat/conversations`)
}

/** 查询会话消息列表 */
export function getConversationMessages(projectId: number, kbId: number, conversationId: number) {
  return request.get(`/v1/projects/${projectId}/knowledge/${kbId}/chat/conversations/${conversationId}/messages`)
}

/** 创建新会话 */
export function createConversation(projectId: number, kbId: number, title?: string) {
  return request.post(`/v1/projects/${projectId}/knowledge/${kbId}/chat/conversations`, null, {
    params: { title }
  })
}

/** 删除会话 */
export function deleteConversation(projectId: number, kbId: number, conversationId: number) {
  return request.delete(`/v1/projects/${projectId}/knowledge/${kbId}/chat/conversations/${conversationId}`)
}

/** 发送消息（流式 SSE）- fetch POST 专用 URL（与 axios baseURL 逻辑一致） */
export function getStreamUrl(projectId: number, kbId: number): string {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  return `${base}/v1/projects/${projectId}/knowledge/${kbId}/chat/stream`
}
