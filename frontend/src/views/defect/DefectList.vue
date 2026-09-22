<!--
 @author HXN
 @date 2026-08-30
 @description 缺陷列表视图
-->
<script setup lang="ts">
/**
 * 缺陷列表
 * 左侧分组树 + 右侧高级搜索 + 批量操作 + 分页表格
 */
import { ref, reactive, nextTick, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDefects, deleteDefect, updateDefect, transitionDefectStatus,
  getDefectGroups, createDefectGroup, updateDefectGroup,
  deleteDefectGroup, clearDefectGroupDefects, clearDefectProjectDefects
} from '@/api/defect'
import PageHeader from '@/components/PageHeader/index.vue'
import ProSearchCard from '@/components/ProSearchCard/index.vue'
import BatchBar from '@/components/BatchBar/index.vue'
import ProPagination from '@/components/ProPagination/index.vue'
import { useDefectStatusOptions } from '@/composables/useDefectStatus'
import { getCustomFieldsForRender } from '@/api/customField'
import { isScopeVisible } from '@/utils/customFieldScope'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))
// 状态选项优先读【字段管理-缺陷字段】的"状态"字段配置（按项目），无配置回退字典
const { options: statusOptions } = useDefectStatusOptions(() => projectId.value)

// ===== 列表数据 =====
const loading = ref(false)
const list = ref<any[]>([])
const pagination = reactive({ current: 1, pageSize: 20, total: 0 })
const selectedRows = ref<any[]>([])

// ===== 搜索条件 =====
const search = reactive({ keyword: '', status: '' })
// 动态字段筛选（fieldKey -> 值：单值字符串或日期范围 [start, end]）
const searchCustom = reactive<Record<string, any>>({})
// 创建时间范围筛选 [start, end]（yyyy-MM-dd）
const searchCreatedAt = ref<[string, string] | null>(null)

// ===== 分组 =====
const groups = ref<any[]>([])
const activeGroupId = ref<number>(0)
const filterText = ref('')
const userGroups = computed(() => groups.value.filter((g) => g.isSystem !== 1))
const ungroupedCount = computed(() => {
  const topUserGroups = groups.value.filter((g) => g.isSystem !== 1 && (g.parentId ?? null) === null)
  const groupedSum = topUserGroups.reduce((acc, g) => acc + (g.defectCount || 0), 0)
  return Math.max(0, pagination.total - groupedSum)
})
const groupTree = computed(() => {
  const userItems = groups.value.filter((g) => g.isSystem !== 1)
  const buildTree = (parentId: number | null): any[] =>
    userItems
      .filter((g) => (g.parentId ?? null) === parentId)
      .map((g) => ({ ...g, children: buildTree(g.id) }))
  return [
    { id: 0, name: '全部', isSystem: 1, defectCount: pagination.total, children: [] },
    { id: -1, name: '未分组', isSystem: 1, defectCount: ungroupedCount.value, children: [] },
    ...buildTree(null),
  ]
})
const filteredGroupTree = computed(() => {
  const kw = filterText.value.trim().toLowerCase()
  if (!kw) return groupTree.value
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
  return matchRecursive(groupTree.value)
})
function filterNode(value: string, data: any) {
  if (!value) return true
  return data.name.toLowerCase().includes(value.toLowerCase())
}

/** 搜索区分组筛选树（未分组 + 用户分组；与左侧分组树共用 activeGroupId，选中即联动高亮） */
const groupFilterTree = computed(() => {
  const build = (parentId: number | null): any[] =>
    groups.value
      .filter((g) => g.isSystem !== 1 && (g.parentId ?? null) === parentId)
      .map((g) => {
        const children = build(g.id)
        return { id: g.id, name: g.name, ...(children.length > 0 ? { children } : {}) }
      })
  return [{ id: -1, name: '未分组' }, ...build(null)]
})

function onGroupNodeClick(data: any) {
  selectGroup(data.id)
}

async function fetchGroups() {
  try {
    const res: any = await getDefectGroups(projectId.value)
    groups.value = res.data || []
  } catch { groups.value = [] }
}

async function fetchList() {
  loading.value = true
  try {
    const groupIdParam = activeGroupId.value === 0 ? undefined : activeGroupId.value === -1 ? 0 : activeGroupId.value
    // 动态字段筛选：组装 fieldKey -> 值（日期范围为 [start, end]，其余为字符串）
    const customFilters: Record<string, any> = {}
    for (const f of displayFields.value) {
      const v = searchCustom[f.fieldKey]
      if (v === undefined || v === null || v === '') continue
      if (Array.isArray(v)) {
        if (!v[0] && !v[1]) continue
        customFilters[f.fieldKey] = v.map((x: any) => (x ? String(x) : ''))
      } else {
        customFilters[f.fieldKey] = String(v)
      }
    }
    const res: any = await getDefects(projectId.value, {
      groupId: groupIdParam,
      keyword: search.keyword || undefined,
      status: search.status || undefined,
      createdAtStart: searchCreatedAt.value?.[0] || undefined,
      createdAtEnd: searchCreatedAt.value?.[1] || undefined,
      customFilters: Object.keys(customFilters).length > 0 ? JSON.stringify(customFilters) : undefined,
      page: pagination.current, pageSize: pagination.pageSize,
    })
    list.value = res.data?.items || []
    pagination.total = res.data?.total || 0
  } catch { list.value = [] } finally { loading.value = false }
}

/** 搜索区分组筛选：与左侧分组树联动（同一 activeGroupId），清空回到全部 */
function handleFilterGroupChange(val: number | undefined) {
  activeGroupId.value = (val ?? 0) as number
  pagination.current = 1
  fetchList()
}

function selectGroup(id: number) {
  activeGroupId.value = id === activeGroupId.value ? 0 : id
  pagination.current = 1
  fetchList()
}

function handleSearch() { pagination.current = 1; fetchList() }
function handleReset() {
  Object.assign(search, { keyword: '', status: '' })
  for (const key of Object.keys(searchCustom)) delete searchCustom[key]
  searchCreatedAt.value = null
  activeGroupId.value = 0
  handleSearch()
}

// ===== 右键菜单 =====
const contextMenuVisible = ref(false)
const contextMenuPos = reactive({ x: 0, y: 0 })
const contextGroup = ref<any>(null)

function handleNodeContextmenu(e: MouseEvent, data: any) {
  e.preventDefault()
  e.stopPropagation()
  contextGroup.value = data
  contextMenuPos.x = e.clientX
  contextMenuPos.y = e.clientY
  contextMenuVisible.value = true
}

function handleBlankContextmenu(e: MouseEvent) {
  e.preventDefault()
  contextGroup.value = null
  contextMenuPos.x = e.clientX
  contextMenuPos.y = e.clientY
  contextMenuVisible.value = true
}

function closeContextMenu() {
  contextMenuVisible.value = false
  contextGroup.value = null
}

function contextCreateGroup() {
  if (contextGroup.value) openCreateGroup(contextGroup.value.id)
  else openCreateGroup()
  closeContextMenu()
}

function contextCreateChild() {
  if (contextGroup.value) openCreateGroup(contextGroup.value.id)
  closeContextMenu()
}

function contextEdit() {
  if (contextGroup.value) openEditGroup(contextGroup.value)
  closeContextMenu()
}

function contextDelete() {
  if (contextGroup.value) handleDeleteGroup(contextGroup.value)
  closeContextMenu()
}

function contextClear() {
  if (!contextGroup.value) return
  const g = contextGroup.value
  closeContextMenu()
  const isAll = g.id === 0
  const isUngrouped = g.id === -1
  ElMessageBox.confirm(
    isAll
      ? '确定清空项目下的所有缺陷？此操作不可恢复。'
      : isUngrouped
        ? '确定清空「未分组」中的所有缺陷？此操作不可恢复。'
        : `确定清空分组「${g.name}」及其子分组中的所有缺陷？此操作不可恢复。`,
    '确认清空',
    { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' }
  )
    .then(async () => {
      if (isAll) {
        await clearDefectProjectDefects(projectId.value)
      } else {
        await clearDefectGroupDefects(projectId.value, isUngrouped ? 0 : g.id)
      }
      ElMessage.success('已清空')
      fetchGroups()
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

function handleBatchDelete() {
  ElMessageBox.confirm(
    `确定删除选中的 ${selectedIds.value.length} 条缺陷？`,
    '批量删除', { type: 'warning' },
  ).then(async () => {
    for (const id of selectedIds.value) {
      await deleteDefect(projectId.value, id)
    }
    ElMessage.success('删除成功')
    clearSelection()
    fetchGroups(); fetchList()
  }).catch(() => {})
}

// 批量改组
const batchMoveVisible = ref(false)
const batchMoveTarget = ref<number | null>(null)
async function handleBatchMove() {
  if (!batchMoveTarget.value) { ElMessage.warning('请选择目标分组'); return }
  try {
    for (const id of selectedIds.value) {
      await updateDefect(projectId.value, id, { groupId: batchMoveTarget.value })
    }
    ElMessage.success('移动成功')
    batchMoveVisible.value = false
    batchMoveTarget.value = null
    clearSelection()
    fetchGroups(); fetchList()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || '操作失败') }
}

// ===== 单条操作 =====
function openCreate() {
  router.push(`/project/${projectId.value}/defects/new`)
}

function handleView(record: any) {
  router.push(`/project/${projectId.value}/defects/${record.id}`)
}

// ===== 标题行内编辑（点击即改，失焦/回车自动保存） =====
const titleEditingId = ref<number | null>(null)
const titleEditingValue = ref('')
const titleInputRef = ref()

/** 点击缺陷标题：进入行内编辑 */
function startTitleEdit(row: any) {
  titleEditingId.value = row.id
  titleEditingValue.value = row.title || ''
  nextTick(() => {
    const input = titleInputRef.value as any
    if (input) {
      input.focus()
      if (typeof input.select === 'function') input.select()
    }
  })
}

/** 标题失焦/回车：退出编辑并自动保存（空标题回退原值） */
async function handleTitleBlur(row: any) {
  if (titleEditingId.value !== row.id) return
  titleEditingId.value = null
  const title = titleEditingValue.value.trim()
  if (!title) {
    ElMessage.warning('缺陷标题不能为空')
    return
  }
  if (title === (row.title || '')) return
  try {
    await updateDefect(projectId.value, row.id, { title })
    ElMessage.success('已保存')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    fetchList()
  }
}

// ===== 状态流转（列表内直接修改，调专用 transition 接口） =====

/** 每行的可选状态：NEW 为初始状态，流转出去后不允许切回（与详情页/后端校验一致） */
function selectableStatusOptions(row: any) {
  return row.status === 'NEW'
    ? statusOptions.value
    : statusOptions.value.filter((s: any) => s.value !== 'NEW')
}

/** 切换状态：成功后刷新列表；失败时下拉值随 model-value 自动回退 */
async function handleTransition(row: any, targetStatus: string) {
  if (targetStatus === row.status) return
  try {
    await transitionDefectStatus(projectId.value, row.id, { targetStatus })
    ElMessage.success('状态更新成功')
    fetchList()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

function handleDelete(record: any) {
  ElMessageBox.confirm(`确定删除缺陷「${record.defectNo}」？`, '确认删除', { type: 'warning' })
    .then(async () => { await deleteDefect(projectId.value, record.id); ElMessage.success('删除成功'); fetchGroups(); fetchList() })
    .catch(() => {})
}

// ===== 动态字段列（与详情页"字段信息"同源：【字段管理-缺陷字段】统一存 edit 视图） =====
const displayFields = ref<any[]>([])

/** 加载列表动态列：状态走专门列、多行文本内容长不进列表；不含"详情"位置的字段不进列表（列表属查看场景，与详情一致） */
async function fetchDisplayFields() {
  try {
    const res: any = await getCustomFieldsForRender({
      projectId: projectId.value,
      module: 'defect',
      viewType: 'edit',
    })
    displayFields.value = (res.data || []).filter(
      (f: any) => f.fieldKey !== 'defect_status' && f.fieldType !== 'textarea' && isScopeVisible(f.displayScope, 'detail'),
    )
  } catch { displayFields.value = [] }
}

/** 解析动态字段选项：优先 field.options，回退 optionsJson（与 DynamicFieldGrid 逻辑一致） */
function parseFieldOptions(field: any): any[] {
  if (field?.options && field.options.length > 0) return field.options
  if (!field?.optionsJson) return []
  try { return JSON.parse(field.optionsJson) } catch { return [] }
}

/** 动态字段列显示文本：下拉/用户/环境类按 value 翻译 label，其余原样显示 */
function fieldDisplayText(field: any, row: any): string {
  const value = row.customFields?.[field.fieldKey]
  if (value === undefined || value === null || value === '') return ''
  if (['select', 'user', 'environment'].includes(field.fieldType)) {
    const hit = parseFieldOptions(field).find((o: any) => String(o.value) === String(value))
    return hit ? hit.label : value
  }
  return value
}

// ===== 分组 CRUD =====
const groupModalVisible = ref(false)
const editingGroupId = ref<number>(0)
const groupForm = reactive({ name: '', description: '', parentId: null as number | null })

function openCreateGroup(parentId?: number | null) {
  editingGroupId.value = 0
  Object.assign(groupForm, { name: '', description: '', parentId: parentId ?? null })
  groupModalVisible.value = true
}
function openEditGroup(g: any) {
  if (g.isSystem === 1) { ElMessage.info('系统分组不可编辑'); return }
  editingGroupId.value = g.id
  Object.assign(groupForm, { name: g.name, description: g.description || '', parentId: g.parentId ?? null })
  groupModalVisible.value = true
}
async function handleGroupSubmit() {
  if (!groupForm.name) { ElMessage.warning('请输入分组名称'); return }
  try {
    if (editingGroupId.value) {
      await updateDefectGroup(projectId.value, editingGroupId.value, groupForm)
      ElMessage.success('更新成功')
    } else {
      await createDefectGroup(projectId.value, groupForm)
      ElMessage.success('创建成功')
    }
    groupModalVisible.value = false
    fetchGroups()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || '操作失败') }
}
function handleDeleteGroup(g: any) {
  if (g.isSystem === 1) { ElMessage.info('系统分组不可删除'); return }
  ElMessageBox.confirm(`确定删除分组「${g.name}」？删除后该分组下的缺陷将自动归入「未分组」。`, '确认删除', { type: 'warning' })
    .then(async () => { await deleteDefectGroup(projectId.value, g.id); ElMessage.success('删除成功'); fetchGroups() })
    .catch(() => {})
}

// ===== 生命周期 =====
const treeRef = ref()
function onDocClick() { closeContextMenu() }
onMounted(() => {
  fetchGroups(); fetchList(); fetchDisplayFields()
  document.addEventListener('click', onDocClick)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
})
</script>

<template>
  <div>
    <PageHeader title="缺陷管理">
      <el-button type="primary" @click="openCreate">+ 新建缺陷</el-button>
    </PageHeader>

    <div class="case-layout">
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
            :data="filteredGroupTree"
            node-key="id"
            :props="{ label: 'name', children: 'children' }"
            :default-expand-all="true"
            :expand-on-click-node="false"
            :filter-node-method="filterNode"
            @node-click="onGroupNodeClick"
          >
            <template #default="{ data }">
              <div
                :class="['group-tree-node', { active: activeGroupId === data.id }]"
                @contextmenu.stop="handleNodeContextmenu($event, data)"
              >
                <span class="group-name">{{ data.name }}</span>
                <span class="group-count">{{ data.defectCount ?? 0 }}</span>
                <span v-if="data.isSystem === 1" class="group-lock" title="系统默认分组">&#x1F512;</span>
              </div>
            </template>
          </el-tree>
        </div>
      </div>

      <!-- 右侧内容 -->
      <div class="case-content">
        <ProSearchCard :loading="loading" @search="handleSearch" @reset="handleReset">
          <div class="pro-search-field">
            <span class="pro-search-label">标题/编号</span>
            <el-input v-model="search.keyword" placeholder="搜索缺陷标题或编号" clearable style="width: 180px" @keyup.enter="handleSearch" />
          </div>
          <div class="pro-search-field">
            <span class="pro-search-label">状态</span>
            <el-select v-model="search.status" placeholder="全部" clearable style="width: 120px">
              <el-option v-for="s in statusOptions" :key="s.value" :value="s.value" :label="s.label" />
            </el-select>
          </div>
          <div class="pro-search-field">
            <span class="pro-search-label">所属分组</span>
            <el-tree-select
              :model-value="activeGroupId === 0 ? undefined : activeGroupId"
              :data="groupFilterTree"
              node-key="id"
              :props="{ label: 'name', value: 'id', children: 'children' }"
              check-strictly
              clearable
              filterable
              placeholder="全部"
              style="width: 160px"
              @update:model-value="handleFilterGroupChange"
            />
          </div>
          <div class="pro-search-field">
            <span class="pro-search-label">创建时间</span>
            <el-date-picker
              v-model="searchCreatedAt"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始"
              end-placeholder="结束"
              style="width: 240px"
            />
          </div>
          <div v-for="field in displayFields" :key="field.fieldKey" class="pro-search-field">
            <span class="pro-search-label">{{ field.fieldLabel }}</span>
            <el-select
              v-if="['select', 'user', 'environment'].includes(field.fieldType)"
              v-model="searchCustom[field.fieldKey]"
              placeholder="全部"
              clearable
              filterable
              style="width: 140px"
            >
              <el-option v-for="o in parseFieldOptions(field)" :key="o.value" :value="o.value" :label="o.label" />
            </el-select>
            <el-date-picker
              v-else-if="field.fieldType === 'datetime'"
              v-model="searchCustom[field.fieldKey]"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始"
              end-placeholder="结束"
              style="width: 240px"
            />
            <el-input-number
              v-else-if="field.fieldType === 'number'"
              v-model="searchCustom[field.fieldKey]"
              :controls="false"
              placeholder="精确匹配"
              style="width: 140px"
            />
            <el-input
              v-else
              v-model="searchCustom[field.fieldKey]"
              placeholder="包含"
              clearable
              style="width: 140px"
              @keyup.enter="handleSearch"
            />
          </div>
        </ProSearchCard>

        <BatchBar
          :selected-count="selectedIds.length"
          :actions="[
            { key: 'move', label: '批量修改分组' },
            { key: 'delete', label: '批量删除', danger: true },
          ]"
          @action="handleBatchAction"
          @clear="clearSelection"
        />

        <el-table :data="list" v-loading="loading" border stripe style="width: 100%" @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="45" />
          <el-table-column prop="defectNo" label="缺陷编号" width="160" show-overflow-tooltip />
          <el-table-column prop="title" label="缺陷标题" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <el-input
                v-if="titleEditingId === row.id"
                ref="titleInputRef"
                v-model="titleEditingValue"
                placeholder="请输入缺陷标题"
                maxlength="500"
                @blur="handleTitleBlur(row)"
                @keyup.enter="handleTitleBlur(row)"
              />
              <span
                v-else
                class="defect-title-editable"
                @click="startTitleEdit(row)"
              >{{ row.title }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-select
                :model-value="row.status"
                size="small"
                @change="(val: string) => handleTransition(row, val)"
              >
                <el-option v-for="s in selectableStatusOptions(row)" :key="s.value" :value="s.value" :label="s.label" />
              </el-select>
            </template>
          </el-table-column>
          <!-- 所属分组（与详情页"字段信息"区一致：未分组时显示"未分组"） -->
          <el-table-column label="所属分组" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.groupName || '未分组' }}
            </template>
          </el-table-column>
          <!-- 动态字段列（【字段管理-缺陷字段】，与详情页"字段信息"同源；仅新建显示的字段不进列表） -->
          <el-table-column
            v-for="field in displayFields"
            :key="field.fieldKey"
            :label="field.fieldLabel"
            :min-width="field.fieldType === 'datetime' ? 150 : 120"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ fieldDisplayText(field, row) }}
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="150">
            <template #default="{ row }">
              {{ row.createdAt?.substring(0, 16)?.replace('T', ' ') }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="handleView(row)">详情</el-button>
              <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div style="padding:48px 20px;text-align:center;color:#909399">
              <div>暂无数据</div>
            </div>
          </template>
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
          <el-input v-model="groupForm.name" placeholder="如：登录模块" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="groupForm.description" type="textarea" :rows="2" placeholder="可选，分组描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupModalVisible = false">取消</el-button>
        <el-button type="primary" @click="handleGroupSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 批量修改分组弹窗 -->
    <el-dialog v-model="batchMoveVisible" title="批量修改分组" width="420px">
      <p style="margin: 0 0 12px; color: #606266; font-size: 13px;">
        将选中的 <b style="color: #409eff">{{ selectedIds.length }}</b> 条缺陷移动到：
      </p>
      <el-select v-model="batchMoveTarget" placeholder="选择目标分组" filterable style="width: 100%">
        <el-option v-for="g in userGroups" :key="g.id" :value="g.id" :label="g.name" />
      </el-select>
      <template #footer>
        <el-button @click="batchMoveVisible = false">取消</el-button>
        <el-button type="primary" @click="handleBatchMove">确认移动</el-button>
      </template>
    </el-dialog>

    <!-- 右键上下文菜单 -->
    <Teleport to="body">
      <div
        v-if="contextMenuVisible"
        class="context-menu"
        :style="{ left: contextMenuPos.x + 'px', top: contextMenuPos.y + 'px' }"
        @click.stop
      >
        <template v-if="!contextGroup">
          <div class="context-menu-item" @click="contextCreateGroup">新建分组</div>
        </template>
        <template v-else-if="contextGroup.isSystem === 1">
          <div class="context-menu-item danger" @click="contextClear">清空缺陷</div>
        </template>
        <template v-else>
          <div class="context-menu-item" @click="contextCreateChild">新建子分组</div>
          <div class="context-menu-divider" />
          <div class="context-menu-item" @click="contextEdit">编辑</div>
          <div class="context-menu-item danger" @click="contextClear">清空缺陷</div>
          <div class="context-menu-item danger" @click="contextDelete">删除</div>
        </template>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.case-layout { display: flex; gap: 16px; min-height: calc(100vh - 164px); }
.group-panel { width: 220px; flex-shrink: 0; background: #fff; border: 1px solid #ebeef5; border-radius: 6px; padding: 12px; display: flex; flex-direction: column; overflow: hidden; }
.group-head { display: flex; justify-content: space-between; align-items: center; }
.group-title { font-weight: 600; font-size: 14px; color: #303133; }
.tree-search { margin: 8px 0; }
.tree-search :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px #dcdfe6 inset; border-radius: 4px; }
.group-tree { flex: 1; overflow-y: auto; }
.group-tree :deep(.el-tree-node__content) { height: auto; padding: 2px 0; }
.group-tree-node { display: flex; align-items: center; flex: 1; padding: 2px 4px; border-radius: 4px; font-size: 13px; gap: 6px; width: 100%; }
.group-tree-node:hover { background: #f5f7fa; }
.group-tree-node.active { background: #ecf5ff; color: #409eff; font-weight: 500; }
.group-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.group-count { font-size: 12px; color: #909399; flex-shrink: 0; }
.group-lock { font-size: 10px; color: #c0c4cc; flex-shrink: 0; margin-left: 2px; }
.case-content { flex: 1; min-width: 0; }
/* 缺陷标题：点击行内编辑（悬浮变色提示可编辑，鼠标保持默认箭头不变手型） */
.defect-title-editable:hover { color: #409eff; }

.context-menu { position: fixed; background: #fff; border: 1px solid #ebeef5; border-radius: 4px; box-shadow: 0 2px 12px rgba(0,0,0,0.1); padding: 4px 0; min-width: 130px; z-index: 9999; }
.context-menu-item { padding: 7px 14px; font-size: 13px; color: #303133; cursor: pointer; display: flex; align-items: center; gap: 8px; transition: background 0.15s; }
.context-menu-item:hover { background: #f5f7fa; }
.context-menu-item.danger { color: #f56c6c; }
.context-menu-item.danger:hover { background: #fef0f0; }
.context-menu-divider { height: 1px; background: #ebeef5; margin: 4px 0; }
</style>
