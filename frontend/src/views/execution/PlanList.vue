<!--
 @author HXN
 @date 2026-08-20 15:34
 @description 测试计划列表视图（含分组树 + 高级搜索 + 执行统计）
-->
<script setup lang="ts">
/**
 * 测试计划列表 - M9
 * 左侧分组树 + 右侧高级搜索 + 分页表格（含触发方式、用例数、最近执行、通过率列）
 * 分组交互对齐 ApiList.vue 模式（el-tree 多层树 + 右键菜单）
 */
import { ref, reactive, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { getPlans, createPlan, updatePlan, deletePlan, getPlanGroups, createPlanGroup, updatePlanGroup, deletePlanGroup, clearGroupPlans, clearProjectPlans } from '@/api/plan'
import { startExecution } from '@/api/execution'
import { getEnvironments } from '@/api/environment'
import { useDict } from '@/composables/useDict'
import { usePermission } from '@/composables/usePermission'
import PageHeader from '@/components/PageHeader/index.vue'
import ProSearchCard from '@/components/ProSearchCard/index.vue'
import ProPagination from '@/components/ProPagination/index.vue'

const route = useRoute()
const router = useRouter()
const { hasPermission } = usePermission()
const projectId = computed(() => Number(route.params.id))
const { options: triggerTypeOptions } = useDict('trigger_type')
const { options: statusOptions } = useDict('is_active')
const { options: planTypeOptions } = useDict('plan_type')

// ===== 分组树 =====
const groups = ref<any[]>([])
const activeGroupId = ref<number>(0) // 0 = 全部，-1 = 未分组，正数 = 分组 ID
const filterText = ref('')
const treeRef = ref()
// 未分组计数 ≈ 当前总数 − 顶层用户分组递归计数和（后端 planCount 已含子孙分组）
const ungroupedCount = computed(() => {
  const topUserGroups = groups.value.filter((g: any) => (g.parentId ?? null) === null)
  const groupedSum = topUserGroups.reduce((acc: number, g: any) => acc + (g.planCount || 0), 0)
  return Math.max(0, pagination.total - groupedSum)
})
// 分组树：全部(虚拟) + 未分组(虚拟) + 用户分组按 parentId 建树
const groupTree = computed(() => {
  const userItems = groups.value
  const buildTree = (parentId: number | null): any[] =>
    userItems
      .filter((g: any) => (g.parentId ?? null) === parentId)
      .map((g: any) => ({ ...g, children: buildTree(g.id) }))
  return [
    { id: 0, name: '全部', isSystem: 1, planCount: pagination.total, children: [] },
    { id: -1, name: '未分组', isSystem: 1, planCount: ungroupedCount.value, children: [] },
    ...buildTree(null),
  ]
})
// 根据搜索关键字过滤分组树
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
// 树形组件过滤回调
function filterNode(value: string, data: any) {
  if (!value) return true
  return data.name.toLowerCase().includes(value.toLowerCase())
}

function onGroupNodeClick(data: any) {
  selectGroup(data.id)
}

function selectGroup(id: number) {
  activeGroupId.value = id === activeGroupId.value ? 0 : id
  pagination.current = 1
  fetchList()
}

async function fetchGroups() {
  try {
    const res: any = await getPlanGroups(projectId.value)
    groups.value = res.data || []
  } catch { groups.value = [] }
}

// ===== 右键菜单 =====
const contextMenuVisible = ref(false)
const contextMenuPos = reactive({ x: 0, y: 0 })
const contextGroup = ref<any>(null)

function handleNodeContextmenu(e: MouseEvent, data: any) {
  e.preventDefault()
  e.stopPropagation()
  // 系统分组（全部/未分组虚拟节点）右键显示清空菜单
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
  if (contextGroup.value) {
    openCreateGroup(contextGroup.value.id)
  } else {
    openCreateGroup()
  }
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
      ? '确定清空项目下的所有计划？其执行记录将一并删除，此操作不可恢复。'
      : isUngrouped
        ? '确定清空「未分组」中的所有计划？其执行记录将一并删除，此操作不可恢复。'
        : `确定清空分组「${g.name}」及其子分组中的所有计划？其执行记录将一并删除，此操作不可恢复。`,
    '确认清空',
    { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' }
  )
    .then(async () => {
      if (isAll) {
        await clearProjectPlans(projectId.value)
      } else {
        // 未分组（-1）后端语义为 groupId=0
        await clearGroupPlans(projectId.value, isUngrouped ? 0 : g.id)
      }
      ElMessage.success('已清空')
      fetchGroups()
      fetchList()
    })
    .catch(() => {})
}

// ===== 分组弹窗 =====
const groupModalVisible = ref(false)
const groupDialogTitle = ref('新建分组')
const groupForm = reactive({ name: '', description: '', parentId: null as number | null })
const editingGroupId = ref<number>(0)

function openCreateGroup(parentId: number | null = null) {
  editingGroupId.value = 0
  groupDialogTitle.value = parentId ? '新建子分组' : '新建分组'
  Object.assign(groupForm, { name: '', description: '', parentId: parentId ?? null })
  groupModalVisible.value = true
}

function openEditGroup(group: any) {
  if (group.isSystem === 1) { ElMessage.info('系统分组不可编辑'); return }
  editingGroupId.value = group.id
  groupDialogTitle.value = '编辑分组'
  Object.assign(groupForm, { name: group.name, description: group.description || '', parentId: group.parentId ?? null })
  groupModalVisible.value = true
}

async function handleGroupSubmit() {
  if (!groupForm.name.trim()) {
    ElMessage.warning('请输入分组名称')
    return
  }
  try {
    if (editingGroupId.value) {
      await updatePlanGroup(editingGroupId.value, {
        name: groupForm.name,
        description: groupForm.description || undefined,
        parentId: groupForm.parentId,
      })
      ElMessage.success('保存成功')
    } else {
      await createPlanGroup(projectId.value, {
        name: groupForm.name,
        description: groupForm.description || undefined,
        parentId: groupForm.parentId,
      })
      ElMessage.success('创建成功')
    }
    groupModalVisible.value = false
    fetchGroups()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

async function handleDeleteGroup(group: any) {
  if (group.isSystem === 1) { ElMessage.info('系统分组不可删除'); return }
  try {
    await ElMessageBox.confirm(
      `确定删除分组「${group.name}」吗？删除后，该分组下的计划将自动归入「未分组」。`,
      '确认删除',
      { type: 'warning' }
    )
    await deletePlanGroup(group.id)
    ElMessage.success('删除成功')
    if (activeGroupId.value === group.id) {
      activeGroupId.value = 0
    }
    fetchGroups()
    fetchList()
  } catch { /* cancelled */ }
}

// ===== 搜索 =====
const searchForm = reactive({
  name: '',
  triggerType: '',
  planType: '',
  environmentId: null as number | null,
  status: '' as string,
  suiteKeyword: '',
  updateBegin: '',
  updateEnd: '',
})
const environments = ref<any[]>([])

async function loadEnvironments() {
  try {
    const res: any = await getEnvironments(projectId.value)
    environments.value = res.data || []
  } catch { environments.value = [] }
}

function handleSearch() {
  pagination.current = 1
  fetchList()
}

function handleReset() {
  Object.assign(searchForm, {
    name: '',
    triggerType: '',
    planType: '',
    environmentId: null,
    status: '',
    suiteKeyword: '',
    updateBegin: '',
    updateEnd: '',
  })
  handleSearch()
}

// ===== 列表 =====
const loading = ref(false)
const list = ref<any[]>([])
const pagination = reactive({ current: 1, pageSize: 20, total: 0 })

async function fetchList() {
  loading.value = true
  try {
    const params: any = {
      page: pagination.current,
      pageSize: pagination.pageSize,
    }
    // 搜索条件拼入 keyword（后端支持 name/description 模糊匹配）
    if (searchForm.name) params.keyword = searchForm.name

    // 分组过滤：activeGroupId 0=全部（不传）；-1=未分组（后端 groupId=0）；正数=分组 ID
    const groupIdParam = activeGroupId.value === 0 ? undefined : activeGroupId.value === -1 ? 0 : activeGroupId.value
    if (groupIdParam !== undefined) params.groupId = groupIdParam

    // 触发方式
    if (searchForm.triggerType) params.triggerType = searchForm.triggerType
    // 计划类型
    if (searchForm.planType) params.planType = searchForm.planType
    // 环境
    if (searchForm.environmentId) params.environmentId = searchForm.environmentId
    // 状态
    if (searchForm.status) params.status = searchForm.status === '1' ? 1 : 0
    // 关联套件名称关键字
    if (searchForm.suiteKeyword) params.suiteKeyword = searchForm.suiteKeyword
    // 更新日期范围
    if (searchForm.updateBegin) params.updateBegin = searchForm.updateBegin
    if (searchForm.updateEnd) params.updateEnd = searchForm.updateEnd

    const res: any = await getPlans(projectId.value, params)
    list.value = res.data?.items || []
    pagination.total = res.data?.total || 0
  } catch { list.value = [] } finally { loading.value = false }
}

// ===== 新建计划弹窗（创建成功后留在列表页，关联内容由「关联用例」独立页维护） =====
const createModalVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  planType: 'AUTO' as string,
  name: '',
  groupId: null as number | null,
  description: '',
})

function openCreateModal(planType: string) {
  Object.assign(createForm, { planType, name: '', groupId: null, description: '' })
  createModalVisible.value = true
}

async function handleCreateSubmit() {
  if (!createForm.name.trim()) {
    ElMessage.warning('请输入计划名称')
    return
  }
  creating.value = true
  try {
    await createPlan(projectId.value, {
      name: createForm.name,
      planType: createForm.planType,
      groupId: createForm.groupId,
      description: createForm.description || undefined,
    })
    ElMessage.success('创建成功')
    createModalVisible.value = false
    // 创建成功后留在列表页刷新；需要关联用例/套件时由操作列「关联用例」进入独立页
    fetchGroups()
    fetchList()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '创建失败')
  } finally {
    creating.value = false
  }
}

// ===== 操作 =====
/** 跳转关联内容独立页（手动计划=手动化用例 / 自动计划=自动化套件） */
function handleRelate(record: any) {
  router.push(`/project/${projectId.value}/plans/${record.id}`)
}

// ===== 编辑计划弹窗（基础信息 + 执行策略；关联内容在独立页维护） =====
const editModalVisible = ref(false)
const editing = ref(false)
const editingPlan = ref<any>(null)
const editForm = reactive({
  name: '',
  description: '',
  planType: 'AUTO' as string,
  groupId: null as number | null,
  environmentId: null as number | null,
  scheduleCron: '',
  triggerType: 'MANUAL',
  isActive: 1,
})

const editIsManual = computed(() => editForm.planType === 'MANUAL')

/** 打开编辑弹窗：直接用列表行数据填充（列表与详情共用同一 PlanResponse，字段完整） */
function openEditModal(record: any) {
  editingPlan.value = record
  Object.assign(editForm, {
    name: record.name || '',
    description: record.description || '',
    planType: record.planType || 'AUTO',
    groupId: record.groupId ?? null,
    environmentId: record.environmentId ?? null,
    scheduleCron: record.scheduleCron || '',
    triggerType: record.triggerType || 'MANUAL',
    isActive: record.isActive ?? 1,
  })
  editModalVisible.value = true
}

async function handleEditSubmit() {
  if (!editForm.name.trim()) { ElMessage.warning('请输入计划名称'); return }
  editing.value = true
  try {
    const data: any = {
      name: editForm.name,
      description: editForm.description,
      groupId: editForm.groupId,
    }
    // groupId 为 null 表示清除分组（归入"未分组"）
    if (editForm.groupId === null) {
      data.clearGroup = true
    }
    if (!editIsManual.value) {
      data.environmentId = editForm.environmentId
      data.scheduleCron = editForm.scheduleCron
      data.triggerType = editForm.triggerType
      data.isActive = editForm.isActive
    }
    await updatePlan(editingPlan.value.id, data)
    ElMessage.success('保存成功')
    editModalVisible.value = false
    // 名称/分组变化影响分组树计数与列表展示
    fetchGroups()
    fetchList()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    editing.value = false
  }
}

// ===== Cron 可读性解析（编辑弹窗定时执行配置） =====
function parseCronReadable(cron: string): string {
  if (!cron || !cron.trim()) return ''
  const parts = cron.trim().split(/\s+/)
  if (parts.length < 5) return '表达式格式不完整'
  try {
    const [min, hour, day, month, week] = parts
    if (week !== '*' && week !== '?') {
      const weekMap: Record<string, string> = { '0': '周日', '1': '周一', '2': '周二', '3': '周三', '4': '周四', '5': '周五', '6': '周六', '7': '周日' }
      return `每${weekMap[week] || week} ${hour}:${min.padStart(2, '0')} 执行`
    }
    if (day === '*' && month === '*') return `每天 ${hour}:${min.padStart(2, '0')} 执行`
    return `Cron: ${cron}`
  } catch {
    return `Cron: ${cron}`
  }
}

const cronReadable = computed(() => parseCronReadable(editForm.scheduleCron))

// ===== 最近 5 次执行时间预览（简单模拟） =====
const nextExecutions = computed(() => {
  if (editForm.triggerType !== 'SCHEDULED' || !editForm.scheduleCron) return []
  const parts = editForm.scheduleCron.trim().split(/\s+/)
  if (parts.length < 5) return []
  try {
    const [min, hour] = parts
    const times: string[] = []
    const now = new Date()
    for (let i = 1; i <= 5; i++) {
      const d = new Date(now)
      d.setDate(d.getDate() + i)
      d.setHours(parseInt(hour, 10), parseInt(min, 10), 0, 0)
      times.push(d.toISOString().substring(0, 16).replace('T', ' '))
    }
    return times
  } catch { return [] }
})

function handleDelete(record: any) {
  ElMessageBox.confirm(`确定删除计划「${record.name}」？`, '确认删除', { type: 'warning' })
    .then(async () => {
      await deletePlan(record.id)
      ElMessage.success('删除成功')
      fetchGroups()
      fetchList()
    })
    .catch(() => {})
}

async function handleRun(record: any) {
  ElMessageBox.confirm(`确定执行计划「${record.name}」？`, '触发执行', { type: 'info' })
    .then(async () => {
      try {
        const res: any = await startExecution(record.id)
        ElMessage.success('执行已触发')
        router.push(`/project/${projectId.value}/executions/${res.data.id}`)
      } catch { ElMessage.error('触发失败') }
    })
    .catch(() => {})
}

// ===== 辅助 =====
const planTypeTagMap: Record<string, string> = {
  AUTO: 'success',
  MANUAL: 'warning',
}

function planTypeLabel(type: string) {
  return planTypeOptions.value.find((t: any) => t.value === type)?.label || type
}

const triggerTypeTagMap: Record<string, string> = {
  MANUAL: '',
  SCHEDULED: 'success',
  CI: 'warning',
}

function triggerTypeLabel(type: string) {
  return triggerTypeOptions.value.find((t) => t.value === type)?.label || type
}

function formatDateTime(dt: string | null) {
  if (!dt) return '-'
  return dt.substring(0, 16).replace('T', ' ')
}

function getPassRateColor(rate: number | null) {
  if (rate == null) return ''
  if (rate >= 90) return '#67c23a'
  if (rate >= 70) return '#e6a23c'
  return '#f56c6c'
}

// ===== 生命周期 =====
function onDocClick() { closeContextMenu() }
onMounted(() => {
  loadEnvironments()
  fetchGroups()
  fetchList()
  document.addEventListener('click', onDocClick)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
})
</script>

<template>
  <div>
    <PageHeader title="测试计划">
      <el-dropdown v-if="hasPermission('project:plan:add')" @command="openCreateModal">
        <el-button type="primary">+ 新建计划<el-icon style="margin-left:4px"><ArrowDown /></el-icon></el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="AUTO">自动</el-dropdown-item>
            <el-dropdown-item command="MANUAL">手动</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </PageHeader>

    <div class="plan-layout">
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
                <span class="group-count">{{ data.planCount ?? 0 }}</span>
                <span v-if="data.isSystem === 1" class="group-lock" title="系统默认分组">🔒</span>
              </div>
            </template>
          </el-tree>
        </div>
      </div>

      <!-- 右侧内容 -->
      <div class="plan-content">
        <ProSearchCard :loading="loading" @search="handleSearch" @reset="handleReset">
          <div class="pro-search-field">
            <span class="pro-search-label">计划名称</span>
            <el-input v-model="searchForm.name" placeholder="模糊查询" clearable style="width: 180px" @keyup.enter="handleSearch" />
          </div>
          <div class="pro-search-field">
            <span class="pro-search-label">触发方式</span>
            <el-select v-model="searchForm.triggerType" placeholder="全部触发方式" clearable style="width: 140px">
              <el-option v-for="t in triggerTypeOptions" :key="t.value" :value="t.value" :label="t.label" />
            </el-select>
          </div>
          <div class="pro-search-field">
            <span class="pro-search-label">计划类型</span>
            <el-select v-model="searchForm.planType" placeholder="全部类型" clearable style="width: 140px">
              <el-option v-for="t in planTypeOptions" :key="t.value" :value="t.value" :label="t.label" />
            </el-select>
          </div>
          <div class="pro-search-field">
            <span class="pro-search-label">环境</span>
            <el-select v-model="searchForm.environmentId" placeholder="全部环境" clearable style="width: 150px">
              <el-option v-for="env in environments" :key="env.id" :value="env.id" :label="env.name" />
            </el-select>
          </div>
          <template #collapse>
            <div class="pro-search-field">
              <span class="pro-search-label">状态</span>
              <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 120px">
                <el-option v-for="s in statusOptions" :key="s.value" :value="s.value" :label="s.label" />
              </el-select>
            </div>
            <div class="pro-search-field">
              <span class="pro-search-label">关联套件</span>
              <el-input v-model="searchForm.suiteKeyword" placeholder="套件名称" clearable style="width: 160px" @keyup.enter="handleSearch" />
            </div>
            <div class="pro-search-field">
              <span class="pro-search-label">最近更新</span>
              <el-date-picker v-model="searchForm.updateBegin" type="date" placeholder="开始日期" style="width: 130px" value-format="YYYY-MM-DD" />
              <span style="color: #909399; padding: 0 2px">至</span>
              <el-date-picker v-model="searchForm.updateEnd" type="date" placeholder="结束日期" style="width: 130px" value-format="YYYY-MM-DD" />
            </div>
          </template>
        </ProSearchCard>

        <!-- 表格 -->
        <el-table v-loading="loading" :data="list" row-key="id" border stripe style="width:100%">
          <el-table-column prop="name" label="计划名称" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <a style="font-weight:500;color:var(--color-primary);cursor:pointer" @click="handleRelate(row)">{{ row.name }}</a>
            </template>
          </el-table-column>
          <el-table-column label="计划类型" width="120" align="center">
            <template #default="{ row }">
              <el-tag :type="(planTypeTagMap[row.planType] || '') as any" size="small">
                {{ planTypeLabel(row.planType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
          <el-table-column label="触发方式" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="(triggerTypeTagMap[row.triggerType] || '') as any" size="small">
                {{ triggerTypeLabel(row.triggerType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="关联内容" min-width="180">
            <template #default="{ row }">
              <div style="display:flex;flex-direction:column;gap:3px;font-size:12px">
                <span v-if="row.planType === 'AUTO'">自动化套件：{{ row.autoSuiteIds?.length || 0 }}</span>
                <span v-if="row.planType === 'MANUAL'">手动化用例：{{ row.manualCaseCount ?? row.manualCaseIds?.length ?? 0 }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="用例数" width="100" align="center">
            <template #default="{ row }">
              <span v-if="row.planType === 'AUTO'">{{ row.caseCount || 0 }}</span>
              <span v-else>{{ row.manualCaseCount ?? row.manualCaseIds?.length ?? 0 }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="environmentName" label="环境" width="100" show-overflow-tooltip />
          <el-table-column label="状态" width="70" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isActive === 1 ? 'success' : 'info'" size="small">
                {{ row.isActive === 1 ? '启用' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近执行" width="150">
            <template #default="{ row }">
              <div v-if="row.lastExecutionTime" style="font-size:12px;color:#606266">
                {{ formatDateTime(row.lastExecutionTime) }}
              </div>
              <div v-if="row.scheduleCron" style="font-size:11px;color:#909399;font-family:monospace;margin-top:2px">
                {{ row.scheduleCron }}
              </div>
              <span v-if="!row.lastExecutionTime" style="color:#c0c4cc">-</span>
            </template>
          </el-table-column>
          <el-table-column label="通过率" width="80" align="center">
            <template #default="{ row }">
              <b v-if="row.passRate != null" :style="{ color: getPassRateColor(row.passRate) }">
                {{ row.passRate }}%
              </b>
              <span v-else style="color:#c0c4cc">-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right" align="center">
            <template #default="{ row }">
              <el-button v-if="hasPermission('project:plan:edit')" type="primary" link size="small" @click="openEditModal(row)">编辑</el-button>
              <el-button v-if="hasPermission('project:plan:edit')" type="primary" link size="small" @click="handleRelate(row)">{{ row.planType === 'MANUAL' ? '关联用例' : '关联套件' }}</el-button>
              <el-button v-if="hasPermission('project:plan:run')" type="success" link size="small" @click="handleRun(row)">执行</el-button>
              <el-button v-if="hasPermission('project:plan:delete')" type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
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

    <!-- 新建计划弹窗 -->
    <el-dialog v-model="createModalVisible" title="新建计划" width="480px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="计划类型" required>
          <div class="plan-type-group">
            <div v-for="t in planTypeOptions" :key="t.value"
              class="plan-type-card"
              :class="{ selected: createForm.planType === t.value }"
              @click="createForm.planType = t.value">
              <el-radio :model-value="createForm.planType" :label="t.value" @click.stop>{{ t.label }}</el-radio>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="计划名称" required>
          <el-input v-model="createForm.name" placeholder="请输入计划名称" maxlength="100" />
        </el-form-item>
        <el-form-item label="所属分组">
          <el-select v-model="createForm.groupId" placeholder="未分组" clearable style="width:100%">
            <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="2" placeholder="可选，描述此计划的用途" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createModalVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreateSubmit">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑计划弹窗：基础信息 + 执行策略（关联内容在独立页维护） -->
    <el-dialog v-model="editModalVisible" :title="`编辑${editIsManual ? '手动' : '自动'}计划`" width="640px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="计划名称" required>
              <el-input v-model="editForm.name" placeholder="请输入计划名称" maxlength="100" />
            </el-form-item>
          </el-col>
          <el-col v-if="!editIsManual" :span="12">
            <el-form-item label="绑定环境">
              <el-select v-model="editForm.environmentId" placeholder="选择执行环境" clearable style="width:100%">
                <el-option v-for="env in environments" :key="env.id" :value="env.id"
                  :label="`${env.name}${env.isCurrent === 1 ? '（当前）' : ''}`" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="所属分组">
              <el-select v-model="editForm.groupId" placeholder="未分组" clearable style="width:100%">
                <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="2" placeholder="可选，描述此计划的用途" maxlength="500" />
        </el-form-item>
      </el-form>

      <!-- 执行策略（仅自动测试计划） -->
      <template v-if="!editIsManual">
        <el-divider content-position="left">执行策略</el-divider>
        <div class="trigger-group">
          <div class="trigger-card" :class="{ selected: editForm.triggerType === 'MANUAL' }"
            @click="editForm.triggerType = 'MANUAL'">
            <el-radio :model-value="editForm.triggerType" label="MANUAL" @click.stop>手动触发</el-radio>
          </div>
          <div class="trigger-card" :class="{ selected: editForm.triggerType === 'SCHEDULED' }"
            @click="editForm.triggerType = 'SCHEDULED'">
            <el-radio :model-value="editForm.triggerType" label="SCHEDULED" @click.stop>定时执行</el-radio>
          </div>
          <div class="trigger-card" :class="{ selected: editForm.triggerType === 'CI' }"
            @click="editForm.triggerType = 'CI'">
            <el-radio :model-value="editForm.triggerType" label="CI" @click.stop>CI 触发</el-radio>
          </div>
        </div>

        <div v-if="editForm.triggerType === 'SCHEDULED'" class="cron-config-area">
          <el-form label-position="top">
            <el-form-item label="Cron 表达式">
              <div style="display:flex;gap:8px;align-items:center">
                <el-input v-model="editForm.scheduleCron" placeholder="如 0 8 * * *" style="width:200px;font-family:monospace" />
                <span v-if="cronReadable" style="font-size:12px;color:#909399">{{ cronReadable }}</span>
              </div>
            </el-form-item>
            <el-form-item label="启停">
              <el-switch v-model="editForm.isActive" :active-value="1" :inactive-value="0" />
            </el-form-item>
          </el-form>
          <div v-if="nextExecutions.length > 0" class="cron-preview">
            <b>最近 5 次执行时间预览：</b><br>
            {{ nextExecutions.join(' · ') }}
          </div>
        </div>

        <div v-if="editForm.triggerType === 'CI'" class="ci-config-area">
          <div style="font-size:13px;color:#909399;line-height:1.8">
            CI 触发模式下，计划将通过外部 CI/CD 系统（如 Jenkins、GitLab CI）的 API 调用来触发执行。<br>
            请在 CI 系统中配置 Webhook 或 API 调用以触发此计划。
          </div>
        </div>
      </template>

      <template #footer>
        <el-button @click="editModalVisible = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="handleEditSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 分组新建/编辑弹窗 -->
    <el-dialog v-model="groupModalVisible" :title="groupDialogTitle" width="420px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="分组名称" required>
          <el-input v-model="groupForm.name" placeholder="请输入分组名称，如「日常测试」" maxlength="100" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="groupForm.description" placeholder="可选，分组描述" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupModalVisible = false">取消</el-button>
        <el-button type="primary" @click="handleGroupSubmit">确认</el-button>
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
        <!-- 空白区域右键：仅显示"新建分组" -->
        <template v-if="!contextGroup">
          <div v-if="hasPermission('project:plan:group')" class="context-menu-item" @click="contextCreateGroup">新建分组</div>
        </template>
        <!-- 系统分组（全部/未分组）右键：仅允许清空 -->
        <template v-else-if="contextGroup.isSystem === 1">
          <div class="context-menu-item danger" @click="contextClear">清空计划</div>
        </template>
        <!-- 用户分组右键 -->
        <template v-else>
          <div v-if="hasPermission('project:plan:group')" class="context-menu-item" @click="contextCreateChild">新建子分组</div>
          <div v-if="hasPermission('project:plan:group')" class="context-menu-divider" />
          <div class="context-menu-item" @click="contextEdit">编辑</div>
          <div class="context-menu-item danger" @click="contextClear">清空计划</div>
          <div class="context-menu-item danger" @click="contextDelete">删除</div>
        </template>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.plan-layout {
  display: flex;
  gap: 16px;
  min-height: calc(100vh - 164px);
}

/* ===== 分组面板 ===== */
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

/* ===== 右侧内容 ===== */
.plan-content {
  flex: 1;
  min-width: 0;
}

/* ===== 新建弹窗：计划类型卡片 ===== */
.plan-type-group {
  display: flex;
  gap: 12px;
  width: 100%;
}
.plan-type-card {
  flex: 1;
  padding: 8px 16px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  cursor: pointer;
  transition: all .15s;
  text-align: center;
}
.plan-type-card:hover {
  border-color: #409eff;
}
.plan-type-card.selected {
  border-color: #409eff;
  background: #ecf5ff;
}

/* ===== 编辑弹窗：触发方式卡片 ===== */
.trigger-group {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.trigger-card {
  padding: 8px 16px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  cursor: pointer;
  transition: all .15s;
}
.trigger-card:hover {
  border-color: #409eff;
}
.trigger-card.selected {
  border-color: #409eff;
  background: #ecf5ff;
}

/* ===== 编辑弹窗：Cron 配置区 ===== */
.cron-config-area {
  padding: 16px;
  background: #f5f7fa;
  border-radius: 4px;
}
.cron-preview {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  line-height: 1.8;
}
.ci-config-area {
  padding: 16px;
  background: #f5f7fa;
  border-radius: 4px;
}

/* ===== 右键上下文菜单 ===== */
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
</style>
