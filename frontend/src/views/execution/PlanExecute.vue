<!--
 @author HXN
 @date 2026-09-28
 @description 手动测试计划执行工作台（执行页）
-->
<script setup lang="ts">
/**
 * 手动计划执行页 - M9
 * 行 = 计划关联手动化用例；列 = 计划级自定义结果列（历次执行共享）
 * 单元格 = 结果下拉（通过/失败/跳过）+ 文本备注，即时保存
 * 【执行完成】快照列定义与各格记录结果，形成测试记录
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import {
  getCurrentExecution,
  updateRoundResult,
  completeExecution,
  cancelExecution,
  createResultColumn,
  renameResultColumn,
  moveResultColumn,
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

// 单元格保存中标识（resultId-columnId），避免同格重复提交
const cellSavingKey = ref('')

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

/** 结果下拉变更：乐观本地更新 → 即时保存 → 失败回滚 */
async function handleCellStatusChange(row: any, column: any, value: string | undefined) {
  if (!executionId.value) return
  const status = value || ''
  const prevCell = cellOf(row, column.id)
  const prev = prevCell ? { ...prevCell } : null
  const remark = prev?.remark || ''
  applyCell(row, column.id, status, remark)

  const key = `${row.resultId}-${column.id}`
  cellSavingKey.value = key
  try {
    await updateRoundResult(executionId.value, {
      resultId: row.resultId,
      columnId: column.id,
      status: status || undefined,
      remark: remark || undefined,
    })
  } catch (e: any) {
    applyCell(row, column.id, prev?.status || '', prev?.remark || '')
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    if (cellSavingKey.value === key) cellSavingKey.value = ''
  }
}

// ===== 备注弹窗 =====
const remarkDialogVisible = ref(false)
const remarkSaving = ref(false)
const remarkForm = reactive({ resultId: 0, columnId: 0, remark: '' })

function openRemarkDialog(row: any, column: any) {
  const cell = cellOf(row, column.id)
  remarkForm.resultId = row.resultId
  remarkForm.columnId = column.id
  remarkForm.remark = cell?.remark || ''
  remarkDialogVisible.value = true
}

async function confirmRemark() {
  if (!executionId.value) return
  const row = rows.value.find((r) => r.resultId === remarkForm.resultId)
  if (!row) { remarkDialogVisible.value = false; return }
  const prevCell = cellOf(row, remarkForm.columnId)
  const status = prevCell?.status || ''
  const remark = (remarkForm.remark || '').trim()
  remarkSaving.value = true
  try {
    await updateRoundResult(executionId.value, {
      resultId: remarkForm.resultId,
      columnId: remarkForm.columnId,
      status: status || undefined,
      remark: remark || undefined,
    })
    applyCell(row, remarkForm.columnId, status, remark)
    remarkDialogVisible.value = false
    ElMessage.success('备注已保存')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存备注失败')
  } finally {
    remarkSaving.value = false
  }
}

// ===== 结果列管理 =====
function columnIndex(column: any) {
  return columns.value.findIndex((c) => c.id === column.id)
}

async function handleAddColumn() {
  try {
    const { value } = await ElMessageBox.prompt('请输入列名称，如：第一次台架测试结果', '添加结果列', {
      inputPlaceholder: '列名称（不超过 100 字）',
      inputValidator: (v: string) => (v && v.trim() ? true : '列名称不能为空'),
      confirmButtonText: '添加',
      cancelButtonText: '取消',
    })
    const res: any = await createResultColumn(planId.value, { columnName: value.trim() })
    if (res.data) columns.value.push(res.data)
    ElMessage.success('列已添加')
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e?.response?.data?.message || '添加列失败')
  }
}

async function handleRenameColumn(column: any) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的列名称', '重命名结果列', {
      inputValue: column.columnName,
      inputValidator: (v: string) => (v && v.trim() ? true : '列名称不能为空'),
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
    const res: any = await renameResultColumn(column.id, { columnName: value.trim() })
    column.columnName = res.data?.columnName || value.trim()
    ElMessage.success('列已重命名')
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e?.response?.data?.message || '重命名失败')
  }
}

async function handleMoveColumn(column: any, direction: 'up' | 'down') {
  try {
    const res: any = await moveResultColumn(column.id, { direction })
    if (res.data) columns.value = res.data
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '移动失败')
  }
}

async function handleDeleteColumn(column: any) {
  try {
    await ElMessageBox.confirm(
      `确定删除结果列「${column.columnName}」？删除后本次执行不再显示该列（已填内容不再展示）。`,
      '删除结果列',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteResultColumn(column.id)
    columns.value = columns.value.filter((c) => c.id !== column.id)
    ElMessage.success('列已删除')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

function handleColumnCommand(command: any) {
  const action = command?.action
  const column = command?.column
  if (!column) return
  if (action === 'rename') handleRenameColumn(column)
  else if (action === 'up') handleMoveColumn(column, 'up')
  else if (action === 'down') handleMoveColumn(column, 'down')
  else if (action === 'delete') handleDeleteColumn(column)
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
    <!-- 页头：执行：计划名 + 状态 + 操作按钮 -->
    <EditPageHeader :title="`执行：${planName || '加载中...'}`">
      <el-tag v-if="executionStatusMap[executionStatus]" :type="(executionStatusMap[executionStatus].type) as any">
        {{ executionStatusMap[executionStatus].label }}
      </el-tag>
      <el-button @click="goBack">返回</el-button>
      <template v-if="executionId">
        <el-button type="primary" @click="handleAddColumn">添加列</el-button>
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
        <span>计划：<b>{{ planName || '-' }}</b></span>
        <span>用例数：<b>{{ totalCases }}</b></span>
        <span>创建时间：<b>{{ formatTime(createdAt) }}</b></span>
        <span class="meta-tip">每格记录即时保存；全部记录完成后点击「执行完成」归档为测试记录</span>
      </div>

      <!-- 执行记录表格：固定用例列 + 动态结果列 -->
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
          <el-table-column v-for="col in columns" :key="col.id" :min-width="196">
            <template #header>
              <div class="col-header">
                <span class="col-name" :title="col.columnName">{{ col.columnName }}</span>
                <el-dropdown trigger="click" @command="handleColumnCommand">
                  <el-icon class="col-more"><ArrowDown /></el-icon>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item :command="{ action: 'rename', column: col }">重命名</el-dropdown-item>
                      <el-dropdown-item :command="{ action: 'up', column: col }"
                        :disabled="columnIndex(col) === 0">上移</el-dropdown-item>
                      <el-dropdown-item :command="{ action: 'down', column: col }"
                        :disabled="columnIndex(col) === columns.length - 1">下移</el-dropdown-item>
                      <el-dropdown-item :command="{ action: 'delete', column: col }" divided>删除</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </template>
            <template #default="{ row }">
              <div class="cell-wrap">
                <el-select
                  :model-value="cellOf(row, col.id)?.status || ''"
                  :loading="cellSavingKey === `${row.resultId}-${col.id}`"
                  placeholder="结果"
                  clearable
                  size="small"
                  style="width:104px"
                  @change="(val: any) => handleCellStatusChange(row, col, val)"
                >
                  <el-option v-for="opt in cellStatusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
                <el-button
                  link
                  size="small"
                  :type="cellOf(row, col.id)?.remark ? 'primary' : 'default'"
                  @click="openRemarkDialog(row, col)"
                >
                  {{ cellOf(row, col.id)?.remark ? '备注✓' : '备注' }}
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="columns.length === 0" class="empty-tip">
          暂无结果列，点击右上角「添加列」创建（如：第一次台架测试结果）
        </div>
        <div v-if="rows.length === 0" class="empty-tip">计划暂无关联手动化用例，请先在计划中关联测试用例</div>
      </el-card>
    </template>

    <!-- 备注弹窗 -->
    <el-dialog v-model="remarkDialogVisible" title="单元格备注" width="480px">
      <el-input
        v-model="remarkForm.remark"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        placeholder="填写该单元格的备注信息（如：复测通过、环境问题导致失败等）"
      />
      <template #footer>
        <el-button @click="remarkDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="remarkSaving" @click="confirmRemark">保存</el-button>
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
  gap: 4px;
  justify-content: center;
}
.col-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 130px;
}
.col-more {
  cursor: pointer;
  color: #909399;
  font-size: 12px;
  flex-shrink: 0;
}
.col-more:hover {
  color: #409eff;
}
.cell-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
}
.empty-tip {
  text-align: center;
  color: #909399;
  padding: 16px;
  font-size: 13px;
}
</style>
