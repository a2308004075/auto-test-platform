/**
 * @author HXN
 * @date 2026-08-22
 * @description 动态路由工具
 * 从菜单树生成 Vue Router 路由，实现完全动态路由
 */
import type { Router } from 'vue-router'
import { resolveComponent } from '@/utils/componentRegistry'
import type { MenuTreeNode } from '@/api/menu'

/**
 * 从路由路径生成路由 name
 * 例：'settings/profile' -> 'SettingsProfile'
 *     'project/:id/apis' -> 'ProjectIdApis'
 *     'project/:id/executions/:executionId' -> 'ProjectIdExecutionsExecutionId'
 *
 * 注意：:param 段转为 Param 形式参与 name 生成（而非丢弃），确保不同路径生成唯一 name，
 * 避免列表路由与详情路由生成相同 name 导致后者覆盖前者。
 * 例：project/:id/executions 与 project/:id/executions/:executionId
 *     若丢弃参数段则两者均生成 'ProjectExecutions'，后注册的详情路由会覆盖列表路由。
 */
function generateRouteName(path: string): string {
  return path
    .replace(/^\//, '')
    .split('/')
    .filter(s => s)
    .map(s => {
      // :param 段去掉冒号后参与 name 生成，保证路径唯一性
      const seg = s.startsWith(':') ? s.slice(1) : s
      return seg.charAt(0).toUpperCase() + seg.slice(1)
    })
    .join('')
    || 'DynamicRoute'
}

/**
 * 将菜单树中的菜单项注册为 Layout 子路由
 * 递归处理目录和菜单项，跳过按钮类型（menuType=3）
 */
function addMenuRoutes(
  router: Router,
  menus: MenuTreeNode[],
  layoutName: string,
): void {
  for (const menu of menus) {
    // 跳过按钮类型
    if (menu.menuType === 3) continue

    // 有组件和路由路径的菜单项 → 注册为路由
    if (menu.component && menu.routePath) {
      const component = resolveComponent(menu.component)
      if (component) {
        // 路径去掉前导 /，作为 Layout 的相对子路径
        const childPath = menu.routePath.replace(/^\//, '')
        const isProject = menu.routePath.startsWith('/project/')
        const routeName = generateRouteName(childPath)

        router.addRoute(layoutName, {
          path: childPath,
          name: routeName,
          component,
          meta: {
            title: menu.name,
            inProject: isProject,
          },
        })
      }
    }

    // 递归处理子菜单
    if (menu.children && menu.children.length) {
      addMenuRoutes(router, menu.children, layoutName)
    }
  }
}

/**
 * 从菜单树生成全部动态路由并注册到 Router
 * @param router Vue Router 实例
 * @param menuTree 从后端获取的启用状态菜单树
 */
export function generateDynamicRoutes(
  router: Router,
  menuTree: MenuTreeNode[],
): void {
  addMenuRoutes(router, menuTree, 'Layout')
}

/**
 * 注册 /settings → /settings/profile 重定向路由
 */
export function addSettingsRedirect(router: Router, layoutName: string): void {
  router.addRoute(layoutName, {
    path: 'settings',
    redirect: '/settings/profile',
  })
}

/**
 * 注册不在菜单系统中但需要路由的子页面（新建/编辑/详情/导入等）
 * 这些页面不显示在侧边栏，仅通过列表页导航到达
 * 必须在 generateDynamicRoutes 之后、addCatchAllRoute 之前调用
 */
export function addSupplementaryRoutes(router: Router, layoutName: string): void {
  const routes: Array<{
    path: string
    component: string
    title: string
    /** 非项目内页面时置 false（默认 true） */
    inProject?: boolean
    /** 单菜单页面：侧边栏仅显示当前页自身一个菜单项（如我的任务） */
    singleMenu?: boolean
  }> = [
    // ===== 环境模块 =====
    { path: 'project/:id/environments/:envId/edit', component: 'environment/EnvironmentEdit', title: '编辑环境变量' },

    // ===== 接口模块 =====
    { path: 'project/:id/apis/new',              component: 'api/ApiEdit',          title: '新建接口' },
    { path: 'project/:id/apis/:apiId/edit',      component: 'api/ApiEdit',          title: '编辑接口' },
    { path: 'project/:id/apis/sync-configs',     component: 'api/ApiSyncConfig',    title: 'Swagger同步配置' },

    // ===== 关键字模块 =====
    { path: 'project/:id/keywords/new',               component: 'keywords/KeywordEdit',      title: '新建关键字' },
    { path: 'project/:id/keywords/:keywordId/edit',   component: 'keywords/KeywordEdit',      title: '编辑关键字' },

    // ===== 工具模块 =====
    { path: 'project/:id/tools/new',              component: 'tool/ToolEdit',  title: '新建工具方法' },
    { path: 'project/:id/tools/:toolId/edit',     component: 'tool/ToolEdit',  title: '编辑工具方法' },

    // ===== Action 模块 =====
    { path: 'project/:id/actions/new',             component: 'action/ActionCreate',   title: '新建Action关键字' },
    { path: 'project/:id/actions/:actionId/edit',  component: 'action/ActionEditor',  title: '编辑Action' },
    { path: 'project/:id/actions/:actionId/debug', component: 'action/ActionDebug',   title: '调试Action' },

    // ===== 自动化用例/自动化套件模块 =====
    { path: 'project/:id/auto-cases/new',                component: 'cases/AutoCaseEdit',    title: '新建自动化用例' },
    { path: 'project/:id/auto-cases/:autoCaseId/edit',   component: 'cases/AutoCaseEdit',    title: '编辑自动化用例' },
    { path: 'project/:id/auto-suites/:autoSuiteId/edit', component: 'cases/AutoSuiteEdit',   title: '步骤配置' },

    // ===== 手动化用例模块 =====
    { path: 'project/:id/manual-cases/new',          component: 'manualcase/ManualCaseEdit', title: '新建手动化用例' },
    { path: 'project/:id/manual-cases/:caseId/edit', component: 'manualcase/ManualCaseEdit', title: '编辑手动化用例' },

    // ===== 缺陷管理模块（新建/详情统一视图 DefectDetail：无 defectId 为新建模式，有则为详情模式） =====
    { path: 'project/:id/defects/new',              component: 'defect/DefectDetail', title: '新建缺陷' },
    { path: 'project/:id/defects/:defectId',         component: 'defect/DefectDetail', title: '缺陷详情' },

    // ===== 测试计划/执行模块 =====
    { path: 'project/:id/plans/new',                       component: 'execution/PlanEdit',        title: '新建计划' },
    { path: 'project/:id/plans/:planId/edit',              component: 'execution/PlanEdit',        title: '编辑计划' },
    { path: 'project/:id/executions/:executionId',         component: 'execution/ExecutionDetail', title: '执行详情' },

    // ===== 需求文档模块 =====
    { path: 'project/:id/requirements/new',                component: 'requirement/RequirementEdit', title: '新建需求' },
    { path: 'project/:id/requirements/:itemId/edit',       component: 'requirement/RequirementEdit', title: '编辑需求' },

    // ===== 知识库模块（菜单直达智能问答，知识库数据来源于项目资料同步） =====
    { path: 'project/:id/knowledge',                    component: 'knowledge/KnowledgeChat', title: '知识库智能问答' },

    // ===== 我的任务（单菜单页面：非项目内，侧边栏仅显示当前页一个菜单项） =====
    { path: 'settings/my-tasks', component: 'settings/MyTasksView', title: '我的任务', inProject: false, singleMenu: true },
  ]

  for (const r of routes) {
    const component = resolveComponent(r.component)
    if (component) {
      router.addRoute(layoutName, {
        path: r.path,
        name: generateRouteName(r.path),
        component,
        meta: { title: r.title, inProject: r.inProject !== false, singleMenu: r.singleMenu === true },
      })
    }
  }
}

/**
 * 注册全局 404 兜底路由（必须在所有动态路由注册完成后调用）
 */
export function addCatchAllRoute(router: Router): void {
  router.addRoute({
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/project/ProjectList.vue'),
    meta: { title: '404', noSidebar: true },
  })
}
