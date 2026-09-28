<!--
 @author HXN
 @date 2026-09-28
 @description 手动测试计划执行工作台（执行页）
-->
<script setup lang="ts">
/**
 * 手动计划执行页 - M9
 * 行 = 计划关联手动化用例；结果列表头固定「执行结果」，每行显示该行最后一个有记录的列，值为「列名：状态，备注」
 * 点击行内【执行】按钮弹窗：展示用例标题/内容/信息字段（分组、状态、动态字段，与手动用例详情同源），
 * 一次标记该用例在所有结果列上的结果（下拉 + 备注）
 * 【执行完成】快照列定义与各格记录结果，形成测试记录
 */
import { ref, reactive, computed, onMounted, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  getCurrentExecution,
  updateRoundResult,
  completeExecution,
  cancelExecution,
  createResultColumn,
  renameResultColumn,
  deleteResultColumn,
} from '@/api/execution'
import { getCustomFieldsForRender } from '@/api/customField'
import { getManualCaseGroups } from '@/api/manualCase'
import { createDefect, getDefectGroups } from '@/api/defect'
import { getContentTemplates } from '@/api/contentTemplate'
import { useDict } from '@/composables/useDict'
import { useManualCaseStatusOptions } from '@/composables/useManualCaseStatus'
import { isScopeVisible } from '@/utils/customFieldScope'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import '@wangeditor/editor/dist/css/style.css'
import EditPageHeader from '@/components/EditPageHeader/index.vue'
import DynamicFieldGrid from '@/components/DynamicFieldGrid/index.vue'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))
const planId = computed(() => Number(route.params.planId))

const loading = ref(false)
const loadError = ref('')
const executionId = ref<number | null>(null)
const planName = ref('')
const executionStatus = ref('')
const totalCases = ref(0)
const createdAt = ref('')
const columns = ref<any[]>([])
const rows = ref<any[]>([])

// 结果状态下拉：test_result_status 字典过滤 通过/失败/跳过
const { options: statusDictOptions } = useDict('test_result_status')
const cellStatusOptions = computed(() =>
  statusDictOptions.value.filter((o) => ['PASSED', 'FAILED', 'SKIPPED'].includes(o.value)),
)

// 展示样式映射（非字典选项，仅 el-tag 配色）
const executionStatusMap: Record<string, { label: string; type: string }> = {
  WAITING_MANUAL: { label: '进行中', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
  CANCELLED: { label: '已作废', type: 'info' },
}
const priorityTypeMap: Record<string, string> = { 高: 'danger', 中: 'warning', 低: 'info' }

async function loadWorkbench() {
  loading.value = true
  loadError.value = ''
  try {
    const res: any = await getCurrentExecution(planId.value)
    const data = res.data || {}
    executionId.value = data.executionId
    planName.value = data.planName || ''
    executionStatus.value = data.status || ''
    totalCases.value = data.totalCases || 0
    createdAt.value = data.createdAt || ''
    columns.value = data.columns || []
    rows.value = data.rows || []
  } catch (e: any) {
    loadError.value = e?.response?.data?.message || '加载执行工作台失败'
  } finally {
    loading.value = false
  }
}

function formatTime(s?: string) {
  return s ? s.substring(0, 19).replace('T', ' ') : '-'
}

/** 读取某行某列的单元格（无记录返回 null） */
function cellOf(row: any, columnId: number) {
  return row.roundResults?.[String(columnId)] || null
}

/** 本地写入单元格；status 与 remark 皆空则移除该列条目（与后端覆盖式语义一致） */
function applyCell(row: any, columnId: number, status: string, remark: string) {
  if (!row.roundResults) row.roundResults = {}
  const key = String(columnId)
  if (!status && !remark) {
    delete row.roundResults[key]
  } else {
    row.roundResults[key] = { status, remark }
  }
}

/** 结果状态展示标签（取自字典选项，避免硬编码文案） */
function statusLabelOf(status?: string) {
  if (!status) return ''
  return cellStatusOptions.value.find((o) => o.value === status)?.label || status
}

/** 该行最后一个有执行结果的列（从最后一列向前找，状态或备注有值即为有记录） */
function lastRecordedOf(row: any): { col: any; cell: any } | null {
  for (let i = columns.value.length - 1; i >= 0; i--) {
    const col = columns.value[i]
    const cell = cellOf(row, col.id)
    if (cell && (cell.status || cell.remark)) return { col, cell }
  }
  return null
}

/** 单元格值文本：列名：状态，备注（状态或备注为空时自动省略对应片段） */
function cellValueText(row: any) {
  const found = lastRecordedOf(row)
  if (!found) return ''
  const parts: string[] = []
  if (found.cell.status) parts.push(statusLabelOf(found.cell.status))
  if (found.cell.remark) parts.push(found.cell.remark)
  return `${found.col.columnName}：${parts.join('，')}`
}

/** 值显示截断：超过 20 字符以省略号结尾，完整内容由悬浮提示展示 */
function cellValueDisplay(row: any) {
  const text = cellValueText(row)
  return text.length > 20 ? text.slice(0, 20) + '…' : text
}

// ===== 执行弹窗（一次标记该用例在所有结果列上的结果） =====
const executeDialogVisible = ref(false)
const executeSaving = ref(false)
const executeRow = ref<any>(null)
const executeForm = ref<Array<{ columnId: number; columnName: string; status: string; remark: string }>>([])

// 弹窗「用例信息」元数据：动态字段定义（详情位置）与分组名映射，与手动用例列表/详情同源
const caseFieldDefs = ref<any[]>([])
const caseGroupNameMap = ref<Record<string, string>>({})
// 用例状态选项优先读【页面配置-手动用例字段】的「状态」字段配置（按项目），无配置回退内置选项
const { options: caseStatusOptions } = useManualCaseStatusOptions(() => projectId.value)

/** 加载弹窗「用例信息」所需元数据（动态字段定义 + 分组名映射） */
async function loadCaseMeta() {
  try {
    const res: any = await getCustomFieldsForRender({
      projectId: projectId.value,
      module: 'manual_case',
      viewType: 'edit',
    })
    // 状态字段（case_status）单独展示不进字段列表；仅显示「详情」位置的字段（与用例详情页字段信息一致）
    caseFieldDefs.value = (res.data || []).filter(
      (f: any) => f.fieldKey !== 'case_status' && isScopeVisible(f.displayScope, 'detail'),
    )
  } catch { caseFieldDefs.value = [] }
  try {
    const res: any = await getManualCaseGroups(projectId.value)
    const map: Record<string, string> = {}
    for (const g of res.data || []) map[String(g.id)] = g.name
    caseGroupNameMap.value = map
  } catch { caseGroupNameMap.value = {} }
}

/** 解析动态字段选项：优先 field.options，回退 optionsJson（与手动用例列表/详情逻辑一致） */
function parseFieldOptions(field: any): any[] {
  if (field?.options && field.options.length > 0) return field.options
  if (!field?.optionsJson) return []
  try { return JSON.parse(field.optionsJson) } catch { return [] }
}

/** 动态字段展示文本：下拉/用户/环境类按 value 翻译 label，其余原样显示；空值返回空串 */
function caseFieldText(field: any): string {
  const value = executeRow.value?.customFields?.[field.fieldKey]
  if (value === undefined || value === null || value === '') return ''
  if (['select', 'user', 'environment'].includes(field.fieldType)) {
    const hit = parseFieldOptions(field).find((o: any) => String(o.value) === String(value))
    return hit ? hit.label : value
  }
  return value
}

/** 用例状态展示标签（配置驱动，与手动用例列表一致） */
function caseStatusLabelOf(status: any) {
  if (status === undefined || status === null || status === '') return ''
  const hit = caseStatusOptions.value.find((o: any) => String(o.value) === String(status))
  return hit ? hit.label : String(status)
}

/** 所属分组名称（未分组/分组已删除统一显示「未分组」） */
function caseGroupNameOf(groupId: any) {
  if (groupId === undefined || groupId === null) return '未分组'
  return caseGroupNameMap.value[String(groupId)] || '未分组'
}

/** 附件大小展示（B/KB/MB 保留 1 位小数；非法值返回空串） */
function formatFileSize(size: any) {
  const n = Number(size)
  if (!Number.isFinite(n) || n < 0) return ''
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

/** 打开执行弹窗：以该用例当前各格记录为表单初值 */
function openExecuteDialog(row: any) {
  if (!columns.value.length) {
    ElMessage.warning('暂无可标记的结果列，请先点击右上角「编辑执行列」')
    return
  }
  executeRow.value = row
  executeForm.value = columns.value.map((col) => {
    const cell = cellOf(row, col.id)
    return { columnId: col.id, columnName: col.columnName, status: cell?.status || '', remark: cell?.remark || '' }
  })
  executeDialogVisible.value = true
}

/** 保存标记：仅提交发生变化的列，逐列写入（中途失败保留弹窗便于重试） */
async function confirmExecute() {
  if (!executeRow.value || !executionId.value) return
  const row = executeRow.value
  const changed = executeForm.value.filter((item) => {
    const cell = cellOf(row, item.columnId)
    return (item.status || '').trim() !== (cell?.status || '') || (item.remark || '').trim() !== (cell?.remark || '')
  })
  if (!changed.length) {
    executeDialogVisible.value = false
    return
  }
  executeSaving.value = true
  try {
    for (const item of changed) {
      const status = (item.status || '').trim()
      const remark = (item.remark || '').trim()
      await updateRoundResult(executionId.value, {
        resultId: row.resultId,
        columnId: item.columnId,
        status: status || undefined,
        remark: remark || undefined,
      })
      applyCell(row, item.columnId, status, remark)
    }
    ElMessage.success('执行结果已保存')
    executeDialogVisible.value = false
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存执行结果失败')
  } finally {
    executeSaving.value = false
  }
}

// ===== 快速创建缺陷（与【新建缺陷】页同构：标题/内容/附件/字段信息，无关联区块；创建后自动关联当前用例并留在执行弹窗） =====
const defectDialogVisible = ref(false)
const defectCreating = ref(false)
const defectForm = reactive({ title: '', content: '', groupId: null as number | null })
// 动态字段值（fieldKey -> 值，与缺陛建页一致：默认值初始化、随创建一次性提交）
const defectFieldValues = ref<Record<string, any>>({})
// 字段信息区块默认折叠（弹窗过长时优先展示标题/内容/附件，需要时展开填写）
const defectFieldsExpanded = ref(false)

// WangEditor（缺陷内容可编辑；与缺陷新建页同配置，禁上传与全屏）
const defectEditorRef = shallowRef<any>(null)
const defectEditorConfig = {
  placeholder: '请输入缺陷内容...',
  excludeKeys: ['fullScreen'],
  MENU_CONF: {
    uploadImage: { disabled: true },
    uploadVideo: { disabled: true },
  },
}
function onDefectEditorCreated(editor: any) {
  defectEditorRef.value = editor
}

// 缺陷字段配置与分组（【页面配置-缺陷字段】edit 视图；与缺陛建页同源：排除状态字段、仅「新建」位置可见）
const defectFieldDefs = ref<any[]>([])
const defectVisibleFields = computed(() =>
  defectFieldDefs.value.filter((f: any) => isScopeVisible(f.displayScope, 'create')),
)
const defectGroups = ref<any[]>([])
const defectUserGroups = computed(() => defectGroups.value.filter((g: any) => g.isSystem !== 1))
// 缺陷内容模板（【页面配置-内容模板】按项目+bizType=defect；与【新建缺陷】页同源，打开弹窗自动填入）
const defectTemplateContent = ref('')

/** 去除富文本标签保留纯文本（模板内容有效性判断用，与缺陛建页一致） */
function stripHtml(html: string): string {
  return html.replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim()
}

/** 加载快速创建缺陷所需元数据（缺陷字段定义 + 缺陷分组 + 内容模板），页面加载时预热 */
async function loadDefectMeta() {
  try {
    const res: any = await getCustomFieldsForRender({
      projectId: projectId.value,
      module: 'defect',
      viewType: 'edit',
    })
    // 状态字段（defect_status）不进字段信息区（初始状态后端固定 NEW，与缺陛建页一致）
    defectFieldDefs.value = (res.data || []).filter((f: any) => f.fieldKey !== 'defect_status')
  } catch { defectFieldDefs.value = [] }
  try {
    const res: any = await getDefectGroups(projectId.value)
    defectGroups.value = res.data || []
  } catch { defectGroups.value = [] }
  try {
    const res: any = await getContentTemplates({ projectId: projectId.value, bizType: 'defect' })
    const tpl = (res.data || [])[0]
    // 模板内容去标签后非空才有效（与缺陛建页 applyContentTemplateOnCreate 判断一致）
    if (tpl && stripHtml(tpl.content || '')) {
      defectTemplateContent.value = tpl.content
    }
  } catch { defectTemplateContent.value = '' }
}

/** 重置动态字段值为配置默认值（每次打开弹窗重新初始化，不残留上次输入） */
function resetDefectFieldValues() {
  const values: Record<string, any> = {}
  for (const field of defectVisibleFields.value) {
    if (field.defaultValue !== null && field.defaultValue !== undefined && field.defaultValue !== '') {
      values[field.fieldKey] = field.defaultValue
    }
  }
  defectFieldValues.value = values
}

// 附件（本页暂存随创建一次性提交，与缺陛建页一致）
const defectAttachmentVisible = ref(false)
const defectAttachmentForm = reactive({ fileName: '', fileUrl: '', fileSize: undefined as number | undefined })
const draftDefectAttachments = ref<any[]>([])

/** 添加附件（暂存到待提交列表，随创建一并提交） */
function handleAddDefectAttachment() {
  if (!defectAttachmentForm.fileName || !defectAttachmentForm.fileUrl) {
    ElMessage.warning('请填写文件名和链接')
    return
  }
  draftDefectAttachments.value.push({
    fileName: defectAttachmentForm.fileName,
    fileUrl: defectAttachmentForm.fileUrl,
    fileSize: defectAttachmentForm.fileSize,
  })
  defectAttachmentVisible.value = false
  Object.assign(defectAttachmentForm, { fileName: '', fileUrl: '', fileSize: undefined })
}

/** 移除暂存附件 */
function handleDeleteDefectAttachment(index: number) {
  draftDefectAttachments.value.splice(index, 1)
}

/** 打开快速创建缺陷弹窗：标题留空手填，内容自动填入缺陷模板，字段/分组/附件重置 */
function openDefectDialog() {
  if (!executeRow.value) return
  defectForm.title = ''
  defectForm.content = defectTemplateContent.value
  defectForm.groupId = null
  resetDefectFieldValues()
  draftDefectAttachments.value = []
  defectFieldsExpanded.value = false
  defectDialogVisible.value = true
}

/** 提交创建缺陷：动态字段/附件随创建提交，自动关联当前用例（后端校验归属并回填标题快照），成功后留在执行弹窗继续标记 */
async function confirmCreateDefect() {
  if (!executeRow.value) return
  const title = defectForm.title.trim()
  if (!title) {
    ElMessage.warning('请输入标题')
    return
  }
  defectCreating.value = true
  try {
    const res: any = await createDefect(projectId.value, {
      groupId: defectForm.groupId,
      title,
      content: defectForm.content,
      customFields: { ...defectFieldValues.value },
      attachments: draftDefectAttachments.value,
      relations: [{
        relationType: 'RELATED',
        targetType: 'MANUAL_CASE',
        targetId: executeRow.value.manualCaseId,
        targetTitle: executeRow.value.title,
      }],
    })
    const defectNo = res?.data?.defectNo
    ElMessage.success(defectNo ? `缺陷创建成功：${defectNo}` : '缺陷创建成功')
    defectDialogVisible.value = false
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '创建缺陷失败')
  } finally {
    defectCreating.value = false
  }
}

// ===== 结果列管理（编辑执行列弹窗：全部列可改名，支持添加/删除，统一保存） =====
interface EditColumnItem {
  key: number
  id?: number
  columnName: string
  originName: string
}

const editDialogVisible = ref(false)
const editSaving = ref(false)
const editColumns = ref<EditColumnItem[]>([])
let editRowKey = 1

/** 打开「编辑执行列」弹窗：以当前列定义为初值 */
function openEditColumnDialog() {
  editColumns.value = columns.value.map((col) => ({
    key: editRowKey++,
    id: col.id,
    columnName: col.columnName,
    originName: col.columnName,
  }))
  editDialogVisible.value = true
}

/** 列表末尾追加一行空列 */
function addEditRow() {
  editColumns.value.push({ key: editRowKey++, columnName: '', originName: '' })
}

/** 将某行移出列表（已有列在保存时统一提交删除） */
function removeEditRow(index: number) {
  editColumns.value.splice(index, 1)
}

/** 保存编辑：校验后按「先删除（释放名称）→ 再改名 → 再新增」逐项提交，失败保留弹窗便于重试 */
async function saveEditColumns() {
  const names = editColumns.value.map((item) => item.columnName.trim())
  if (names.some((n) => !n)) {
    ElMessage.warning('列名称不能为空')
    return
  }
  const dup = names.find((n, i) => names.indexOf(n) !== i)
  if (dup) {
    ElMessage.warning(`列名称存在重复：${dup}`)
    return
  }
  const keptIds = new Set(editColumns.value.map((item) => item.id))
  const deleted = columns.value.filter((col) => !keptIds.has(col.id))
  const renamed = editColumns.value.filter(
    (item) => item.id !== undefined && item.columnName.trim() !== item.originName,
  )
  const added = editColumns.value.filter((item) => item.id === undefined)
  if (!deleted.length && !renamed.length && !added.length) {
    editDialogVisible.value = false
    return
  }
  if (deleted.length) {
    try {
      await ElMessageBox.confirm(
        `将删除 ${deleted.length} 个执行列：${deleted.map((c) => `「${c.columnName}」`).join('，')}，删除后其已记录的结果将不再显示。是否确认？`,
        '删除执行列',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  editSaving.value = true
  try {
    for (const col of deleted) {
      await deleteResultColumn(col.id)
    }
    for (const item of renamed) {
      await renameResultColumn(item.id as number, { columnName: item.columnName.trim() })
    }
    for (const item of added) {
      await createResultColumn(planId.value, { columnName: item.columnName.trim() })
    }
    await loadWorkbench()
    ElMessage.success('执行列已保存')
    editDialogVisible.value = false
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存执行列失败')
  } finally {
    editSaving.value = false
  }
}

// ===== 执行完成 / 作废 =====
async function handleComplete() {
  if (!executionId.value) return
  try {
    await ElMessageBox.confirm(
      '执行完成后将形成测试记录：当前列定义与各格记录结果将被归档保存，之后不能继续修改。是否确认完成？',
      '执行完成',
      { type: 'warning', confirmButtonText: '确认完成', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await completeExecution(executionId.value)
    ElMessage.success('执行已完成，已形成测试记录')
    router.push(`/project/${projectId.value}/executions/${executionId.value}`)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '执行完成失败')
  }
}

async function handleCancel() {
  if (!executionId.value) return
  try {
    await ElMessageBox.confirm(
      '作废后本次执行单将冻结为已作废状态，不再继续记录（已填内容保留）。是否确认作废？',
      '作废执行单',
      { type: 'warning', confirmButtonText: '作废', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await cancelExecution(executionId.value)
    ElMessage.success('执行单已作废')
    router.push(`/project/${projectId.value}/plans`)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '作废失败')
  }
}

function goBack() {
  router.push(`/project/${projectId.value}/plans`)
}

onMounted(() => {
  loadWorkbench()
  loadCaseMeta()
  loadDefectMeta()
})
</script>

<template>
  <div v-loading="loading">
    <!-- 页头：执行：计划名 + 操作按钮 -->
    <EditPageHeader :title="`执行：${planName || '加载中...'}`">
      <el-button @click="goBack">返回</el-button>
      <template v-if="executionId">
        <el-button type="primary" @click="openEditColumnDialog">编辑执行列</el-button>
        <el-button type="danger" @click="handleCancel">作废</el-button>
        <el-button type="primary" @click="handleComplete">执行完成</el-button>
      </template>
    </EditPageHeader>

    <!-- 加载失败提示 -->
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" style="margin-bottom:16px">
      <el-button link type="primary" style="padding:0" @click="goBack">返回测试计划列表</el-button>
    </el-alert>

    <template v-else>
      <!-- 元信息行 -->
      <div class="meta-row">
        <span>用例数：<b>{{ totalCases }}</b></span>
        <span>创建时间：<b>{{ formatTime(createdAt) }}</b></span>
        <el-tag v-if="executionStatusMap[executionStatus]" :type="(executionStatusMap[executionStatus].type) as any" size="small">
          {{ executionStatusMap[executionStatus].label }}
        </el-tag>
        <span class="meta-tip">点击每条用例的「执行」标记结果；全部记录完成后点击「执行完成」归档为测试记录</span>
      </div>

      <!-- 执行记录表格：固定用例列 + 执行结果列（每行取最后一个有记录的列；全部轮次在【执行】弹窗中标记与查看） -->
      <el-card>
        <el-table :data="rows" row-key="resultId" border stripe>
          <el-table-column prop="manualCaseId" label="ID" width="80" />
          <el-table-column prop="title" label="用例标题" min-width="220" show-overflow-tooltip />
          <el-table-column label="优先级" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.priority" :type="(priorityTypeMap[row.priority] || 'info') as any" size="small">
                {{ row.priority }}
              </el-tag>
              <span v-else style="color:#c0c4cc">-</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.caseType === 'EXCEPTION' ? 'warning' : 'info'" size="small">
                {{ row.caseType === 'EXCEPTION' ? '异常' : '正常' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="columns.length" width="220">
            <template #header>
              <div class="col-header">执行结果</div>
            </template>
            <template #default="{ row }">
              <el-tooltip
                :disabled="!cellValueText(row)"
                :content="cellValueText(row)"
                placement="top"
                :show-after="200"
              >
                <span v-if="cellValueText(row)" class="cell-value">{{ cellValueDisplay(row) }}</span>
                <span v-else class="cell-empty">-</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openExecuteDialog(row)">执行</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="rows.length === 0" class="empty-tip">计划暂无关联手动化用例，请先在计划中关联测试用例</div>
      </el-card>
    </template>

    <!-- 执行弹窗：展示用例标题/内容/信息字段，一次标记该用例在所有结果列上的结果 -->
    <el-dialog v-model="executeDialogVisible" title="用例执行" width="720px">
      <div class="exec-case-info">
        <span class="exec-case-title">标题：{{ executeRow?.title }}</span>
        <el-tag size="small" type="info">ID {{ executeRow?.manualCaseId }}</el-tag>
      </div>
      <!-- 用例内容（富文本只读） -->
      <div class="exec-block-title">内容</div>
      <div v-if="executeRow?.content" class="exec-content" v-html="executeRow.content"></div>
      <div v-else class="exec-content exec-content-empty">暂无内容</div>
      <!-- 用例附件（与用例详情页同源，点击新窗口打开） -->
      <div class="exec-block-title">附件</div>
      <div v-if="executeRow?.attachments?.length" class="exec-attachments">
        <a
          v-for="file in executeRow.attachments"
          :key="file.id"
          class="exec-attachment"
          :href="file.fileUrl"
          target="_blank"
          rel="noopener"
          :title="file.fileUrl"
        >
          <span class="exec-attachment-name">{{ file.fileName }}</span>
          <span v-if="file.fileSize != null" class="exec-attachment-size">{{ formatFileSize(file.fileSize) }}</span>
        </a>
      </div>
      <div v-else class="exec-attachment-empty">暂无附件</div>
      <!-- 字段：所属分组/用例状态 + 动态字段（与手动用例详情同源，仅显示「详情」位置的字段） -->
      <div class="exec-block-title">字段</div>
      <div class="exec-meta">
        <div class="exec-meta-item">
          <span class="exec-meta-label">所属分组</span>
          <span class="exec-meta-value">{{ caseGroupNameOf(executeRow?.groupId) }}</span>
        </div>
        <div class="exec-meta-item">
          <span class="exec-meta-label">用例状态</span>
          <span class="exec-meta-value">{{ caseStatusLabelOf(executeRow?.caseStatus) || '-' }}</span>
        </div>
        <div v-for="field in caseFieldDefs" :key="field.fieldKey" class="exec-meta-item">
          <span class="exec-meta-label">{{ field.fieldLabel }}</span>
          <span class="exec-meta-value" :title="caseFieldText(field)">{{ caseFieldText(field) || '-' }}</span>
        </div>
      </div>
      <!-- 结果列标记（每列一个下拉 + 备注） -->
      <div class="exec-block-title">执行结果</div>
      <div class="exec-rows">
        <div v-for="item in executeForm" :key="item.columnId" class="exec-row">
          <span class="exec-col-name" :title="item.columnName">{{ item.columnName }}</span>
          <el-select v-model="item.status" placeholder="结果" clearable size="small" style="width:120px">
            <el-option v-for="opt in cellStatusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
          <el-input
            v-model="item.remark"
            size="small"
            maxlength="200"
            placeholder="备注（选填，不超过 200 字）"
            style="flex:1"
          />
        </div>
      </div>
      <template #footer>
        <el-button type="primary" plain @click="openDefectDialog">快速创建缺陷</el-button>
        <el-button @click="executeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="executeSaving" @click="confirmExecute">保存</el-button>
      </template>
    </el-dialog>

    <!-- 快速创建缺陷弹窗：与【新建缺陷】页同构（标题/内容/附件/字段信息，无关联区块），创建后自动关联当前用例 -->
    <el-dialog
      v-model="defectDialogVisible"
      title="新建缺陷"
      width="720px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item label="标题" required>
          <el-input v-model="defectForm.title" maxlength="500" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="内容">
          <div class="defect-editor-wrapper">
            <Toolbar :editor="defectEditorRef" :default-config="defectEditorConfig" mode="default" style="border-bottom: 1px solid #ccc" />
            <Editor v-model="defectForm.content" :default-config="defectEditorConfig" mode="default" style="height: 240px; overflow-y: hidden" @on-created="onDefectEditorCreated" />
          </div>
        </el-form-item>
      </el-form>
      <!-- 附件（本页暂存随创建一次性提交；未添加附件时不显示空表格） -->
      <div class="defect-block-title">附件</div>
      <div class="defect-toolbar">
        <el-button type="primary" size="small" @click="defectAttachmentVisible = true">添加附件</el-button>
      </div>
      <el-table v-if="draftDefectAttachments.length > 0" :data="draftDefectAttachments" border stripe size="small">
        <el-table-column prop="fileName" label="文件名" />
        <el-table-column prop="fileSize" label="大小（字节）" width="130" />
        <el-table-column label="操作" width="80">
          <template #default="{ $index }">
            <el-button type="danger" link size="small" @click="handleDeleteDefectAttachment($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <!-- 字段（所属分组 + 动态字段，仅「新建」位置可见；默认折叠，点击展开） -->
      <div class="defect-block-header">
        <span class="defect-block-title">字段</span>
        <el-button link type="primary" size="small" @click="defectFieldsExpanded = !defectFieldsExpanded">
          {{ defectFieldsExpanded ? '收起' : '展开' }}
        </el-button>
      </div>
      <div v-show="defectFieldsExpanded">
        <el-form label-position="top">
          <DynamicFieldGrid
            v-if="defectVisibleFields.length > 0"
            :fields="defectVisibleFields"
            :model-value="defectFieldValues"
            @update:model-value="defectFieldValues = $event"
          >
            <template #prepend>
              <el-form-item label="所属分组">
                <el-select v-model="defectForm.groupId" placeholder="未分组" clearable filterable style="width: 100%">
                  <el-option v-for="g in defectUserGroups" :key="g.id" :value="g.id" :label="g.name" />
                </el-select>
              </el-form-item>
            </template>
          </DynamicFieldGrid>
          <el-form-item v-else label="所属分组">
            <el-select v-model="defectForm.groupId" placeholder="未分组" clearable filterable style="width: 100%">
              <el-option v-for="g in defectUserGroups" :key="g.id" :value="g.id" :label="g.name" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
      <div class="defect-create-tip">创建后将自动关联当前用例：{{ executeRow?.title }}（ID {{ executeRow?.manualCaseId }}）</div>
      <template #footer>
        <el-button @click="defectDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="defectCreating" @click="confirmCreateDefect">创建</el-button>
      </template>
    </el-dialog>

    <!-- 添加附件弹窗（缺陷暂存附件录入，随创建一并提交） -->
    <el-dialog v-model="defectAttachmentVisible" title="添加附件" width="460px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="文件名" required>
          <el-input v-model="defectAttachmentForm.fileName" />
        </el-form-item>
        <el-form-item label="文件链接" required>
          <el-input v-model="defectAttachmentForm.fileUrl" placeholder="文件访问 URL" />
        </el-form-item>
        <el-form-item label="文件大小（字节）">
          <el-input-number v-model="defectAttachmentForm.fileSize" :controls="false" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="defectAttachmentVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAddDefectAttachment">确定</el-button>
      </template>
    </el-dialog>

    <!-- 编辑执行列弹窗：列出全部执行列可改名，【添加】新增、【删除】移除，【保存】统一提交 -->
    <el-dialog v-model="editDialogVisible" title="编辑执行列" width="520px">
      <div class="col-edit-rows">
        <div v-for="(item, index) in editColumns" :key="item.key" class="col-edit-row">
          <el-input v-model="item.columnName" maxlength="100" placeholder="请输入列名称" style="flex:1" />
          <el-button link type="danger" @click="removeEditRow(index)">删除</el-button>
        </div>
      </div>
      <el-button class="col-edit-add" :icon="Plus" plain @click="addEditRow">添加</el-button>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEditColumns">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.meta-row {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: #909399;
  margin-bottom: 16px;
  flex-wrap: wrap;
  align-items: center;
}
.meta-row b {
  color: #606266;
}
.meta-tip {
  margin-left: auto;
  font-size: 12px;
  color: #c0c4cc;
}
.col-header {
  display: flex;
  align-items: center;
  justify-content: center;
}
.cell-value {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: #606266;
}
.cell-empty {
  color: #c0c4cc;
}
.exec-case-info {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 14px;
  color: #606266;
}
.exec-case-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}
.exec-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 16px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 4px;
  margin-bottom: 12px;
}
.exec-meta-item {
  display: flex;
  gap: 8px;
  font-size: 13px;
  min-width: 0;
}
.exec-meta-label {
  flex-shrink: 0;
  color: #909399;
}
.exec-meta-value {
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.exec-block-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}
.exec-content {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 12px;
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
  max-height: 200px;
  overflow-y: auto;
}
.exec-content :deep(img) {
  max-width: 100%;
}
.exec-content :deep(p) {
  margin: 4px 0;
}
.exec-content-empty {
  color: #c0c4cc;
}
.exec-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.exec-attachment {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 4px 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 13px;
  color: #409eff;
  text-decoration: none;
}
.exec-attachment:hover {
  border-color: #409eff;
}
.exec-attachment-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.exec-attachment-size {
  flex-shrink: 0;
  font-size: 12px;
  color: #909399;
}
.exec-attachment-empty {
  margin-bottom: 12px;
  font-size: 13px;
  color: #c0c4cc;
}
.exec-rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 280px;
  overflow-y: auto;
}
.defect-editor-wrapper {
  width: 100%;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  z-index: 100;
}
.defect-editor-wrapper :deep(.editor-toolbar) {
  border: none;
}
.defect-block-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin: 4px 0 8px;
}
.defect-block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 4px 0 8px;
}
.defect-block-header .defect-block-title {
  margin: 0;
}
.defect-toolbar {
  margin-bottom: 8px;
}
.defect-create-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.exec-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.exec-col-name {
  width: 160px;
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: #606266;
}
.col-edit-rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 360px;
  overflow-y: auto;
}
.col-edit-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.col-edit-add {
  width: 100%;
  margin-top: 10px;
}
.empty-tip {
  text-align: center;
  color: #909399;
  padding: 16px;
  font-size: 13px;
}
</style>
