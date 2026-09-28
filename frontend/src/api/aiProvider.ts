/**
 * @author HXN
 * @date 2026-09-28
 * @description AI 服务提供商模块 API
 */
import request from './request'

/** 单个提供商只读状态（凭据不回显，仅配置完整性） */
export interface AiProviderInfo {
  provider: string
  displayName: string
  active: boolean
  configured: boolean
  baseUrl?: string
  chatModel?: string
  agentId?: string
  environmentId?: string
  credentialConfigured: boolean
}

/** 提供商状态响应 */
export interface AiProviderStatus {
  currentProvider: string
  providers: AiProviderInfo[]
}

/** 查询 AI 提供商状态（当前启用项 + 连接信息） */
export function getAiProviderStatus() {
  return request.get('/v1/ai/provider')
}

/** 切换当前启用的提供商（bailian / qoder） */
export function switchAiProvider(provider: string) {
  return request.post('/v1/ai/provider', { provider })
}
