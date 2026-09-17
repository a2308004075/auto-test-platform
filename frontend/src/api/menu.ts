/**
 * @author HXN
 * @date 2026-08-22 13:28
 * @description 菜单模块 API
 */
import request from './request'

/**
 * 菜单管理模块 API
 */

export interface MenuTreeNode {
  id: number
  parentId: number
  name: string
  menuType: number
  icon: string
  routePath: string
  component: string
  sortNo: number
  isActive: number
  permissionCode: string | null
  children?: MenuTreeNode[]
}

export interface MenuListItem {
  id: number
  parentId: number
  name: string
  menuType: number
  icon: string
  routePath: string
  component: string
  sortNo: number
  isActive: number
  permissionCode: string | null
  createdAt: string
  updatedAt: string
}

export interface MenuCreateRequest {
  parentId?: number
  name: string
  menuType: number
  icon?: string
  routePath?: string
  component?: string
  sortNo?: number
  permissionCode?: string
}

/** 获取菜单树（仅启用状态） */
export function getMenuTree() {
  return request.get('/v1/sys/menus/tree')
}

/** 获取所有菜单列表（扁平） */
export function getMenuList() {
  return request.get('/v1/sys/menus')
}

/** 获取单个菜单 */
export function getMenu(id: number) {
  return request.get(`/v1/sys/menus/${id}`)
}

/** 更新菜单 */
export function updateMenu(id: number, data: MenuCreateRequest) {
  return request.post(`/v1/sys/menus/${id}`, data)
}

/** 删除菜单 */
export function deleteMenu(id: number) {
  return request.delete(`/v1/sys/menus/${id}`)
}

/** 切换菜单启用/停用 */
export function toggleMenuStatus(id: number) {
  return request.post(`/v1/sys/menus/${id}/toggle`)
}

/** 菜单排序项（编辑模式拖拽保存） */
export interface MenuSortItem {
  id: number
  parentId: number
  sortNo: number
}

/** 批量更新菜单层级与顺序（编辑模式拖拽保存） */
export function sortMenus(items: MenuSortItem[]) {
  return request.post('/v1/sys/menus/sort', items)
}
