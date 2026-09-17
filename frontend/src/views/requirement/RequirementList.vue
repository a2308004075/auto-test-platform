<!--
 @author HXN
 @date 2026-09-15
 @description 需求文档视图（左右分栏：分组/版本树 + 需求条目列表）
-->
<script setup lang="ts">
/**
 * 需求文档 - 分组与版本管理、需求条目管理
 * 左侧分组树（分组可嵌套子分组，分组下挂版本），右侧选中版本的需求条目列表（可增删改）
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  getRequirementGroups,
  createRequirementGroup,
  updateRequirementGroup,
  deleteRequirementGroup,
  getRequirementVersions,
  createRequirementVersion,
  updateRequirementVersion,
  deleteRequirementVersion,
  getRequirementItems,
  deleteRequirementItem,
} from '@/api/requirement'
import type { RequirementGroup, RequirementVersion, RequirementItem } from '@/api/requirement'
import { useProjectStore } from '@/stores/modules/project'
import { usePermission } from '@/composables/usePermission'
import BizDetailDrawer from '@/components/BizDetailDrawer/index.vue'

const route = useRoute()
const router = useRouter()
const { hasPermission } = usePermission()
const projectStore = useProjectStore()
const projectId = computed(() => Number(route.params.id))

// ===== 字典映射 =====
const versionStatusMap: Record<string, { label: string; type: string }> = {
  PLANNING: { label: '规划中', type: 'info' },
  IN_PROGRESS: { label: '进行中', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
}

const reqTypeMap: Record<string, { label: string; type: string }> = {
  FEATURE: { label: '功能', type: '' },
  IMPROVEMENT: { label: '优化', type: 'success' },
  BUG: { label: 'Bug', type: 'danger' },
}

const priorityMap: Record<string, { label: string; type: string }> = {
  HIGH: { label: '高', type: 'danger' },
  MEDIUM: { label: '中', type: 'warning' },
  LOW: { label: '低', type: 'info' },
}

const itemStatusMap: Record<string, { label: string; type: string }> = {
  PENDING: { label: '待处理', type: 'info' },
  IN_PROGRESS: { label: '进行中', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
}

// ===== 分组与版本 =====
const groupLoading = ref(false)
const groups = ref<RequirementGroup[]>([])
const versions = ref<RequirementVersion[]>([])
const selectedVersionId = ref<number | null>(null)

const selectedVersion = computed(() =>
  versions.value.find((v) => v.id === selectedVersionId.value) || null,
)

// 左侧树：全部(虚拟) + 系统分组「未分组」+ 用户分组（含嵌套子分组）→ 版本节点
// 同一版本会同时出现在「全部」与所属分组下，node-key 统一用 key 字段避免 id 重复
const groupTree = computed(() => {
  const mkVersion = (v: RequirementVersion) => ({
    ...v,
    nodeType: 'version',
    key: `version-${v.id}`,
    children: [],
  })
  // 「全部」虚拟节点（id=0）：统计所有版本的需求条目总数，展开后平铺全部版本
  const allNode = {
    id: 0,
    name: '全部',
    isSystem: 1,
    nodeType: 'group',
    key: 'group-0',
    itemCount: versions.value.reduce((sum, v) => sum + (v.itemCount || 0), 0),
    children: versions.value.map(mkVersion),
  }
  const userGroups = groups.value.filter((g) => g.isSystem !== 1)
  const buildTree = (parentId: number | null): any[] =>
    userGroups
      .filter((g) => (g.parentId ?? null) === parentId)
      .map((g) => ({
        ...g,
        nodeType: 'group',
        key: `group-${g.id}`,
        children: [
          ...buildTree(g.id),
          ...versions.value
            .filter((v) => v.groupId === g.id)
            .map(mkVersion),
        ],
      }))
  const systemGroups = groups.value
    .filter((g) => g.isSystem === 1)
    .map((g) => ({
      ...g,
      nodeType: 'group',
      key: `group-${g.id}`,
      children: versions.value
        .filter((v) => v.groupId === g.id)
        .map(mkVersion),
    }))
  return [allNode, ...systemGroups, ...buildTree(null)]
})

async function fetchGroupsAndVersions() {
  groupLoading.value = true
  try {
    const [groupRes, versionRes]: any[] = await Promise.all([
      getRequirementGroups(projectId.value),
      getRequirementVersions(projectId.value),
    ])
    groups.value = groupRes.data || []
    versions.value = versionRes.data || []
    // 如果之前选中的版本已不存在，清除选中
    if (selectedVersionId.value && !versions.value.find((v) => v.id === selectedVersionId.value)) {
      selectedVersionId.value = null
    }
  } catch {
    groups.value = []
    versions.value = []
  } finally {
    groupLoading.value = false
  }
}

// ===== 分组新建/编辑弹窗 =====
const groupModalVisible = ref(false)
const groupEditingId = ref<number>(0)
const groupFormRef = ref<FormInstance>()
const groupForm = reactive({
  name: '',
  description: '',
  parentId: null as number | null,
})
const groupRules = reactive<FormRules>({
  name: [
    { required: true, message: '请输入分组名称', trigger: 'blur' },
    { max: 100, message: '分组名称长度不能超过 100 个字符', trigger: 'blur' },
  ],
})

// 版本弹窗的分组下拉选项：未分组系统分组 + 用户分组树
const groupSelectOptions = computed(() => {
  const userGroups = groups.value.filter((g) => g.isSystem !== 1)
  const buildTree = (parentId: number | null): any[] =>
    userGroups
      .filter((g) => (g.parentId ?? null) === parentId)
      .map((g) => ({ id: g.id, name: g.name, children: buildTree(g.id) }))
  const ungrouped = groups.value.find((g) => g.isSystem === 1)
  return ungrouped
    ? [{ id: ungrouped.id, name: ungrouped.name, children: [] }, ...buildTree(null)]
    : buildTree(null)
})

function openCreateGroup(parentId?: number | null) {
  groupEditingId.value = 0
  Object.assign(groupForm, { name: '', description: '', parentId: parentId ?? null })
  groupModalVisible.value = true
}

function openEditGroup(group: RequirementGroup) {
  if (group.isSystem === 1) {
    ElMessage.info('系统分组不可编辑')
    return
  }
  groupEditingId.value = group.id
  Object.assign(groupForm, {
    name: group.name,
    description: group.description || '',
    parentId: group.parentId ?? null,
  })
  groupModalVisible.value = true
}

function handleGroupSubmit() {
  groupFormRef.value?.validate(async (valid) => {
    if (!valid) return
    try {
      if (groupEditingId.value) {
        await updateRequirementGroup(projectId.value, groupEditingId.value, {
          name: groupForm.name,
          description: groupForm.description || undefined,
          parentId: groupForm.parentId,
        })
        ElMessage.success('保存成功')
      } else {
        await createRequirementGroup(projectId.value, {
          name: groupForm.name,
          description: groupForm.description || undefined,
          parentId: groupForm.parentId,
        })
        ElMessage.success('创建成功')
      }
      groupModalVisible.value = false
      await fetchGroupsAndVersions()
    } catch (e: any) {
      ElMessage.error(e?.response?.data?.message || '保存失败')
    }
  })
}

function handleGroupDialogClosed() {
  groupFormRef.value?.resetFields()
}

function handleDeleteGroup(group: RequirementGroup) {
  if (group.isSystem === 1) {
    ElMessage.info('系统分组不可删除')
    return
  }
  ElMessageBox.confirm(
    `确定删除分组「${group.name}」？分组下存在子分组或版本时将无法删除。`,
    '确认删除',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
  )
    .then(async () => {
      await deleteRequirementGroup(projectId.value, group.id)
      ElMessage.success('删除成功')
      await fetchGroupsAndVersions()
    })
    .catch(() => {})
}

// ===== 版本新建/编辑弹窗 =====
const versionModalVisible = ref(false)
const versionIsEdit = ref(false)
const versionEditingId = ref<number | null>(null)
const versionFormRef = ref<FormInstance>()
const versionForm = reactive({
  versionName: '',
  description: '',
  status: 'PLANNING',
  startDate: '',
  endDate: '',
  groupId: null as number | null,
})
const versionRules = reactive<FormRules>({
  versionName: [
    { required: true, message: '请输入版本号', trigger: 'blur' },
    { max: 100, message: '版本号长度不能超过 100 个字符', trigger: 'blur' },
  ],
})

// 新建版本的默认分组：系统「未分组」
const ungroupedId = computed(() => groups.value.find((g) => g.isSystem === 1)?.id ?? null)

function openCreateVersion(groupId?: number | null) {
  versionIsEdit.value = false
  versionEditingId.value = null
  Object.assign(versionForm, {
    versionName: '',
    description: '',
    status: 'PLANNING',
    startDate: '',
    endDate: '',
    groupId: groupId ?? ungroupedId.value,
  })
  versionModalVisible.value = true
}

function openEditVersion(version: RequirementVersion) {
  versionIsEdit.value = true
  versionEditingId.value = version.id
  Object.assign(versionForm, {
    versionName: version.versionName,
    description: version.description || '',
    status: version.status,
    startDate: version.startDate || '',
    endDate: version.endDate || '',
    groupId: version.groupId,
  })
  versionModalVisible.value = true
}

function handleVersionSubmit() {
  versionFormRef.value?.validate(async (valid) => {
    if (!valid) return
    try {
      const data = {
        versionName: versionForm.versionName,
        description: versionForm.description || undefined,
        status: versionForm.status,
        startDate: versionForm.startDate || undefined,
        endDate: versionForm.endDate || undefined,
        groupId: versionForm.groupId,
      }
      if (versionIsEdit.value && versionEditingId.value) {
        await updateRequirementVersion(versionEditingId.value, data)
        ElMessage.success('保存成功')
      } else {
        await createRequirementVersion(projectId.value, data)
        ElMessage.success('创建成功')
      }
      versionModalVisible.value = false
      await fetchGroupsAndVersions()
    } catch (e: any) {
      ElMessage.error(e?.response?.data?.message || '保存失败')
    }
  })
}

function handleVersionDialogClosed() {
  versionFormRef.value?.resetFields()
}

function handleDeleteVersion(version: RequirementVersion) {
  const itemCount = version.itemCount || 0
  const msg = itemCount > 0
    ? `确定删除版本「${version.versionName}」？该版本下有 ${itemCount} 个需求条目，将一并删除且不可恢复。`
    : `确定删除版本「${version.versionName}」？此操作不可恢复。`
  ElMessageBox.confirm(msg, '确认删除', {
    type: 'warning',
    confirmButtonText: '确认删除',
    cancelButtonText: '取消',
  })
    .then(async () => {
      await deleteRequirementVersion(version.id)
      ElMessage.success('删除成功')
      if (selectedVersionId.value === version.id) {
        selectedVersionId.value = null
      }
      await fetchGroupsAndVersions()
      items.value = []
    })
    .catch(() => {})
}

// ===== 树节点交互 =====
function onNodeClick(data: any) {
  // 仅版本节点可选中加载条目；分组节点仅展开/收起
  if (data.nodeType === 'version') {
    selectedVersionId.value = data.id
  }
}

// ===== 右键菜单 =====
const contextMenuVisible = ref(false)
const contextMenuPos = reactive({ x: 0, y: 0 })
/** 右键目标：null=空白；group 分组节点；version 版本节点 */
const contextTarget = ref<{ type: 'group' | 'version'; data: any } | null>(null)

function handleNodeContextmenu(e: MouseEvent, data: any) {
  e.preventDefault()
  e.stopPropagation()
  contextTarget.value = { type: data.nodeType === 'version' ? 'version' : 'group', data }
  contextMenuPos.x = e.clientX
  contextMenuPos.y = e.clientY
  contextMenuVisible.value = true
}

function handleBlankContextmenu(e: MouseEvent) {
  e.preventDefault()
  contextTarget.value = null
  contextMenuPos.x = e.clientX
  contextMenuPos.y = e.clientY
  contextMenuVisible.value = true
}

function closeContextMenu() {
  contextMenuVisible.value = false
  contextTarget.value = null
}

function contextCreateGroup() {
  openCreateGroup()
  closeContextMenu()
}

function contextCreateChildGroup() {
  if (contextTarget.value?.type === 'group') {
    openCreateGroup(contextTarget.value.data.id)
  }
  closeContextMenu()
}

function contextEditGroup() {
  if (contextTarget.value?.type === 'group') {
    openEditGroup(contextTarget.value.data)
  }
  closeContextMenu()
}

function contextDeleteGroup() {
  if (contextTarget.value?.type === 'group') {
    handleDeleteGroup(contextTarget.value.data)
  }
  closeContextMenu()
}

function contextCreateVersion() {
  if (contextTarget.value?.type === 'group') {
    // 「全部」为虚拟节点（id=0），新建版本默认归入未分组
    openCreateVersion(contextTarget.value.data.id || undefined)
  }
  closeContextMenu()
}

function contextCreateItem() {
  if (contextTarget.value?.type === 'version') {
    selectedVersionId.value = contextTarget.value.data.id
    router.push(`/project/${projectId.value}/requirements/new?versionId=${contextTarget.value.data.id}`)
  }
  closeContextMenu()
}

function contextEditVersion() {
  if (contextTarget.value?.type === 'version') {
    openEditVersion(contextTarget.value.data)
  }
  closeContextMenu()
}

function contextDeleteVersion() {
  if (contextTarget.value?.type === 'version') {
    handleDeleteVersion(contextTarget.value.data)
  }
  closeContextMenu()
}

// ===== 需求条目列表 =====
const itemsLoading = ref(false)
const items = ref<RequirementItem[]>([])

async function fetchItems() {
  if (!selectedVersionId.value) {
    items.value = []
    return
  }
  itemsLoading.value = true
  try {
    const res: any = await getRequirementItems(selectedVersionId.value)
    items.value = res.data || []
  } catch {
    items.value = []
  } finally {
    itemsLoading.value = false
  }
}

watch(selectedVersionId, () => {
  fetchItems()
})

// ===== 需求条目新建/编辑导航 =====

function openCreateItem() {
  if (!selectedVersionId.value) return
  router.push(`/project/${projectId.value}/requirements/new?versionId=${selectedVersionId.value}`)
}

function openEditItem(item: RequirementItem) {
  router.push(`/project/${projectId.value}/requirements/${item.id}/edit`)
}

function handleDeleteItem(item: RequirementItem) {
  ElMessageBox.confirm(
    `确定删除需求「${item.title}」？此操作不可恢复。`,
    '确认删除',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
  )
    .then(async () => {
      await deleteRequirementItem(item.id)
      ElMessage.success('删除成功')
      await fetchItems()
      fetchGroupsAndVersions()
    })
    .catch(() => {})
}

// ===== 格式化 =====
function formatDate(value: string | null | undefined) {
  return value ? value.substring(0, 10) : '-'
}

// ===== 详情抽屉 =====
const detailDrawerVisible = ref(false)
const detailItem = ref<RequirementItem | null>(null)

const requirementFieldLabelMap: Record<string, string> = {
  title: '标题',
  description: '描述',
  reqType: '需求类型',
  priority: '优先级',
  status: '状态',
  assignee: '负责人',
  deadline: '截止日期',
}

const requirementValueLabelMap: Record<string, Record<string, string>> = {
  reqType: { FEATURE: '功能', IMPROVEMENT: '优化', BUG: 'Bug' },
  priority: { HIGH: '高', MEDIUM: '中', LOW: '低' },
  status: { PENDING: '待处理', IN_PROGRESS: '进行中', COMPLETED: '已完成' },
}

function openDetailDrawer(item: RequirementItem) {
  detailItem.value = item
  detailDrawerVisible.value = true
}

function onDocClick() { closeContextMenu() }
onMounted(() => {
  fetchGroupsAndVersions()
  document.addEventListener('click', onDocClick)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
})
</script>

<template>
  <div>
    <!-- 页面头部（分组/版本创建入口统一在左侧树右键菜单） -->
    <div class="page-header">
      <h2>需求文档</h2>
    </div>

    <!-- 项目上下文栏 -->
    <div class="req-project-bar">
      <span>&#x1F4CC;</span>
      <span>当前项目：<span class="project-name">{{ projectStore.currentProjectName }}</span></span>
      <span class="bar-sep">|</span>
      <span>管理需求分组、版本与需求条目，跟踪需求进度</span>
    </div>

    <!-- 左右分栏主体 -->
    <div class="req-main">
      <!-- 左侧：分组/版本树 -->
      <div class="req-left-panel">
        <div class="panel-header">
          <span class="panel-title">分组</span>
        </div>

        <div v-loading="groupLoading" class="group-tree" @contextmenu="handleBlankContextmenu">
          <el-tree
            :data="groupTree"
            node-key="key"
            :props="{ label: 'name', children: 'children' }"
            :default-expand-all="true"
            :expand-on-click-node="false"
            @node-click="onNodeClick"
          >
            <template #default="{ data }">
              <div
                :class="[
                  data.nodeType === 'version' ? 'version-node' : 'group-node',
                  { active: data.nodeType === 'version' && selectedVersionId === data.id },
                ]"
                @contextmenu.stop="handleNodeContextmenu($event, data)"
              >
                <span class="group-name">{{ data.nodeType === 'version' ? data.versionName : data.name }}</span>
                <el-tag
                  v-if="data.nodeType === 'version'"
                  :type="versionStatusMap[data.status]?.type || 'info'"
                  size="small"
                >
                  {{ versionStatusMap[data.status]?.label || data.status }}
                </el-tag>
                <span v-else class="group-count">{{ data.itemCount ?? 0 }}</span>
                <span v-if="data.nodeType === 'group' && data.isSystem === 1" class="group-lock" title="系统默认分组">🔒</span>
                <span v-if="data.nodeType === 'version'" class="group-count">{{ data.itemCount || 0 }}</span>
              </div>
            </template>
          </el-tree>

          <div v-if="!groupLoading && groups.length === 0" class="empty-text">
            暂无分组，右键空白处新建分组
          </div>
        </div>
      </div>

      <!-- 右侧：需求条目列表 -->
      <div class="req-right-panel">
        <template v-if="selectedVersion">
          <div v-if="hasPermission('project:req:item:create')" class="panel-header">
            <el-button class="panel-header-action" type="primary" size="small" @click="openCreateItem">
              + 新建需求
            </el-button>
          </div>

          <el-table v-loading="itemsLoading" :data="items" row-key="id" style="width: 100%">
            <el-table-column prop="title" label="标题" min-width="180">
              <template #default="{ row }">
                <strong>{{ row.title }}</strong>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="reqTypeMap[row.reqType]?.type || ''" size="small">
                  {{ reqTypeMap[row.reqType]?.label || row.reqType }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="优先级" width="70" align="center">
              <template #default="{ row }">
                <el-tag :type="priorityMap[row.priority]?.type || 'info'" size="small" effect="plain">
                  {{ priorityMap[row.priority]?.label || row.priority }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="itemStatusMap[row.status]?.type || 'info'" size="small">
                  {{ itemStatusMap[row.status]?.label || row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="负责人" width="90">
              <template #default="{ row }">
                {{ row.assignee || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="截止日期" width="110">
              <template #default="{ row }">
                {{ formatDate(row.deadline) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button
                  type="primary"
                  link
                  size="small"
                  @click="openDetailDrawer(row)"
                >
                  详情
                </el-button>
                <el-button
                  v-if="hasPermission('project:req:item:edit')"
                  type="primary"
                  link
                  size="small"
                  @click="openEditItem(row)"
                >
                  编辑
                </el-button>
                <el-button
                  v-if="hasPermission('project:req:item:delete')"
                  type="danger"
                  link
                  size="small"
                  @click="handleDeleteItem(row)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-text">暂无数据</div>
            </template>
          </el-table>
        </template>

        <template v-else>
          <div class="empty-state">
            <p>请从左侧选择一个版本，查看和管理需求条目</p>
          </div>
        </template>
      </div>
    </div>

    <!-- 分组新建/编辑弹窗 -->
    <el-dialog
      v-model="groupModalVisible"
      :title="groupEditingId ? '编辑分组' : '新建分组'"
      width="460px"
      @closed="handleGroupDialogClosed"
    >
      <el-form ref="groupFormRef" :model="groupForm" :rules="groupRules" label-position="top">
        <el-form-item label="分组名称" prop="name">
          <el-input v-model="groupForm.name" placeholder="如：一期需求" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="groupForm.description" type="textarea" :rows="2" placeholder="分组描述" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupModalVisible = false">取消</el-button>
        <el-button type="primary" @click="handleGroupSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 新建/编辑版本弹窗 -->
    <el-dialog
      v-model="versionModalVisible"
      :title="versionIsEdit ? '编辑版本' : '新建版本'"
      width="520px"
      @closed="handleVersionDialogClosed"
    >
      <el-form ref="versionFormRef" :model="versionForm" :rules="versionRules" label-position="top">
        <el-form-item label="所属分组" prop="groupId">
          <el-tree-select
            v-model="versionForm.groupId"
            :data="groupSelectOptions"
            node-key="id"
            check-strictly
            :props="{ label: 'name', children: 'children' }"
            placeholder="选择分组"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="版本号" prop="versionName">
          <el-input v-model="versionForm.versionName" placeholder="如 V1.0、Release 2.0" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="versionForm.description" type="textarea" :rows="2" placeholder="版本描述" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="versionForm.status" placeholder="选择状态" style="width: 100%">
            <el-option label="规划中" value="PLANNING" />
            <el-option label="进行中" value="IN_PROGRESS" />
            <el-option label="已完成" value="COMPLETED" />
          </el-select>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="计划开始日期" prop="startDate">
              <el-date-picker
                v-model="versionForm.startDate"
                type="date"
                placeholder="开始日期"
                format="YYYY-MM-DD"
                value-format="YYYY-MM-DD"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="计划结束日期" prop="endDate">
              <el-date-picker
                v-model="versionForm.endDate"
                type="date"
                placeholder="结束日期"
                format="YYYY-MM-DD"
                value-format="YYYY-MM-DD"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="versionModalVisible = false">取消</el-button>
        <el-button type="primary" @click="handleVersionSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 需求条目详情抽屉 -->
    <BizDetailDrawer
      v-model:visible="detailDrawerVisible"
      :title="`需求详情 - ${detailItem?.title || ''}`"
      biz-type="REQUIREMENT_ITEM"
      :biz-id="detailItem?.id"
      status-field-name="status"
      :field-label-map="requirementFieldLabelMap"
      :value-label-map="requirementValueLabelMap"
      :project-id="projectId"
    />

    <!-- 左侧树右键菜单 -->
    <Teleport to="body">
      <div
        v-if="contextMenuVisible"
        class="context-menu"
        :style="{ left: contextMenuPos.x + 'px', top: contextMenuPos.y + 'px' }"
        @click.stop
      >
        <!-- 空白区域右键 -->
        <template v-if="!contextTarget">
          <div v-if="hasPermission('project:req:group')" class="context-menu-item" @click="contextCreateGroup">新建分组</div>
        </template>
        <!-- 分组节点右键 -->
        <template v-else-if="contextTarget.type === 'group'">
          <div v-if="hasPermission('project:req:version:create')" class="context-menu-item" @click="contextCreateVersion">新建版本</div>
          <template v-if="contextTarget.data.isSystem !== 1">
            <div v-if="hasPermission('project:req:group')" class="context-menu-item" @click="contextCreateChildGroup">新建子分组</div>
            <div v-if="hasPermission('project:req:group')" class="context-menu-divider" />
            <div v-if="hasPermission('project:req:group')" class="context-menu-item" @click="contextEditGroup">编辑</div>
            <div v-if="hasPermission('project:req:group')" class="context-menu-item danger" @click="contextDeleteGroup">删除</div>
          </template>
        </template>
        <!-- 版本节点右键 -->
        <template v-else>
          <div v-if="hasPermission('project:req:item:create')" class="context-menu-item" @click="contextCreateItem">新建需求</div>
          <div v-if="hasPermission('project:req:item:create')" class="context-menu-divider" />
          <div v-if="hasPermission('project:req:version:edit')" class="context-menu-item" @click="contextEditVersion">编辑</div>
          <div v-if="hasPermission('project:req:version:delete')" class="context-menu-item danger" @click="contextDeleteVersion">删除</div>
        </template>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.req-project-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  background: #ecf5ff;
  border: 1px solid #c6e2ff;
  border-radius: 6px;
  margin-bottom: 16px;
  font-size: 13px;
  color: rgba(0, 0, 0, 0.65);
}

.req-project-bar .project-name {
  font-weight: 600;
  color: #409eff;
}

.req-project-bar .bar-sep {
  color: rgba(0, 0, 0, 0.25);
}

/* ===== 左右分栏布局 ===== */
.req-main {
  display: flex;
  gap: 16px;
  min-height: calc(100vh - 220px);
}

.req-left-panel {
  width: 300px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.req-right-panel {
  flex: 1;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
}

/* 按钮靠右（面板头已无标题，仅保留操作按钮） */
.panel-header-action {
  margin-left: auto;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
}

/* ===== 分组/版本树 ===== */
.group-tree {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.group-tree :deep(.el-tree-node__content) {
  height: auto;
  padding: 2px 0;
}

.group-node,
.version-node {
  display: flex;
  align-items: center;
  flex: 1;
  gap: 6px;
  padding: 4px 6px;
  border-radius: 4px;
  font-size: 13px;
  width: 100%;
  cursor: pointer;
  transition: background 0.15s;
}

.group-node:hover,
.version-node:hover {
  background: #f5f7fa;
}

.version-node.active {
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

.group-node .group-name {
  font-weight: 500;
}

.group-count {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
  margin-left: auto;
}

.group-lock {
  font-size: 10px;
  color: #c0c4cc;
  flex-shrink: 0;
  margin-left: 2px;
}

/* ===== 空状态 ===== */
.empty-text {
  padding: 32px 0;
  color: rgba(0, 0, 0, 0.25);
  font-size: 13px;
  text-align: center;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 300px;
  color: rgba(0, 0, 0, 0.25);
  font-size: 14px;
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
