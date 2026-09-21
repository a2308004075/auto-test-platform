/**
 * @author HXN
 * @date 2026-08-22
 * @description 前端组件注册表
 * 将组件标识符映射到懒加载的 Vue 组件，驱动动态路由生成
 * 数据库 sys_menu.component 字段存储组件标识符（如 "settings/ProfileView"）
 */

/**
 * 组件注册表：key -> 懒加载组件
 *
 * 新增页面时，只需在此处注册组件并在菜单管理中配置对应 component 值即可
 */
export const componentMap: Record<string, () => Promise<any>> = {
  // ===== 项目模块 =====
  'project/ProjectList': () => import('@/views/project/ProjectList.vue'),
  'project/ProjectDashboard': () => import('@/views/project/ProjectDashboard.vue'),

  // ===== 接口模块 =====
  'api/ApiList': () => import('@/views/api/ApiList.vue'),
  'api/ApiEdit': () => import('@/views/api/ApiEdit.vue'),
  'api/ApiDebug': () => import('@/views/api/ApiDebug.vue'),
  'api/ApiSyncConfig': () => import('@/views/api/ApiSyncConfig.vue'),

  // ===== 环境模块 =====
  'environment/EnvironmentList': () => import('@/views/environment/EnvironmentList.vue'),
  'environment/EnvironmentEdit': () => import('@/views/environment/EnvironmentEdit.vue'),

  // ===== 测试代码库模块 =====
  'repository/RepositoryList': () => import('@/views/repository/RepositoryList.vue'),

  // ===== 界面元素模块 =====
  'uielement/UiElementList': () => import('@/views/uielement/UiElementList.vue'),

  // ===== 需求文档模块 =====
  'requirement/RequirementList': () => import('@/views/requirement/RequirementList.vue'),
  'requirement/RequirementEdit': () => import('@/views/requirement/RequirementEdit.vue'),

  // ===== 项目文档模块 =====
  'projectdoc/ProjectDocList': () => import('@/views/projectdoc/ProjectDocList.vue'),

  // ===== 关键字模块 =====
  'keywords/KeywordList': () => import('@/views/keywords/KeywordList.vue'),
  'keywords/KeywordEdit': () => import('@/views/keywords/KeywordEdit.vue'),

  // ===== 工具模块 =====
  'tool/ToolList': () => import('@/views/tool/ToolList.vue'),
  'tool/ToolEdit': () => import('@/views/tool/ToolEdit.vue'),

  // ===== Action 模块 =====
  'action/ActionList': () => import('@/views/action/ActionList.vue'),
  'action/ActionCreate': () => import('@/views/action/ActionCreate.vue'),
  'action/ActionEditor': () => import('@/views/action/ActionEditor.vue'),
  'action/ActionDebug': () => import('@/views/action/ActionDebug.vue'),

  // ===== 自动化套件/自动化用例模块 =====
  'cases/AutoSuiteList': () => import('@/views/cases/AutoSuiteList.vue'),
  'cases/AutoSuiteEdit': () => import('@/views/cases/AutoSuiteEdit.vue'),
  'cases/AutoCaseList': () => import('@/views/cases/AutoCaseList.vue'),
  'cases/AutoCaseEdit': () => import('@/views/cases/AutoCaseEdit.vue'),

  // ===== 手动化用例模块 =====
  'manualcase/ManualCaseList': () => import('@/views/manualcase/ManualCaseList.vue'),
  'manualcase/ManualCaseEdit': () => import('@/views/manualcase/ManualCaseEdit.vue'),

  // ===== 缺陷管理模块（新建/详情统一 DefectDetail 组件） =====
  'defect/DefectList': () => import('@/views/defect/DefectList.vue'),
  'defect/DefectDetail': () => import('@/views/defect/DefectDetail.vue'),

  // ===== AI 渗透测试模块 =====
  'ai/AiPenTest': () => import('@/views/ai/AiPenTest.vue'),

  // ===== AI 白盒测试模块 =====
  'ai/AiWhitebox': () => import('@/views/ai/AiWhitebox.vue'),

  // ===== 知识库模块 =====
  'knowledge/KnowledgeChat': () => import('@/views/knowledge/KnowledgeChat.vue'),

  // ===== 测试计划/执行模块 =====
  'execution/PlanList': () => import('@/views/execution/PlanList.vue'),
  'execution/PlanEdit': () => import('@/views/execution/PlanEdit.vue'),
  'execution/ExecutionList': () => import('@/views/execution/ExecutionList.vue'),
  'execution/ExecutionDetail': () => import('@/views/execution/ExecutionDetail.vue'),

  // ===== 系统设置模块 =====
  'settings/ProfileView': () => import('@/views/settings/ProfileView.vue'),
  'settings/MyTasksView': () => import('@/views/settings/MyTasksView.vue'),
  'settings/UserManagementView': () => import('@/views/settings/UserManagementView.vue'),
  'settings/RoleManagementView': () => import('@/views/settings/RoleManagementView.vue'),
  'settings/GlobalConfigView': () => import('@/views/settings/GlobalConfigView.vue'),
  'settings/MenuManagementView': () => import('@/views/settings/MenuManagementView.vue'),
  'settings/CustomFieldView': () => import('@/views/settings/CustomFieldView.vue'),
}

/**
 * 获取所有已注册的组件标识符列表（供菜单管理页面下拉选择）
 */
export function getRegisteredComponents(): string[] {
  return Object.keys(componentMap)
}

/**
 * 根据组件标识符获取懒加载组件
 * @returns 组件加载函数，若未注册则返回 null
 */
export function resolveComponent(key: string): (() => Promise<any>) | null {
  return componentMap[key] || null
}
