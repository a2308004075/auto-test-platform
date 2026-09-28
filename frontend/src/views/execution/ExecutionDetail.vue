<!--
 @author HXN
 @date 2026-08-20 15:34
 @description 执行详情视图
-->
<script setup lang="ts">
/**
 * 执行详情 - M9
 * 对齐原型 execution-detail.html
 * 统计卡片 + 通过率环形图 + 用例结果过滤 + 内联执行日志
 */
import { ref, onMounted, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document } from '@element-plus/icons-vue'
import { getExecution, getExecutionResults, cancelExecution, startExecution, updateManualCaseResult } from '@/api/execution'
import { useExecutionWebSocket } from '@/composables/useExecutionWebSocket'
import { useDict, type DictOption } from '@/composables/useDict'
import EditPageHeader from '@/components/EditPageHeader/index.vue'

const route = useRoute()
const router = useRouter()
const executionId = computed(() => Number(route.params.executionId))

const execution = ref<any>({})
const results = ref<any[]>([])
const loading = ref(false)
const updatingResultId = ref<number | null>(null)

// 用例结果过滤
const caseFilter = ref('all')

// 日志级别过滤
const logLevel = ref('all')

// WebSocket 实时进度
const { progress, connected, connect } = useExecutionWebSocket(executionId)

// 合并 WebSocket 实时进度到执行数据
const liveExecution = computed(() => {
  if (progress.value) {
    return {
      ...execution.value,
      status: progress.value.status,
      totalCases: progress.value.totalCases,
      passedCases: progress.value.passedCases,
      failedCases: progress.value.failedCases,
      skippedCases: progress.value.skippedCases,
      durationMs: progress.value.durationMs,
      progressPercent: progress.value.progressPercent,
      passRate: progress.value.passRate,
      currentCaseName: progress.value.currentCaseName,
    }
  }
  return execution.value
})

const liveMessage = computed(() => progress.value?.message || '')
const liveProgressPercent = computed(() => progress.value?.progressPercent || 0)
const livePassRate = computed(() => progress.value?.passRate || 0)
const liveCurrentCase = computed(() => progress.value?.currentCaseName || '')

const detailTitle = computed(() => {
  const name = liveExecution.value?.planName || '执行详情'
  return liveExecution.value?.id ? `${name} #${liveExecution.value.id}` : name
})

const { options: _statusOptions } = useDict('execution_status')
const { options: triggerOptions } = useDict('trigger_type')

const statusTypeMap: Record<string, string> = {
  PENDING: 'info', QUEUED: 'info', RUNNING: '', WAITING_MANUAL: 'warning', COMPLETED: 'success',
  FAILED: 'danger', CANCELLED: 'warning',
  PASSED: 'success', SKIPPED: 'info', ERROR: 'danger',
}
const statusLabels: Record<string, string> = {
  PENDING: '等待中', QUEUED: '排队中', RUNNING: '执行中', WAITING_MANUAL: '待手动处理', COMPLETED: '已完成',
  FAILED: '执行失败', CANCELLED: '已取消',
  PASSED: '通过', SKIPPED: '跳过', ERROR: '错误',
}

// 手动计划：无自动执行过程，详情按“测试结果记录单”语义展示
const isManualPlan = computed(() => execution.value?.planType === 'MANUAL')

// 快照列：新模型手动执行记录在【执行完成】时快照列定义，脱离计划列定义稳定回放
const snapshotColumns = computed<any[]>(() => execution.value?.resultColumns || [])
const hasSnapshot = computed(() => isManualPlan.value && snapshotColumns.value.length > 0)

// 多轮结果单元格展示（状态/备注）
const cellStatusLabels: Record<string, string> = { PASSED: '通过', FAILED: '失败', SKIPPED: '跳过' }
const cellStatusTypeMap: Record<string, string> = { PASSED: 'success', FAILED: 'danger', SKIPPED: 'info' }

/** 读取结果行某快照列的单元格（无记录返回 null） */
function cellOf(row: any, columnId: any) {
  return row.roundResults?.[String(columnId)] || null
}

/** 执行状态文案：手动计划的 WAITING_MANUAL 显示“待记录结果” */
function statusLabel(s?: string): string {
  if (s === 'WAITING_MANUAL' && isManualPlan.value) return '待记录结果'
  return statusLabels[s || ''] || s || ''
}

function getLabel(options: DictOption[], value: string): string {
  return options.find((o) => o.value === value)?.label || value
}

/** 毫秒数转可读时长 */
function formatDuration(ms: number | null | undefined): string {
  if (!ms) return '-'
  if (ms < 1000) return `${ms}ms`
  const sec = Math.round(ms / 1000)
  if (sec < 60) return `${sec}s`
  const min = Math.floor(sec / 60)
  return `${min}m ${sec % 60}s`
}

// 通过率环形图参数
const ringCircumference = 2 * Math.PI * 34 // ≈ 213.6
const passRatePercent = computed(() => liveExecution.value?.passRate || 0)
const ringOffset = computed(() => ringCircumference * (1 - passRatePercent.value / 100))
const ringColor = computed(() =>
  passRatePercent.value >= 90 ? '#67c23a' : passRatePercent.value >= 60 ? '#e6a23c' : '#f56c6c',
)

// 用例统计
const caseStats = computed(() => ({
  total: liveExecution.value?.totalCases || 0,
  passed: liveExecution.value?.passedCases || 0,
  failed: liveExecution.value?.failedCases || 0,
  skipped: liveExecution.value?.skippedCases || 0,
}))

// 用例结果过滤
const filteredResults = computed(() => {
  if (caseFilter.value === 'all') return results.value
  if (caseFilter.value === 'pass') return results.value.filter((r: any) => r.status === 'PASSED')
  if (caseFilter.value === 'fail') return results.value.filter((r: any) => r.status === 'FAILED' || r.status === 'ERROR')
  return results.value
})

const passCount = computed(() => results.value.filter((r: any) => r.status === 'PASSED').length)
const failCount = computed(() => results.value.filter((r: any) => r.status === 'FAILED' || r.status === 'ERROR').length)

// 执行日志 - 从执行详情中解析
const executionLogs = computed(() => {
  const logs = execution.value?.logs
  if (!logs) return []
  if (typeof logs === 'string') {
    try { return JSON.parse(logs) } catch { return [] }
  }
  return Array.isArray(logs) ? logs : []
})

const filteredLogs = computed(() => {
  if (logLevel.value === 'all') return executionLogs.value
  return executionLogs.value.filter((log: any) => {
    const level = (log.level || log.phase || '').toUpperCase()
    return level.includes(logLevel.value.toUpperCase())
  })
})

async function loadData() {
  loading.value = true
  try {
    const [execRes, resultsRes] = await Promise.all([
      getExecution(executionId.value),
      getExecutionResults(executionId.value),
    ])
    execution.value = (execRes as any).data || {}
    results.value = (resultsRes as any).data || []
    // 执行中或排队中则建立 WebSocket 连接
    if (execution.value.status === 'RUNNING' || execution.value.status === 'PENDING' || execution.value.status === 'QUEUED') {
      connect()
    }
  } catch { ElMessage.error('加载执行详情失败') } finally { loading.value = false }
}

// 监听 WebSocket 进度，终态时自动刷新结果
watch(() => progress.value?.status, (status) => {
  if (status === 'WAITING_MANUAL' || status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED') {
    loadData()
  }
})

function refresh() { loadData() }

async function handleManualResult(row: any, status: 'PASSED' | 'FAILED' | 'SKIPPED') {
  if (updatingResultId.value !== null) return

  const statusLabels: Record<string, string> = {
    PASSED: '通过',
    FAILED: '失败',
    SKIPPED: '跳过',
  }

  try {
    await ElMessageBox.confirm(
      `确定将手动化用例「${row.caseName || '-'}」标记为${statusLabels[status]}吗？`,
      '确认测试结果',
      { type: status === 'FAILED' ? 'warning' : 'info' },
    )
    updatingResultId.value = row.id
    await updateManualCaseResult(executionId.value, { resultId: row.id, status })
    ElMessage.success(`已标记为${statusLabels[status]}`)
    await loadData()
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e?.response?.data?.message || '更新测试结果失败')
    }
  } finally {
    updatingResultId.value = null
  }
}

async function handleCancel() {
  try {
    await cancelExecution(executionId.value)
    ElMessage.success(isManualPlan.value ? '记录单已作废' : '已取消执行')
    loadData()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || (isManualPlan.value ? '作废失败' : '取消失败'))
  }
}

function handleReRun() {
  const planId = execution.value?.planId
  if (!planId) { ElMessage.warning('无关联计划，无法重新执行'); return }
  // 手动计划：跳转执行页（进行中执行单在执行页加载时获取或创建）
  if (isManualPlan.value) {
    router.push(`/project/${route.params.id}/plans/${planId}/execute`)
    return
  }
  ElMessageBox.confirm('确定重新执行此测试计划？', '重新执行', { type: 'info' })
    .then(async () => {
      try {
        const res: any = await startExecution(planId)
        ElMessage.success('执行已触发')
        router.push(`/project/${route.params.id}/executions/${res.data.id}`)
      } catch { ElMessage.error('触发失败') }
    })
    .catch(() => {})
}

const canCancel = computed(() => {
  const s = liveExecution.value?.status
  return s === 'RUNNING' || s === 'PENDING' || s === 'QUEUED' || s === 'WAITING_MANUAL'
})

onMounted(loadData)
</script>

<template>
  <div>
    <!-- 页头：计划名 + 状态 + 操作按钮 -->
    <EditPageHeader :title="detailTitle">
      <el-tag v-if="liveExecution.status" :type="(statusTypeMap[liveExecution.status] || 'info') as any">
        {{ statusLabel(liveExecution.status) }}
      </el-tag>
      <el-tag v-if="connected" type="success" size="small" effect="dark">实时</el-tag>
      <el-button type="primary" @click="handleReRun">{{ isManualPlan ? '去执行' : '重新执行' }}</el-button>
      <el-button @click="refresh">刷新</el-button>
      <el-button v-if="canCancel" type="danger" @click="handleCancel">{{ isManualPlan ? '作废记录单' : '取消执行' }}</el-button>
    </EditPageHeader>

    <!-- 实时进度提示 -->
    <el-alert v-if="liveMessage && connected" :title="liveMessage" type="info" show-icon
      :closable="false" style="margin-bottom:16px" />

    <!-- 进度条（运行中显示） -->
    <el-card v-if="connected && liveExecution.status === 'RUNNING'" style="margin-bottom:16px">
      <div style="display:flex;align-items:center;gap:16px">
        <el-progress :percentage="liveProgressPercent" :stroke-width="20" text-inside striped striped-flow style="flex:1" />
        <div style="white-space:nowrap;font-size:13px;color:#606266">
          <span v-if="liveCurrentCase" style="margin-right:12px">当前: {{ liveCurrentCase }}</span>
          <span>通过率: {{ livePassRate }}%</span>
        </div>
      </div>
    </el-card>

    <!-- 元信息行 -->
    <div class="meta-row">
      <span>计划：<b>{{ execution.planName || '-' }}</b></span>
      <span>环境：<b>{{ execution.environmentName || '-' }}</b></span>
      <span>触发：<b>{{ getLabel(triggerOptions, execution.triggerType) }}</b></span>
      <span>时间：<b>{{ (execution.startedAt || execution.createdAt)?.substring(0, 19).replace('T', ' ') || '-' }}</b></span>
      <span v-if="!isManualPlan">总耗时：<b>{{ formatDuration(liveExecution.durationMs) }}</b></span>
    </div>

    <!-- 统计卡片 + 通过率环形图（手动快照记录无整单统计，隐藏） -->
    <div v-if="!hasSnapshot" class="stats-grid">
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">总用例</div>
        <div class="stat-value">{{ caseStats.total }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">通过</div>
        <div class="stat-value" style="color:#67c23a">{{ caseStats.passed }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">失败</div>
        <div class="stat-value" style="color:#f56c6c">{{ caseStats.failed }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">跳过</div>
        <div class="stat-value" style="color:#909399">{{ caseStats.skipped }}</div>
      </el-card>
      <div class="ring-container">
        <svg width="80" height="80" viewBox="0 0 80 80">
          <circle cx="40" cy="40" r="34" fill="none" stroke="#f0f0f0" stroke-width="8" />
          <circle cx="40" cy="40" r="34" fill="none" :stroke="ringColor" stroke-width="8"
            :stroke-dasharray="ringCircumference" :stroke-dashoffset="ringOffset"
            stroke-linecap="round" transform="rotate(-90 40 40)" />
          <text x="40" y="38" text-anchor="middle" font-size="16" font-weight="700" :fill="ringColor">
            {{ passRatePercent }}%
          </text>
          <text x="40" y="52" text-anchor="middle" font-size="9" fill="#999">通过率</text>
        </svg>
      </div>
    </div>

    <!-- 用例执行结果 / 多轮测试结果（手动快照记录替换为多轮结果只读表格） -->
    <el-card style="margin-bottom:16px">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>{{ hasSnapshot ? '多轮测试结果' : '用例执行结果' }}</span>
          <div v-if="!hasSnapshot" style="display:flex;gap:4px">
            <el-button :type="caseFilter === 'all' ? 'primary' : 'default'"
              :plain="caseFilter !== 'all'" size="small" @click="caseFilter = 'all'">
              全部 ({{ results.length }})
            </el-button>
            <el-button :type="caseFilter === 'pass' ? 'success' : 'default'"
              :plain="caseFilter !== 'pass'" size="small" @click="caseFilter = 'pass'">
              通过 ({{ passCount }})
            </el-button>
            <el-button :type="caseFilter === 'fail' ? 'danger' : 'default'"
              :plain="caseFilter !== 'fail'" size="small" @click="caseFilter = 'fail'">
              失败 ({{ failCount }})
            </el-button>
          </div>
        </div>
      </template>
      <el-table v-if="!hasSnapshot" :data="filteredResults" row-key="id" :border="false" style="width:100%"
        :row-class-name="({ row }: any) => (row.status === 'FAILED' || row.status === 'ERROR') ? 'fail-row' : ''">
        <el-table-column prop="caseName" label="用例名称" min-width="160" />
        <el-table-column label="用例类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.caseType === 'MANUAL' ? 'warning' : 'primary'" size="small">
              {{ row.caseType === 'MANUAL' ? '手动化' : '自动化' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="(statusTypeMap[row.status] || 'info') as any" size="small">
              {{ row.status === 'PENDING' && row.caseType === 'MANUAL' ? (isManualPlan ? '待记录' : '待处理') : (statusLabels[row.status] || row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="185">
          <template #default="{ row }">
            <template v-if="row.caseType === 'MANUAL' && row.status === 'PENDING'">
              <el-button type="success" link size="small" :loading="updatingResultId === row.id"
                @click="handleManualResult(row, 'PASSED')">通过</el-button>
              <el-button type="danger" link size="small" :loading="updatingResultId === row.id"
                @click="handleManualResult(row, 'FAILED')">失败</el-button>
              <el-button type="info" link size="small" :loading="updatingResultId === row.id"
                @click="handleManualResult(row, 'SKIPPED')">跳过</el-button>
            </template>
            <span v-else style="color:#c0c4cc">-</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!isManualPlan" label="耗时" width="80">
          <template #default="{ row }">
            {{ row.durationMs ? `${(row.durationMs / 1000).toFixed(1)}s` : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="错误摘要">
          <template #default="{ row }">
            <span v-if="row.errorSummary || row.actualResult"
              :style="{ color: (row.status === 'FAILED' || row.status === 'ERROR') ? '#f56c6c' : '#909399', fontSize: '12px' }">
              {{ row.errorSummary || row.actualResult || '-' }}
            </span>
            <span v-else style="color:#c0c4cc">-</span>
          </template>
        </el-table-column>
      </el-table>
      <!-- 手动执行记录：多轮测试结果只读表格（列=【执行完成】时快照的结果列） -->
      <el-table v-else :data="results" row-key="id" :border="false" style="width:100%">
        <el-table-column prop="caseName" label="用例名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="用例类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.caseType === 'MANUAL' ? 'warning' : 'primary'" size="small">
              {{ row.caseType === 'MANUAL' ? '手动化' : '自动化' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-for="col in snapshotColumns" :key="col.id" :min-width="150">
          <template #header>
            <span>{{ col.columnName }}</span>
          </template>
          <template #default="{ row }">
            <template v-if="cellOf(row, col.id)">
              <el-tag v-if="cellOf(row, col.id).status"
                :type="(cellStatusTypeMap[cellOf(row, col.id).status] || 'info') as any" size="small">
                {{ cellStatusLabels[cellOf(row, col.id).status] || cellOf(row, col.id).status }}
              </el-tag>
              <el-tooltip v-if="cellOf(row, col.id).remark" placement="top" :show-after="200">
                <template #content>
                  <div style="max-width:300px;word-break:break-all">{{ cellOf(row, col.id).remark }}</div>
                </template>
                <el-icon class="cell-remark-icon"><Document /></el-icon>
              </el-tooltip>
            </template>
            <span v-else style="color:#c0c4cc">-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 执行日志（内联卡片，手动记录单无日志不展示） -->
    <el-card v-if="!isManualPlan">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>执行日志</span>
          <el-select v-model="logLevel" style="width:100px" size="small">
            <el-option value="all" label="全部" />
            <el-option value="INFO" label="INFO" />
            <el-option value="WARN" label="WARN" />
            <el-option value="ERROR" label="ERROR" />
          </el-select>
        </div>
      </template>
      <div v-if="filteredLogs.length > 0" class="log-container">
        <div v-for="(log, idx) in filteredLogs" :key="idx" class="log-line">
          <span :class="['log-level', `log-${(log.level || log.phase || 'info').toLowerCase()}`]">
            [{{ log.timestamp || log.time || '' }}] {{ (log.level || log.phase || 'INFO').toUpperCase() }}
          </span>
          <span>{{ log.message || log.stepName || '' }}</span>
          <span v-if="log.assertionSummary" class="log-assertion">{{ log.assertionSummary }}</span>
        </div>
      </div>
      <el-empty v-else description="暂无日志" :image-size="60" />
    </el-card>
  </div>
</template>

<style scoped>
.meta-row {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: #909399;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.meta-row b {
  color: #606266;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr) auto;
  gap: 12px;
  margin-bottom: 16px;
}
.stat-card {
  text-align: center;
}
.stat-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}
.ring-container {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 16px;
  background: #fff;
  border-radius: 4px;
  border: 1px solid #ebeef5;
}

:deep(.fail-row) {
  background-color: #fef0f0 !important;
}

.cell-remark-icon {
  color: #909399;
  cursor: pointer;
  vertical-align: middle;
  margin-left: 4px;
}

.log-container {
  background: #fafafa;
  border-radius: 4px;
  padding: 12px;
  font-size: 12px;
  line-height: 1.8;
  font-family: 'Courier New', Consolas, monospace;
  max-height: 400px;
  overflow-y: auto;
}
.log-line {
  display: block;
  white-space: pre-wrap;
  word-break: break-all;
}
.log-level {
  margin-right: 6px;
  font-weight: 500;
}
.log-info { color: #909399; }
.log-warn { color: #e6a23c; }
.log-error { color: #f56c6c; font-weight: 600; }
.log-assertion {
  color: #409eff;
  margin-left: 4px;
}
</style>
