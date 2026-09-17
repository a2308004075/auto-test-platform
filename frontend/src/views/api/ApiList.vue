<!--
 @author HXN
 @date 2026-08-18 17:31
 @description API 接口列表视图
-->
<script setup lang="ts">
/**
 * 接口列表 - M4
 * 左侧分组面板 + 右侧高级搜索 + 批量操作 + 表字段调整 + 分页表格
 * 对齐原型 api-list.html（分组树形多层已支持，后端 ApiModule 含 parentId）
 */
import { ref, reactive, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getApis, deleteApi, batchDeleteApis, batchMoveApis, getModules, createModule, updateModule, deleteModule, clearModuleApis, clearProjectApis,
} from '@/api/apidoc'
import PageHeader from '@/components/PageHeader/index.vue'
import ProSearchCard from '@/components/ProSearchCard/index.vue'
import BatchBar from '@/components/BatchBar/index.vue'
import ColumnSettings, { type ColumnItem } from '@/components/ColumnSettings/index.vue'
import ProPagination from '@/components/ProPagination/index.vue'
import ApiDebugModal from '@/components/ApiDebugModal/index.vue'
import { useDict } from '@/composables/useDict'
import { usePermission } from '@/composables/usePermission'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))
const { hasPermission } = usePermission()

const { options: httpMethodOptions } = useDict('http_method')
const { options: sourceTypeOptions } = useDict('source_type')

// ===== 列表数据 =====
const loading = ref(false)
const list = ref<any[]>([])
const pagination = reactive({ current: 1, pageSize: 20, total: 0 })
const selectedRows = ref<any[]>([])

// ===== 搜索条件 =====
const search = reactive({ name: '', path: '', method: '', source: '' })

const methodColors: Record<string, string> = { GET: '', POST: 'success', PUT: 'warning', DELETE: 'danger', PATCH: 'info' }

// ===== 分组 =====
const modules = ref<any[]>([])
const activeModuleId = ref<number>(0) // 0 = 全部
const filterText = ref('')
const moduleMap = computed<Record<number, any>>(() => {
  const m: Record<number, any> = {}
  modules.value.forEach((mod) => { m[mod.id] = mod })
  return m
})
// 批量移动可选分组（用户分组 + 未分组系统分组）
const moveTargetModules = computed(() =>
  modules.value.filter((m) => m.isSystem !== 1 || m.name === '未分组')
)
// 分组树：全部(虚拟) + 系统分组(未分组等，排除全部) + 用户分组按 parentId 建树
const moduleTree = computed(() => {
  const userGroups = modules.value.filter((m) => m.isSystem !== 1)
  const buildTree = (parentId: number | null): any[] =>
    userGroups
      .filter((m) => (m.parentId ?? null) === parentId)
      .map((m) => ({ ...m, children: buildTree(m.id) }))
  const systemGroups = modules.value
    .filter((m) => m.isSystem === 1 && m.name !== '全部')
    .map((m) => ({ ...m, children: [] }))
  return [
    { id: 0, name: '全部', isSystem: 1, apiCount: pagination.total, children: [] },
    ...systemGroups,
    ...buildTree(null),
  ]
})
// 根据搜索关键字过滤分组树
const filteredModuleTree = computed(() => {
  const kw = filterText.value.trim().toLowerCase()
  if (!kw) return moduleTree.value
  const matchRecursive = (nodes: any[]): any[] => {
    const result: any[] = []
    for (const node of nodes) {
      const childMatches = matchRecursive(node.children || [])
      if (node.name.toLowerCase().includes(kw) || childMatches.length > 0) {
        result.push({ ...node, children: childMatches.length > 0 ? childMatches : node.children })
      }
    }
    return result
  }
  return matchRecursive(moduleTree.value)
})
// 树形组件过滤回调
function filterNode(value: string, data: any) {
  if (!value) return true
  return data.name.toLowerCase().includes(value.toLowerCase())
}

function onModuleNodeClick(data: any) {
  selectModule(data.id)
}

async function fetchModules() {
  try {
    const res: any = await getModules(projectId.value)
    modules.value = res.data || []
  } catch { modules.value = [] }
}

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getApis(projectId.value, {
      moduleId: activeModuleId.value || undefined,
      keyword: search.name || undefined,
      path: search.path || undefined,
      httpMethod: search.method || undefined,
      source: search.source || undefined,
      page: pagination.current, pageSize: pagination.pageSize,
    })
    list.value = res.data?.items || []
    pagination.total = res.data?.total || 0
  } catch { list.value = [] } finally { loading.value = false }
}

function selectModule(id: number) {
  activeModuleId.value = id === activeModuleId.value ? 0 : id
  pagination.current = 1
  fetchList()
}

function handleSearch() { pagination.current = 1; fetchList() }
function handleReset() {
  Object.assign(search, { name: '', path: '', method: '', source: '' })
  handleSearch()
}

// ===== 分组拖拽 =====
function allowDrag(node: any) {
  // 系统分组不可拖拽（全部、未分组等）
  return node.data.isSystem !== 1
}
function allowDrop(_draggingNode: any, dropNode: any, dropType: string) {
  const target = dropNode.data
  // 不允许放入系统分组内部（全部、未分组等）
  if (dropType === 'inner' && target.isSystem === 1) return false
  return true
}
async function onNodeDrop(draggingNode: any, dropNode: any, dropType: string) {
  let parentId: number | null
  if (dropType === 'inner') {
    parentId = dropNode.data.id
  } else {
    // before / after → 与目标节点同级
    parentId = dropNode.data.parentId ?? null
  }
  try {
    await updateModule(projectId.value, draggingNode.data.id, { parentId })
    fetchModules()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '移动失败')
    fetchModules()
  }
}

// ===== 右键菜单 =====
const contextMenuVisible = ref(false)
const contextMenuPos = reactive({ x: 0, y: 0 })
const contextModule = ref<any>(null)

function handleNodeContextmenu(e: MouseEvent, data: any) {
  e.preventDefault()
  e.stopPropagation()
  // 系统分组仅允许"全部"(id=0)和"未分组"显示清空菜单
  if (data.isSystem === 1 && data.id !== 0 && data.name !== '未分组') return
  contextModule.value = data
  contextMenuPos.x = e.clientX
  contextMenuPos.y = e.clientY
  contextMenuVisible.value = true
}

function handleBlankContextmenu(e: MouseEvent) {
  e.preventDefault()
  contextModule.value = null
  contextMenuPos.x = e.clientX
  contextMenuPos.y = e.clientY
  contextMenuVisible.value = true
}

function closeContextMenu() {
  contextMenuVisible.value = false
  contextModule.value = null
}

function contextCreateGroup() {
  if (contextModule.value) {
    openCreateGroup(contextModule.value.id)
  } else {
    openCreateGroup()
  }
  closeContextMenu()
}

function contextCreateChild() {
  if (contextModule.value) openCreateGroup(contextModule.value.id)
  closeContextMenu()
}

function contextEdit() {
  if (contextModule.value) openEditGroup(contextModule.value)
  closeContextMenu()
}

function contextDelete() {
  if (contextModule.value) handleDeleteGroup(contextModule.value)
  closeContextMenu()
}

function contextClear() {
  if (!contextModule.value) return
  const m = contextModule.value
  closeContextMenu()
  const isAll = m.id === 0
  ElMessageBox.confirm(
    isAll
      ? '确定清空项目下的所有接口？此操作不可恢复。'
      : `确定清空分组「${m.name}」及其子分组中的所有接口？此操作不可恢复。`,
    '确认清空',
    { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' }
  )
    .then(async () => {
      if (isAll) {
        await clearProjectApis(projectId.value)
      } else {
        await clearModuleApis(projectId.value, m.id)
      }
      ElMessage.success('已清空')
      fetchModules()
      fetchList()
    })
    .catch(() => {})
}

// ===== 批量操作 =====
const selectedIds = computed(() => selectedRows.value.map((r: any) => r.id))
function handleSelectionChange(rows: any[]) { selectedRows.value = rows }

function handleBatchAction(key: string) {
  if (key === 'delete') handleBatchDelete()
  else if (key === 'move') batchMoveVisible.value = true
}
function clearSelection() { selectedRows.value = [] }

// ===== 批量删除 =====
const batchDeleteVisible = ref(false)
function handleBatchDelete() {
  batchDeleteVisible.value = true
}
async function confirmBatchDelete() {
  try {
    await batchDeleteApis(projectId.value, selectedIds.value)
    ElMessage.success('已删除 ' + selectedIds.value.length + ' 条接口')
    batchDeleteVisible.value = false
    clearSelection()
    fetchModules(); fetchList()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || '删除失败') }
}

// 批量改组
const batchMoveVisible = ref(false)
const batchMoveTarget = ref<number | null>(null)
const batchMovePreview = computed(() => {
  const target = modules.value.find((m: any) => m.id === batchMoveTarget.value)
  return selectedRows.value.map((r: any) => ({
    name: r.name,
    method: r.httpMethod,
    path: r.path,
    oldGroup: r.moduleName || moduleMap.value[r.moduleId]?.name || '未分组',
    newGroup: target?.name || '',
  }))
})
async function handleBatchMove() {
  if (!batchMoveTarget.value) { ElMessage.warning('请选择目标分组'); return }
  try {
    await batchMoveApis(projectId.value, batchMoveTarget.value, selectedIds.value)
    ElMessage.success('移动成功')
    batchMoveVisible.value = false
    batchMoveTarget.value = null
    clearSelection()
    fetchModules(); fetchList()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || '操作失败') }
}

// ===== 单条删除 =====
const deleteApiVisible = ref(false)
const deleteApiRow = ref<any>(null)
function handleDelete(record: any) {
  deleteApiRow.value = record
  deleteApiVisible.value = true
}
async function confirmDeleteApi() {
  if (!deleteApiRow.value) return
  try {
    await deleteApi(projectId.value, deleteApiRow.value.id)
    ElMessage.success('删除成功')
    deleteApiVisible.value = false
    deleteApiRow.value = null
    fetchModules(); fetchList()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || '删除失败') }
}

// ===== 分组 CRUD =====
const groupModalVisible = ref(false)
const editingGroupId = ref<number>(0)
const groupForm = reactive({ name: '', servicePrefix: '', description: '', parentId: null as number | null })

function openCreateGroup(parentId?: number | null) {
  editingGroupId.value = 0
  Object.assign(groupForm, { name: '', servicePrefix: '', description: '', parentId: parentId ?? null })
  groupModalVisible.value = true
}
function openEditGroup(m: any) {
  if (m.isSystem === 1) { ElMessage.info('系统分组不可编辑'); return }
  editingGroupId.value = m.id
  Object.assign(groupForm, { name: m.name, servicePrefix: m.servicePrefix || '', description: m.description || '', parentId: m.parentId ?? null })
  groupModalVisible.value = true
}
async function handleGroupSubmit() {
  if (!groupForm.name) { ElMessage.warning('请输入分组名称'); return }
  try {
    if (editingGroupId.value) {
      await updateModule(projectId.value, editingGroupId.value, groupForm)
      ElMessage.success('更新成功')
    } else {
      await createModule(projectId.value, { ...groupForm, projectId: projectId.value })
      ElMessage.success('创建成功')
    }
    groupModalVisible.value = false
    fetchModules()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || '操作失败') }
}
function handleDeleteGroup(m: any) {
  if (m.isSystem === 1) { ElMessage.info('系统分组不可删除'); return }
  ElMessageBox.confirm(`确定删除分组「${m.name}」？`, '确认删除', { type: 'warning' })
    .then(async () => { await deleteModule(projectId.value, m.id); ElMessage.success('删除成功'); fetchModules() })
    .catch(() => {})
}

// ===== 表字段调整 =====
const defaultColumns: ColumnItem[] = [
  { key: 'id', label: 'ID', locked: true, visible: true },
  { key: 'name', label: '接口名称', locked: true, visible: true },
  { key: 'path', label: '路径', locked: true, visible: true },
  { key: 'method', label: '方法', locked: true, visible: true },
  { key: 'group', label: '分组', locked: false, visible: true },
  { key: 'desc', label: '描述', locked: true, visible: true },
  { key: 'source', label: '来源', locked: true, visible: true },
  { key: 'createTime', label: '创建时间', locked: false, visible: true },
  { key: 'refCount', label: '被引用次数', locked: false, visible: true },
  { key: 'action', label: '操作', locked: true, visible: true },
]
const columns = ref<ColumnItem[]>(defaultColumns.map((c) => ({ ...c })))
function isColVisible(key: string) {
  return columns.value.find((c) => c.key === key)?.visible ?? false
}
function resetColumns() {
  columns.value = defaultColumns.map((c) => ({ ...c }))
}

// ===== 格式化 =====
function sourceLabel(t?: string) {
  if (!t) return '--'
  return sourceTypeOptions.value.find((s) => s.value === t)?.label || t
}
// 描述列：最多显示 10 个字符，超出用省略号，悬浮 title 显示全文
function descText(v?: string) {
  const s = v || ''
  return s.length > 10 ? s.slice(0, 10) + '...' : s
}
// ===== 调试弹窗 =====
const debugVisible = ref(false)
const debugApiId = ref(0)
function openDebug(id: number) {
  debugApiId.value = id
  debugVisible.value = true
}

// ===== 生命周期 =====
const treeRef = ref()
function onDocClick() { closeContextMenu() }
onMounted(() => {
  fetchModules(); fetchList()
  document.addEventListener('click', onDocClick)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
})
</script>

<template>
  <div>
    <PageHeader title="接口文档">
      <el-button v-if="hasPermission('project:api:swagger')" @click="router.push(`/project/${projectId}/apis/sync-configs`)">Swagger 同步</el-button>
      <el-button v-if="hasPermission('project:api:add')" type="primary" @click="router.push(`/project/${projectId}/apis/new`)">+ 新建接口</el-button>
    </PageHeader>

    <div class="api-layout">
      <!-- 左侧分组 -->
      <div class="group-panel" @contextmenu="handleBlankContextmenu">
        <div class="group-head">
          <span class="group-title">分组</span>
        </div>
        <div class="tree-search">
          <el-input v-model="filterText" size="small" placeholder="搜索分组..." clearable @input="(v: string) => treeRef?.filter(v)" />
        </div>
        <div class="group-tree">
          <el-tree
            ref="treeRef"
            :data="filteredModuleTree"
            node-key="id"
            :props="{ label: 'name', children: 'children' }"
            :default-expand-all="true"
            :expand-on-click-node="false"
            :filter-node-method="filterNode"
            :draggable="true"
            :allow-drag="allowDrag"
            :allow-drop="allowDrop"
            @node-click="onModuleNodeClick"
            @node-drop="onNodeDrop"
          >
            <template #default="{ data }">
              <div
                :class="['group-tree-node', { active: activeModuleId === data.id }]"
                @contextmenu.stop="handleNodeContextmenu($event, data)"
              >
                <span class="group-name">{{ data.name }}</span>
                <span class="group-count">{{ data.apiCount ?? 0 }}</span>
                <span v-if="data.isSystem === 1" class="group-lock" title="系统默认分组">🔒</span>
              </div>
            </template>
          </el-tree>
        </div>
      </div>

      <!-- 右侧内容 -->
      <div class="api-content">
        <ProSearchCard :loading="loading" @search="handleSearch" @reset="handleReset">
          <div class="pro-search-field">
            <span class="pro-search-label">接口名称</span>
            <el-input v-model="search.name" placeholder="输入接口名称" clearable style="width: 180px" @keyup.enter="handleSearch" />
          </div>
          <div class="pro-search-field">
            <span class="pro-search-label">接口路径</span>
            <el-input v-model="search.path" placeholder="输入接口路径" clearable style="width: 180px" @keyup.enter="handleSearch" />
          </div>
          <div class="pro-search-field">
            <span class="pro-search-label">请求方法</span>
            <el-select v-model="search.method" placeholder="全部方法" clearable style="width: 140px">
              <el-option v-for="m in httpMethodOptions" :key="m.value" :value="m.value" :label="m.label" />
            </el-select>
          </div>
          <template #collapse>
            <div class="pro-search-field">
              <span class="pro-search-label">接口来源</span>
              <el-select v-model="search.source" placeholder="全部来源" clearable style="width: 160px">
                <el-option v-for="s in sourceTypeOptions" :key="s.value" :value="s.value" :label="s.label" />
              </el-select>
            </div>
          </template>
        </ProSearchCard>

        <div class="table-toolbar">
          <ColumnSettings
            :columns="columns"
            @update:columns="(v: ColumnItem[]) => (columns = v)"
            @reset="resetColumns"
          />
        </div>

        <BatchBar
          v-if="hasPermission('project:api:batch')"
          :selected-count="selectedIds.length"
          :actions="[{ key: 'move', label: '批量修改分组' }, { key: 'delete', label: '批量删除', danger: true }]"
          @action="handleBatchAction"
          @clear="clearSelection"
        />

        <el-table :data="list" v-loading="loading" border stripe style="width: 100%" @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="45" />
          <el-table-column v-if="isColVisible('id')" prop="id" label="ID" width="70" />
          <el-table-column v-if="isColVisible('name')" prop="name" label="接口名称" width="240" show-overflow-tooltip />
          <el-table-column v-if="isColVisible('path')" label="路径" min-width="280" show-overflow-tooltip>
            <template #default="{ row }">
              <span style="font-family: monospace">{{ (row.path || '').replace(/^\$\{[^}]*\}/, '') }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="isColVisible('method')" prop="httpMethod" label="方法" width="80" />
          <el-table-column v-if="isColVisible('group')" label="分组" width="120">
            <template #default="{ row }">{{ row.moduleName || moduleMap[row.moduleId]?.name || '--' }}</template>
          </el-table-column>
          <el-table-column v-if="isColVisible('desc')" label="描述" width="180">
            <template #default="{ row }">
              <span :title="row.description" style="white-space: nowrap;">{{ descText(row.description) }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="isColVisible('source')" label="来源" width="120">
            <template #default="{ row }">{{ sourceLabel(row.sourceType) }}</template>
          </el-table-column>
          <el-table-column v-if="isColVisible('createTime')" label="创建时间" width="120">
            <template #default="{ row }">{{ row.createdAt?.substring(0, 10) }}</template>
          </el-table-column>
          <el-table-column v-if="isColVisible('refCount')" label="被引用次数" width="110" align="center">
            <template #default="{ row }">{{ row.refCount ?? 0 }}</template>
          </el-table-column>
          <el-table-column v-if="isColVisible('action')" label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <el-button v-if="hasPermission('project:api:edit')" type="primary" link size="small" @click="router.push(`/project/${projectId}/apis/${row.id}/edit`)">编辑</el-button>
              <el-button type="primary" link size="small" @click="openDebug(row.id)">调试</el-button>
              <el-button v-if="hasPermission('project:api:delete')" type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <ProPagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          @change="(p: number) => { pagination.current = p; fetchList() }"
        />
      </div>
    </div>

    <!-- 分组新建/编辑弹窗 -->
    <el-dialog v-model="groupModalVisible" :title="editingGroupId ? '编辑分组' : '新建分组'" width="460px">
      <el-form label-position="top">
        <el-form-item label="分组名称" required>
          <el-input v-model="groupForm.name" placeholder="如：用户管理服务" />
        </el-form-item>
        <el-form-item label="服务前缀">
          <el-input v-model="groupForm.servicePrefix" placeholder="可选，如 /api/users" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="groupForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupModalVisible = false">取消</el-button>
        <el-button type="primary" @click="handleGroupSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 批量修改分组弹窗 -->
    <el-dialog v-model="batchMoveVisible" title="批量修改分组" width="480px">
      <p style="margin: 0 0 12px; color: #606266; font-size: 13px;">
        将选中的 <b style="color: #409eff">{{ selectedIds.length }}</b> 条接口的分组修改为：
      </p>
      <el-select v-model="batchMoveTarget" placeholder="选择目标分组" filterable style="width: 100%; margin-bottom: 12px">
        <el-option v-for="m in moveTargetModules" :key="m.id" :value="m.id" :label="m.name" />
      </el-select>
      <div v-if="batchMovePreview.length" class="batch-preview">
        <div class="batch-preview-title">以下接口的分组将被修改：</div>
        <div class="batch-preview-list">
          <div v-for="item in batchMovePreview" :key="item.name" class="batch-preview-item">
            <span class="batch-preview-name">{{ item.name }}</span>
            <span class="batch-preview-old">{{ item.oldGroup }}</span>
            <span class="batch-preview-arrow">→</span>
            <span :class="['batch-preview-new', { empty: !item.newGroup }]">{{ item.newGroup || '请选择目标分组' }}</span>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="batchMoveVisible = false">取消</el-button>
        <el-button type="primary" @click="handleBatchMove">确认修改</el-button>
      </template>
    </el-dialog>

    <!-- 单条删除确认弹窗 -->
    <el-dialog v-model="deleteApiVisible" title="删除接口" width="400px">
      <p style="font-size: 14px; color: #606266; line-height: 1.6; margin: 0">
        确定删除接口 <strong style="color: #303133">{{ deleteApiRow?.name }}</strong> 吗？<br>
        <span style="color: #909399; font-size: 13px;">删除后将无法恢复。</span>
      </p>
      <template #footer>
        <el-button @click="deleteApiVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmDeleteApi">确认删除</el-button>
      </template>
    </el-dialog>

    <!-- 批量删除确认弹窗 -->
    <el-dialog v-model="batchDeleteVisible" title="批量删除接口" width="440px">
      <p style="font-size: 14px; color: #606266; line-height: 1.6; margin: 0 0 12px">
        确定删除已选中的 <strong style="color: #f56c6c">{{ selectedIds.length }}</strong> 条接口吗？<br>
        <span style="color: #909399; font-size: 13px;">删除后将无法恢复，请谨慎操作。</span>
      </p>
      <div v-if="selectedRows.length" class="batch-preview">
        <div class="batch-preview-title">以下接口将被删除：</div>
        <div class="batch-preview-list">
          <div v-for="row in selectedRows" :key="row.id" class="batch-preview-item">
            <el-tag :type="methodColors[row.httpMethod] || 'info'" size="small" style="margin-right: 6px">{{ row.httpMethod }}</el-tag>
            <span style="color: #606266; font-family: monospace; font-size: 12px">{{ row.path }}</span>
            <span style="color: #909399; font-size: 12px; margin-left: 4px">({{ row.name }})</span>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="batchDeleteVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmBatchDelete">确认删除</el-button>
      </template>
    </el-dialog>

    <!-- 调试弹窗 -->
    <ApiDebugModal v-model="debugVisible" :project-id="projectId" :api-id="debugApiId" />

    <!-- 右键上下文菜单 -->
    <Teleport to="body">
      <div
        v-if="contextMenuVisible"
        class="context-menu"
        :style="{ left: contextMenuPos.x + 'px', top: contextMenuPos.y + 'px' }"
        @click.stop
      >
        <!-- 空白区域右键：仅显示"新建分组" -->
        <template v-if="!contextModule">
          <div v-if="hasPermission('project:api:group')" class="context-menu-item" @click="contextCreateGroup">新建分组</div>
        </template>
        <!-- 系统分组右键：仅允许清空 -->
        <template v-else-if="contextModule.isSystem === 1">
          <div class="context-menu-item danger" @click="contextClear">清空接口</div>
        </template>
        <!-- 用户分组右键 -->
        <template v-else>
          <div v-if="hasPermission('project:api:group')" class="context-menu-item" @click="contextCreateChild">新建子分组</div>
          <div v-if="hasPermission('project:api:group')" class="context-menu-divider" />
          <div class="context-menu-item" @click="contextEdit">编辑</div>
          <div class="context-menu-item danger" @click="contextClear">清空接口</div>
          <div class="context-menu-item danger" @click="contextDelete">删除</div>
        </template>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.api-layout {
  display: flex;
  gap: 16px;
  min-height: calc(100vh - 164px);
}
.group-panel {
  width: 220px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.group-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.group-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}
.tree-search {
  margin: 8px 0;
}
.tree-search :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px #dcdfe6 inset;
  border-radius: 4px;
}
.group-tree {
  flex: 1;
  overflow-y: auto;
}
.group-tree :deep(.el-tree-node__content) {
  height: auto;
  padding: 2px 0;
}
.group-tree-node {
  display: flex;
  align-items: center;
  flex: 1;
  padding: 2px 4px;
  border-radius: 4px;
  font-size: 13px;
  gap: 6px;
  width: 100%;
}
.group-tree-node:hover {
  background: #f5f7fa;
}
.group-tree-node.active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 500;
}
.group-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-prefix {
  font-size: 11px;
  color: #c0c4cc;
  margin-left: 4px;
}
.group-count {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}
.group-lock {
  font-size: 10px;
  color: #c0c4cc;
  flex-shrink: 0;
  margin-left: 2px;
}
.api-content {
  flex: 1;
  min-width: 0;
}
.table-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

/* 右键上下文菜单 */
.context-menu {
  position: fixed;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  padding: 4px 0;
  min-width: 130px;
  z-index: 9999;
}
.context-menu-item {
  padding: 7px 14px;
  font-size: 13px;
  color: #303133;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: background 0.15s;
}
.context-menu-item:hover {
  background: #f5f7fa;
}
.context-menu-item.danger {
  color: #f56c6c;
}
.context-menu-item.danger:hover {
  background: #fef0f0;
}
.context-menu-divider {
  height: 1px;
  background: #ebeef5;
  margin: 4px 0;
}

/* 批量操作预览列表 */
.batch-preview {
  margin-top: 8px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 13px;
}
.batch-preview-title {
  color: #909399;
  margin-bottom: 6px;
}
.batch-preview-list {
  max-height: 160px;
  overflow-y: auto;
}
.batch-preview-item {
  padding: 3px 0;
  display: flex;
  align-items: center;
  gap: 6px;
}
.batch-preview-name {
  color: #606266;
}
.batch-preview-old {
  color: #909399;
  font-size: 12px;
}
.batch-preview-arrow {
  color: #909399;
  font-size: 12px;
}
.batch-preview-new {
  color: #409eff;
  font-weight: 500;
}
.batch-preview-new.empty {
  color: #c0c4cc;
  font-style: italic;
  font-weight: normal;
}
</style>
