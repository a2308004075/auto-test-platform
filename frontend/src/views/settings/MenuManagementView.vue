<!--
 @author HXN
 @date 2026-08-22 13:28
 @description 菜单管理视图
-->
<script setup lang="ts">
/**
 * 菜单管理页面（仅 ADMIN）
 * 树形结构 + Popover 右键菜单（编辑/删除/启停）
 * 对标 svc-manager-web Menu.vue
 */
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getMenuTree,
  updateMenu,
  deleteMenu,
  toggleMenuStatus,
  sortMenus,
  type MenuTreeNode,
  type MenuCreateRequest,
  type MenuSortItem,
} from '@/api/menu'
import { getRegisteredComponents } from '@/utils/componentRegistry'
import { usePermissionStore } from '@/stores'
import { usePermission } from '@/composables/usePermission'
import PageHeader from '@/components/PageHeader/index.vue'

const permissionStore = usePermissionStore()
const { hasPermission } = usePermission()

// ===== 当前右键选中的菜单 ID（控制只显示一个 buttonlist） =====
const activeMenuId = ref<number | null>(null)

// ===== 树形数据 =====
const loading = ref(false)
const treeData = ref<MenuTreeNode[]>([])
const defaultExpandedKeys = ref<number[]>([])

// ===== 弹窗 =====
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const flatMenuList = ref<MenuTreeNode[]>([])

const form = reactive<MenuCreateRequest & { parentId: number }>({
  parentId: 0,
  name: '',
  menuType: 2,
  icon: '',
  routePath: '',
  component: '',
  sortNo: 0,
})

// ===== 菜单类型选项 =====
const menuTypeOptions = [
  { value: 1, label: '目录' },
  { value: 2, label: '菜单' },
  { value: 3, label: '按钮' },
]

// ===== 可选组件列表（从注册表获取） =====
const componentOptions = getRegisteredComponents()

// ===== 排序编辑模式（拖拽调整目录/菜单的层级与顺序） =====
const sortMode = ref(false)
const saving = ref(false)
/** 进入编辑模式时的树快照（取消时还原） */
let sortSnapshot: MenuTreeNode[] = []

/** 进入编辑模式：拍快照、展开全部节点、禁用其他操作 */
function enterSortMode() {
  sortSnapshot = JSON.parse(JSON.stringify(treeData.value))
  sortMode.value = true
  closeContextMenu()
}

/** 仅目录/菜单可拖动，按钮不可单独拖动（跟随父级整体移动） */
function allowDrag(node: any) {
  return node.data.menuType === 1 || node.data.menuType === 2
}

/** dropNode 是否为 draggingNode 的子孙节点（禁止拖入自身子树，防循环） */
function isDescendantNode(draggingNode: any, dropNode: any): boolean {
  let parent = dropNode.parent
  while (parent && parent.level > 0) {
    if (parent.data && parent.data.id === draggingNode.data.id) return true
    parent = parent.parent
  }
  return false
}

/**
 * 拖拽放置约束：
 * - inner：目录/菜单只能放入目录内部（允许目录嵌套）
 * - prev/next：不能与按钮同级（按钮层级只能挂在菜单下）
 */
function allowDrop(draggingNode: any, dropNode: any, dropType: string) {
  if (isDescendantNode(draggingNode, dropNode)) return false
  if (dropType === 'inner') {
    return dropNode.data.menuType === 1
  }
  return dropNode.data.menuType !== 3
}

/** 遍历树收集排序项（parentId 按当前层级，sortNo 按同级顺序） */
function collectSortItems(nodes: MenuTreeNode[], parentId: number, result: MenuSortItem[]) {
  nodes.forEach((node, index) => {
    result.push({ id: node.id, parentId, sortNo: index })
    if (node.children && node.children.length) {
      collectSortItems(node.children, node.id, result)
    }
  })
}

/** 保存拖拽后的层级与顺序 */
async function handleSortSave() {
  const items: MenuSortItem[] = []
  collectSortItems(treeData.value, 0, items)
  saving.value = true
  try {
    await sortMenus(items)
    ElMessage.success('菜单层级与顺序已保存')
    sortMode.value = false
    sortSnapshot = []
    await fetchTree()
    // 同步刷新侧边栏的菜单树
    permissionStore.reloadMenuTree()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 取消编辑：还原进入编辑模式时的树快照 */
function handleSortCancel() {
  treeData.value = sortSnapshot
  sortMode.value = false
  sortSnapshot = []
}

// ===== 加载菜单树 =====
async function fetchTree() {
  loading.value = true
  try {
    const res: any = await getMenuTree()
    treeData.value = res.data || []
    defaultExpandedKeys.value = treeData.value.map((n) => n.id)
    flatMenuList.value = flattenTree(treeData.value)
  } catch {
    treeData.value = []
  } finally {
    loading.value = false
  }
}

function flattenTree(nodes: MenuTreeNode[]): MenuTreeNode[] {
  const result: MenuTreeNode[] = []
  for (const node of nodes) {
    result.push(node)
    if (node.children && node.children.length) {
      result.push(...flattenTree(node.children))
    }
  }
  return result
}

// ===== 编辑菜单 =====
function handleEdit(data: MenuTreeNode) {
  editingId.value = data.id
  form.parentId = data.parentId
  form.name = data.name
  form.menuType = data.menuType
  form.icon = data.icon || ''
  form.routePath = data.routePath || ''
  form.component = data.component || ''
  form.sortNo = data.sortNo || 0
  dialogVisible.value = true
}

// ===== 保存 =====
async function handleSave() {
  if (!form.name.trim()) {
    ElMessage.warning('菜单名称不能为空')
    return
  }
  try {
    const req: MenuCreateRequest = {
      parentId: form.parentId,
      name: form.name,
      menuType: form.menuType,
      icon: form.icon || undefined,
      routePath: form.routePath || undefined,
      component: form.component || undefined,
      sortNo: form.sortNo || 0,
    }
    if (editingId.value === null) return
    await updateMenu(editingId.value, req)
    ElMessage.success('菜单更新成功')
    dialogVisible.value = false
    fetchTree()
    // 同步刷新侧边栏的菜单树
    permissionStore.reloadMenuTree()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

// ===== 删除菜单 =====
async function handleDelete(data: MenuTreeNode) {
  try {
    await ElMessageBox.confirm(
      `确定删除菜单「${data.name}」？${data.children && data.children.length ? '（包含子菜单将一并删除）' : ''}`,
      '删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteMenu(data.id)
    ElMessage.success('菜单删除成功')
    fetchTree()
    permissionStore.reloadMenuTree()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

// ===== 切换启用/停用 =====
async function handleToggle(data: MenuTreeNode) {
  const action = data.isActive === 1 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(`确定${action}菜单「${data.name}」？`, '操作确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await toggleMenuStatus(data.id)
    ElMessage.success(`菜单已${action}`)
    fetchTree()
    permissionStore.reloadMenuTree()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

// ===== 右键菜单显示/隐藏控制 =====
function handleContextMenu(event: Event, id: number) {
  event.preventDefault()
  event.stopPropagation()
  // 编辑模式下禁用右键菜单（只允许拖拽）
  if (sortMode.value) return
  activeMenuId.value = id
}

function closeContextMenu() {
  activeMenuId.value = null
}

function handleDocumentClick(event: MouseEvent) {
  const target = event.target as HTMLElement
  if (!target.closest('.menu-popover')) {
    activeMenuId.value = null
  }
}

onMounted(() => {
  fetchTree()
  document.addEventListener('click', handleDocumentClick)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleDocumentClick)
})
</script>

<template>
  <div class="menu">
    <PageHeader title="菜单管理">
      <template v-if="!sortMode">
        <el-button v-if="hasPermission('system:menu:edit')" @click="enterSortMode">编辑</el-button>
      </template>
      <template v-else>
        <el-button type="primary" :loading="saving" @click="handleSortSave">保存</el-button>
        <el-button :disabled="saving" @click="handleSortCancel">取消</el-button>
      </template>
    </PageHeader>
    <div v-if="sortMode" class="sort-tip">
      编辑模式：拖动「目录 / 菜单」节点调整层级与顺序（虚线框 = 放入内部，横线 = 同级顺序），完成后点击「保存」生效
    </div>
    <div v-loading="loading" :class="['menu-list', { sorting: sortMode }]">
      <el-tree
        :key="sortMode ? 'sort' : 'view'"
        :data="treeData"
        node-key="id"
        :default-expanded-keys="defaultExpandedKeys"
        :default-expand-all="sortMode"
        :expand-on-click-node="false"
        :props="{ label: 'name', children: 'children' }"
        :draggable="sortMode"
        :allow-drag="allowDrag"
        :allow-drop="allowDrop"
        highlight-current
      >
        <template #default="{ data }">
          <el-popover
            :visible="activeMenuId === data.id"
            placement="right"
            trigger="manual"
            popper-class="menu-popover"
            :width="100"
          >
            <div class="button-list" @click="closeContextMenu">
              <el-button v-if="hasPermission('system:menu:edit')" link type="primary" @click="handleEdit(data)">编辑</el-button>
              <el-button
                v-if="hasPermission('system:menu:toggle')"
                link
                :type="data.isActive === 1 ? 'warning' : 'success'"
                @click="handleToggle(data)"
              >
                {{ data.isActive === 1 ? '停用' : '启用' }}
              </el-button>
              <el-button v-if="hasPermission('system:menu:delete')" link type="danger" @click="handleDelete(data)">删除</el-button>
            </div>
            <template #reference>
              <span class="tree-node-label" @contextmenu.prevent.stop="handleContextMenu($event, data.id)">
                <el-tag v-if="data.menuType === 1" size="small" type="info" class="type-tag">目录</el-tag>
                <el-tag v-else-if="data.menuType === 2" size="small" type="success" class="type-tag">菜单</el-tag>
                <el-tag v-else size="small" type="warning" class="type-tag">按钮</el-tag>
                <span class="node-name">{{ data.name }}</span>
                <span v-if="data.routePath" class="route-path">{{ data.routePath }}</span>
                <span v-if="data.component" class="component-path">{{ data.component }}</span>
                <el-tag v-if="data.isActive === 0" size="small" type="danger" class="status-tag">已停用</el-tag>
              </span>
            </template>
          </el-popover>
        </template>
      </el-tree>
    </div>

    <!-- 编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      draggable
      title="编辑菜单"
      :close-on-click-modal="false"
    >
      <el-form :model="form" label-width="90px">
        <el-form-item label="上级菜单">
          <el-tree-select
            v-model="form.parentId"
            :data="treeData"
            :props="{ label: 'name', value: 'id', children: 'children' }"
            :render-after-expand="false"
            check-strictly
            placeholder="无（顶级菜单）"
            clearable
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item label="菜单名称" required>
          <el-input v-model="form.name" placeholder="请输入菜单名称" />
        </el-form-item>
        <el-form-item label="菜单类型" required>
          <el-radio-group v-model="form.menuType">
            <el-radio v-for="opt in menuTypeOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="图标">
          <el-input v-model="form.icon" placeholder="Element Plus 图标名（可选）" />
        </el-form-item>
        <el-form-item label="路由路径">
          <el-input v-model="form.routePath" placeholder="如 /settings/profile（可选）" />
        </el-form-item>
        <el-form-item label="组件路径">
          <el-select
            v-model="form.component"
            placeholder="选择前端组件（可选）"
            clearable
            filterable
            allow-create
            style="width: 100%;"
          >
            <el-option
              v-for="comp in componentOptions"
              :key="comp"
              :label="comp"
              :value="comp"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="form.sortNo" :min="0" :controls="false" style="width: 120px;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.menu {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.menu-list {
  flex: 1;
  overflow: auto;
  background-color: var(--color-white, #fff);
  margin: 8px 24px 0 24px;
  border-radius: 4px;
  padding: 8px;
  border: 1px solid var(--border-color-base, #dcdfe6);
}

.tree-node-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  cursor: pointer;
}

.node-name {
  margin: 0 2px;
}

.route-path {
  font-size: 12px;
  color: var(--color-text-secondary, #909399);
}

.component-path {
  font-size: 12px;
  color: var(--el-color-primary, #409eff);
}

.type-tag {
  transform: scale(0.85);
  transform-origin: left center;
}

.sort-tip {
  margin: 0 24px;
  padding: 8px 12px;
  background-color: var(--el-color-primary-light-9, #ecf5ff);
  border: 1px solid var(--el-color-primary-light-7, #c6e2ff);
  border-radius: 4px;
  color: var(--el-text-color-regular, #606266);
  font-size: 13px;
}

.menu-list.sorting .tree-node-label {
  cursor: grab;
}

.status-tag {
  margin-left: 4px;
  transform: scale(0.85);
}
</style>

<style>
.menu-popover {
  min-width: 80px !important;
}

.menu-popover .button-list {
  display: grid;
}

.menu-popover .button-list .el-button {
  margin-left: 0;
  justify-content: flex-start;
}
</style>
