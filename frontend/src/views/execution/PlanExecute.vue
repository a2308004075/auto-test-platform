<!--
 @author HXN
 @date 2026-09-28
 @description 手动测试计划执行工作台（执行页）
-->
<script setup lang="ts">
/**
 * 手动计划执行页 - M9
 * 行 = 计划关联手动化用例；结果列表头固定「执行结果」，每行显示该行最后一个有记录的列，值为「列名：状态，备注」
 * 点击行内【执行】按钮弹窗，一次标记该用例在所有结果列上的结果（下拉 + 备注）
 * 【执行完成】快照列定义与各格记录结果，形成测试记录
 */
import { ref, computed, onMounted } from 'vue'
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
import { useDict } from '@/composables/useDict'
import EditPageHeader from '@/components/EditPageHeader/index.vue'

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

onMounted(loadWorkbench)
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

    <!-- 执行弹窗：一次标记该用例在所有结果列上的结果 -->
    <el-dialog v-model="executeDialogVisible" title="标记执行结果" width="680px">
      <div class="exec-case-info">
        <span class="exec-case-title">用例：{{ executeRow?.title }}</span>
        <el-tag size="small" type="info">ID {{ executeRow?.manualCaseId }}</el-tag>
      </div>
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
        <el-button @click="executeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="executeSaving" @click="confirmExecute">保存</el-button>
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
.exec-rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 380px;
  overflow-y: auto;
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
