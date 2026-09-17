<!--
 @author HXN
 @date 2026-09-15
 @description AI 白盒测试视图（参考 白盒.md PRD）
-->
<script setup lang="ts">
/**
 * AI 白盒测试
 * 以【源代码】+【需求文档】为输入的增量白盒测试页面
 * 流水线：同步代码 → 变更识别 → 静态分析 → 需求对齐 → 生成测试 → 编译验证 → 执行测试 → 质量评估 → 报告生成
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader/index.vue'
import { usePermission } from '@/composables/usePermission'
import { getRepositories } from '@/api/repository'
import { getRequirementVersions } from '@/api/requirement'
import {
  startWhiteboxTask as apiStartTask,
  stopWhiteboxTask as apiStopTask,
  getWhiteboxTasks,
  getWhiteboxTask,
  getWhiteboxMethods,
  getWhiteboxTests,
  saveWhiteboxTests,
  downloadWhiteboxReport,
} from '@/api/aiWhitebox'

const route = useRoute()
const projectId = computed(() => Number(route.params.id))
const { hasPermission, isButtonDisabled } = usePermission()

// ===== 轮询控制 =====
let pollTimer: ReturnType<typeof setInterval> | null = null
const currentTaskId = ref<number | null>(null)
const running = ref(false)

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})

// ===== 任务配置 =====
const taskConfig = reactive({
  repositoryId: null as number | null,
  requirementVersionId: null as number | null,
  baselineCommit: '',
})

/** 仓库下拉选项 */
const repoOptions = ref<any[]>([])
/** 需求版本下拉选项 */
const versionOptions = ref<any[]>([])
/** 首次测试标记（无已完成任务时提示必填基准 commit） */
const hasCompletedTask = ref(false)

onMounted(async () => {
  await Promise.all([loadRepositories(), loadVersions(), loadTaskHistory()])
})

async function loadRepositories() {
  try {
    const res: any = await getRepositories(projectId.value)
    repoOptions.value = (res.data || res || []).map((r: any) => ({
      value: r.id,
      label: r.name,
      lastCommitId: r.lastCommitId,
    }))
  } catch {
    // 静默
  }
}

async function loadVersions() {
  try {
    const res: any = await getRequirementVersions(projectId.value)
    versionOptions.value = (res.data || res || []).map((v: any) => ({
      value: v.id,
      label: v.versionName,
    }))
  } catch {
    // 静默
  }
}

// ===== 9 阶段定义 =====
const phases = [
  { key: 'sync', label: '同步代码' },
  { key: 'diff', label: '变更识别' },
  { key: 'analyze', label: '静态分析' },
  { key: 'align', label: '需求对齐' },
  { key: 'generate', label: '生成测试' },
  { key: 'build', label: '编译验证' },
  { key: 'test', label: '执行测试' },
  { key: 'quality', label: '质量评估' },
  { key: 'report', label: '报告生成' },
]

/** 当前阶段索引 */
const activePhaseIndex = computed(() => {
  const idx = phases.findIndex(p => p.key === currentPhase.value)
  return idx >= 0 ? idx : 0
})

// ===== 任务状态 =====
const currentPhase = ref('')
const taskProgress = ref(0)
const taskStatus = ref('')
const taskLog = ref<string[]>([])
const logContainer = ref<HTMLElement | null>(null)

// ===== 统计 =====
const stats = reactive({
  totalChangedFiles: 0,
  totalChangedMethods: 0,
  totalCases: 0,
  totalTestClasses: 0,
  compilePass: 0,
  execPass: 0,
  execFail: 0,
  branchCoverage: null as number | null,
  lineCoverage: null as number | null,
  mutationScore: null as number | null,
  totalMutants: 0,
  killedMutants: 0,
  tokensUsed: 0,
})

// ===== 结果数据 =====
const methods = ref<any[]>([])
const tests = ref<any[]>([])
const selectedTestIds = ref<number[]>([])

// ===== 历史任务 =====
const taskHistory = ref<any[]>([])

// ===== 用例详情弹窗 =====
const detailVisible = ref(false)
const currentTest = ref<any>(null)

// ===== 任务控制 =====
async function startTask() {
  if (!hasPermission('project:ai-whitebox:run')) {
    ElMessage.warning('无启动测试权限')
    return
  }
  if (!taskConfig.repositoryId) {
    ElMessage.warning('请选择源代码仓库')
    return
  }
  if (!taskConfig.baselineCommit.trim() && !hasCompletedTask) {
    ElMessage.warning('首次测试必须填写基准 commit（上次发布/基线的 commit ID）')
    return
  }

  try {
    const payload: any = { repositoryId: taskConfig.repositoryId }
    if (taskConfig.requirementVersionId) {
      payload.requirementVersionId = taskConfig.requirementVersionId
    }
    if (taskConfig.baselineCommit.trim()) {
      payload.baselineCommit = taskConfig.baselineCommit.trim()
    }

    const res: any = await apiStartTask(projectId.value, payload)
    const task = res.data || res
    currentTaskId.value = task.id
    running.value = true
    taskStatus.value = task.status || 'PENDING'
    taskProgress.value = task.progress || 0
    currentPhase.value = task.currentPhase || 'sync'
    taskLog.value = task.taskLog ? task.taskLog.split('\n') : ['[系统] 白盒测试任务已创建']
    methods.value = []
    tests.value = []
    selectedTestIds.value = []
    resetStats()

    startPolling(task.id)
    ElMessage.success('白盒测试任务已启动')
  } catch (e: any) {
    const msg = e?.response?.data?.message || '启动测试失败'
    ElMessage.error(msg)
  }
}

async function stopTask() {
  if (!currentTaskId.value) return
  try {
    await apiStopTask(projectId.value, currentTaskId.value)
    ElMessage.info('已发送终止指令')
  } catch (e: any) {
    const msg = e?.response?.data?.message || '终止任务失败'
    ElMessage.error(msg)
  }
}

function resetStats() {
  stats.totalChangedFiles = 0
  stats.totalChangedMethods = 0
  stats.totalCases = 0
  stats.totalTestClasses = 0
  stats.compilePass = 0
  stats.execPass = 0
  stats.execFail = 0
  stats.branchCoverage = null
  stats.lineCoverage = null
  stats.mutationScore = null
  stats.totalMutants = 0
  stats.killedMutants = 0
  stats.tokensUsed = 0
}

/** 轮询任务状态（3 秒间隔，终态停止） */
function startPolling(taskId: number) {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    try {
      const res: any = await getWhiteboxTask(projectId.value, taskId)
      const task = res.data || res
      taskStatus.value = task.status || ''
      taskProgress.value = task.progress || 0
      currentPhase.value = task.currentPhase || ''
      if (task.taskLog) {
        taskLog.value = task.taskLog.split('\n')
        scrollLogToBottom()
      }
      // 统计字段实时刷新
      stats.totalChangedFiles = task.totalChangedFiles || 0
      stats.totalChangedMethods = task.totalChangedMethods || 0
      stats.totalCases = task.totalCases || 0
      stats.totalTestClasses = task.totalTestClasses || 0
      stats.compilePass = task.compilePass || 0
      stats.execPass = task.execPass || 0
      stats.execFail = task.execFail || 0
      stats.branchCoverage = task.branchCoverage ?? null
      stats.lineCoverage = task.lineCoverage ?? null
      stats.mutationScore = task.mutationScore ?? null
      stats.totalMutants = task.totalMutants || 0
      stats.killedMutants = task.killedMutants || 0
      stats.tokensUsed = task.tokensUsed || 0

      // 终态处理
      if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status)) {
        running.value = false
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
        if (task.status === 'COMPLETED') {
          ElMessage.success('白盒测试完成')
          await loadTaskDetail(taskId)
        } else if (task.status === 'FAILED') {
          ElMessage.error('任务失败: ' + (task.taskLog?.split('\n').pop() || '未知错误'))
          await loadTaskDetail(taskId)
        } else {
          ElMessage.info('任务已取消')
        }
        await loadTaskHistory()
      }
    } catch {
      // 轮询异常静默处理
    }
  }, 3000)
}

/** 日志自动滚底 */
async function scrollLogToBottom() {
  await nextTick()
  if (logContainer.value) {
    logContainer.value.scrollTop = logContainer.value.scrollHeight
  }
}

// ===== 数据加载 =====
async function loadTaskHistory() {
  try {
    const res: any = await getWhiteboxTasks(projectId.value)
    taskHistory.value = res.data || res || []
    hasCompletedTask.value = taskHistory.value.some((t: any) => t.status === 'COMPLETED')
  } catch {
    // 静默
  }
}

/** 加载任务详情（方法+用例） */
async function loadTaskDetail(taskId: number) {
  try {
    const [taskRes, methodsRes, testsRes]: any[] = await Promise.all([
      getWhiteboxTask(projectId.value, taskId),
      getWhiteboxMethods(projectId.value, taskId),
      getWhiteboxTests(projectId.value, taskId),
    ])
    const task = taskRes.data || taskRes
    currentTaskId.value = task.id
    taskStatus.value = task.status
    taskProgress.value = task.progress || 0
    currentPhase.value = task.currentPhase || ''
    taskLog.value = task.taskLog ? task.taskLog.split('\n') : []
    stats.totalChangedFiles = task.totalChangedFiles || 0
    stats.totalChangedMethods = task.totalChangedMethods || 0
    stats.totalCases = task.totalCases || 0
    stats.totalTestClasses = task.totalTestClasses || 0
    stats.compilePass = task.compilePass || 0
    stats.execPass = task.execPass || 0
    stats.execFail = task.execFail || 0
    stats.branchCoverage = task.branchCoverage ?? null
    stats.lineCoverage = task.lineCoverage ?? null
    stats.mutationScore = task.mutationScore ?? null
    stats.totalMutants = task.totalMutants || 0
    stats.killedMutants = task.killedMutants || 0
    stats.tokensUsed = task.tokensUsed || 0
    methods.value = methodsRes.data || methodsRes || []
    tests.value = testsRes.data || testsRes || []
    scrollLogToBottom()
  } catch {
    // 静默
  }
}

/** 历史任务行点击 → 回填详情 */
async function handleHistoryRowClick(row: any) {
  if (running.value) {
    ElMessage.warning('当前有任务进行中，请先等待或终止')
    return
  }
  await loadTaskDetail(row.id)
}

/** 已保存用例禁选 */
function isTestSelectable(row: any) {
  return row.savedToManual !== 1
}

/** 用例详情弹窗 */
function showTestDetail(row: any) {
  currentTest.value = row
  detailVisible.value = true
}

/** 复制测试代码 */
async function copyTestCode() {
  if (!currentTest.value?.testCode) return
  try {
    await navigator.clipboard.writeText(currentTest.value.testCode)
    ElMessage.success('测试代码已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

/** 保存选中用例到手动用例库 */
async function handleSaveTests() {
  if (!currentTaskId.value) return
  if (selectedTestIds.value.length === 0) {
    ElMessage.warning('请先勾选要保存的用例')
    return
  }
  try {
    await saveWhiteboxTests(projectId.value, currentTaskId.value, selectedTestIds.value)
    ElMessage.success(`已保存 ${selectedTestIds.value.length} 个用例到手动用例库`)
    selectedTestIds.value = []
    await loadTaskDetail(currentTaskId.value)
  } catch (e: any) {
    const msg = e?.response?.data?.message || '保存用例失败'
    ElMessage.error(msg)
  }
}

/** 下载报告 */
async function handleDownload(format: string) {
  if (!currentTaskId.value) return
  try {
    const res = await downloadWhiteboxReport(projectId.value, currentTaskId.value, format)
    const blob = new Blob([res as any], { type: format === 'json' ? 'application/json' : 'text/markdown' })
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `whitebox-report-${currentTaskId.value}.${format === 'markdown' ? 'md' : 'json'}`
    a.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('报告下载成功')
  } catch {
    ElMessage.error('报告下载失败')
  }
}

// ===== 状态标签映射 =====
const statusTagMap: Record<string, { type: string; label: string }> = {
  PENDING: { type: 'info', label: '等待中' },
  RUNNING: { type: 'warning', label: '运行中' },
  COMPLETED: { type: 'success', label: '已完成' },
  FAILED: { type: 'danger', label: '失败' },
  CANCELLED: { type: 'info', label: '已取消' },
  PASSED: { type: 'success', label: '通过' },
  SKIPPED: { type: 'info', label: '跳过' },
}

function statusTag(value: string) {
  return statusTagMap[value] || { type: 'info', label: value || '-' }
}

const changeTypeMap: Record<string, { type: string; label: string }> = {
  MODIFIED: { type: 'warning', label: '修改' },
  ADDED: { type: 'success', label: '新增' },
  INDIRECT: { type: 'info', label: '疑似影响' },
}

function changeTypeTag(value: string) {
  return changeTypeMap[value] || { type: 'info', label: value || '-' }
}

const priorityTypeMap: Record<string, string> = {
  高: 'danger',
  中: 'warning',
  低: 'info',
}

/** 关联需求数量 */
function requirementCount(row: any): number {
  if (!row.relatedRequirementsJson) return 0
  try {
    const arr = JSON.parse(row.relatedRequirementsJson)
    return Array.isArray(arr) ? arr.length : 0
  } catch {
    return 0
  }
}

/** 覆盖率格式化 */
function fmtCoverage(value: number | null): string {
  return value !== null && value !== undefined ? Number(value).toFixed(2) + '%' : '-'
}
</script>

<template>
  <div>
    <PageHeader title="AI 白盒测试">
      <el-button
        v-if="hasPermission('project:ai-whitebox:run')"
        type="primary"
        :disabled="running || isButtonDisabled('project:ai-whitebox:run')"
        :loading="running"
        @click="startTask"
      >
        {{ running ? '测试中...' : '启动测试' }}
      </el-button>
      <el-button v-if="running" type="danger" plain @click="stopTask">终止任务</el-button>
    </PageHeader>

    <!-- ===== 任务配置 ===== -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">任务配置</span>
          <span class="card-desc">选择源代码仓库与需求版本，基于代码迭代增量生成测试</span>
        </div>
      </template>

      <el-form label-position="top" size="default">
        <div class="form-row">
          <el-form-item label="源代码仓库" required>
            <el-select
              v-model="taskConfig.repositoryId"
              placeholder="选择【源代码】中登记的仓库"
              style="width: 100%"
              filterable
            >
              <el-option
                v-for="repo in repoOptions"
                :key="repo.value"
                :value="repo.value"
                :label="repo.label"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="需求版本（可选）">
            <el-select
              v-model="taskConfig.requirementVersionId"
              placeholder="选择需求版本以对齐测试语义（可不选）"
              style="width: 100%"
              clearable
              filterable
            >
              <el-option
                v-for="version in versionOptions"
                :key="version.value"
                :value="version.value"
                :label="version.label"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="基准 commit">
            <el-input
              v-model="taskConfig.baselineCommit"
              :placeholder="hasCompletedTask ? '留空自动取上次测试 HEAD，也可手动指定' : '首次测试必填：上次发布/基线的 commit ID'"
              clearable
            />
          </el-form-item>
        </div>
      </el-form>
    </el-card>

    <!-- ===== 任务进度 ===== -->
    <el-card v-if="currentTaskId && (running || taskLog.length > 0)" shadow="never" class="section-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">任务进度</span>
          <el-tag v-if="running" type="warning" size="small">进行中</el-tag>
          <el-tag v-else :type="statusTag(taskStatus).type" size="small">{{ statusTag(taskStatus).label }}</el-tag>
        </div>
      </template>

      <el-progress
        :percentage="taskProgress"
        :status="running ? '' : (taskProgress >= 100 ? 'success' : 'exception')"
        :stroke-width="12"
        style="margin-bottom: 20px"
      />

      <!-- 9 阶段步骤条 -->
      <el-steps :active="activePhaseIndex" finish-status="success" align-center style="margin-bottom: 20px">
        <el-step v-for="phase in phases" :key="phase.key" :title="phase.label" />
      </el-steps>

      <!-- 终端样式日志 -->
      <div ref="logContainer" class="task-log">
        <div v-for="(log, i) in taskLog" :key="i" class="log-line">{{ log }}</div>
      </div>
    </el-card>

    <!-- ===== 结果统计 ===== -->
    <el-card v-if="currentTaskId && !running && taskStatus === 'COMPLETED'" shadow="never" class="section-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">结果统计</span>
        </div>
      </template>

      <div class="stats-grid">
        <div class="stat-box">
          <div class="stat-value">{{ stats.totalChangedFiles }}</div>
          <div class="stat-label">变更文件</div>
        </div>
        <div class="stat-box">
          <div class="stat-value">{{ stats.totalChangedMethods }}</div>
          <div class="stat-label">变更方法</div>
        </div>
        <div class="stat-box">
          <div class="stat-value">{{ stats.totalCases }}</div>
          <div class="stat-label">生成用例</div>
        </div>
        <div class="stat-box">
          <div class="stat-value">{{ stats.totalTestClasses }}</div>
          <div class="stat-label">测试类</div>
        </div>
        <div class="stat-box">
          <div class="stat-value">{{ stats.compilePass }}</div>
          <div class="stat-label">编译通过</div>
        </div>
        <div class="stat-box stat-success">
          <div class="stat-value">{{ stats.execPass }}</div>
          <div class="stat-label">执行通过</div>
        </div>
        <div class="stat-box stat-danger">
          <div class="stat-value">{{ stats.execFail }}</div>
          <div class="stat-label">执行失败</div>
        </div>
        <div class="stat-box">
          <div class="stat-value">{{ fmtCoverage(stats.branchCoverage) }}</div>
          <div class="stat-label">分支覆盖率</div>
        </div>
        <div class="stat-box">
          <div class="stat-value">{{ fmtCoverage(stats.lineCoverage) }}</div>
          <div class="stat-label">行覆盖率</div>
        </div>
        <div class="stat-box">
          <div class="stat-value">{{ fmtCoverage(stats.mutationScore) }}</div>
          <div class="stat-label">变异得分</div>
        </div>
        <div class="stat-box">
          <div class="stat-value">{{ stats.killedMutants }}/{{ stats.totalMutants }}</div>
          <div class="stat-label">杀死变异体</div>
        </div>
        <div class="stat-box">
          <div class="stat-value">{{ stats.tokensUsed }}</div>
          <div class="stat-label">Token 消耗</div>
        </div>
      </div>
    </el-card>

    <!-- ===== 变更方法 ===== -->
    <el-card v-if="methods.length > 0" shadow="never" class="section-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">变更方法（{{ methods.length }}）</span>
          <span class="card-desc">INDIRECT 为正则扫描的疑似受影响调用者</span>
        </div>
      </template>

      <el-table :data="methods" size="small" border>
        <el-table-column prop="className" label="类名" min-width="220" show-overflow-tooltip />
        <el-table-column prop="methodName" label="方法" min-width="160" show-overflow-tooltip />
        <el-table-column label="变更类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="changeTypeTag(row.changeType).type as any" size="small">
              {{ changeTypeTag(row.changeType).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="行号" width="100" align="center">
          <template #default="{ row }">{{ row.startLine }}-{{ row.endLine }}</template>
        </el-table-column>
        <el-table-column label="关联需求" width="90" align="center">
          <template #default="{ row }">{{ requirementCount(row) }}</template>
        </el-table-column>
        <el-table-column prop="filePath" label="文件路径" min-width="240" show-overflow-tooltip />
      </el-table>
    </el-card>

    <!-- ===== 生成用例 ===== -->
    <el-card v-if="tests.length > 0" shadow="never" class="section-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">生成用例（{{ tests.length }}）</span>
          <el-button
            v-if="hasPermission('project:ai-whitebox:save')"
            type="primary"
            size="small"
            :disabled="selectedTestIds.length === 0"
            @click="handleSaveTests"
          >
            保存选中到手动用例库（{{ selectedTestIds.length }}）
          </el-button>
        </div>
      </template>

      <el-table
        :data="tests"
        size="small"
        border
        @selection-change="(rows: any[]) => (selectedTestIds = rows.map(r => r.id))"
      >
        <el-table-column type="selection" width="45" :selectable="isTestSelectable" />
        <el-table-column prop="caseTitle" label="用例标题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="testMethodName" label="测试方法" min-width="160" show-overflow-tooltip />
        <el-table-column label="类型" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.caseType === 'EXCEPTION' ? 'danger' : 'success'" size="small">
              {{ row.caseType === 'EXCEPTION' ? '异常' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="(priorityTypeMap[row.priority] || 'info') as any" size="small">{{ row.priority }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="编译" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.compileStatus).type as any" size="small">
              {{ statusTag(row.compileStatus).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="执行" width="80" align="center">
          <template #default="{ row }">
            <el-tooltip :disabled="!row.execMessage" :content="row.execMessage" placement="top" :show-after="300">
              <el-tag :type="statusTag(row.execStatus).type as any" size="small">
                {{ statusTag(row.execStatus).label }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="已保存" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.savedToManual === 1" type="success" size="small">已保存</el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="showTestDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ===== 报告下载 ===== -->
    <el-card v-if="currentTaskId && taskStatus === 'COMPLETED'" shadow="never" class="section-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">测试报告</span>
        </div>
      </template>
      <div class="export-bar">
        <span class="export-label">下载报告：</span>
        <el-button size="small" @click="handleDownload('markdown')">Markdown</el-button>
        <el-button size="small" @click="handleDownload('json')">JSON</el-button>
      </div>
    </el-card>

    <!-- ===== 历史任务 ===== -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">历史任务</span>
          <span class="card-desc">点击行查看任务详情</span>
        </div>
      </template>

      <el-table
        :data="taskHistory"
        size="small"
        border
        highlight-current-row
        @row-click="handleHistoryRowClick"
        style="cursor: pointer"
      >
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="repositoryName" label="仓库" min-width="140" show-overflow-tooltip />
        <el-table-column prop="requirementVersionName" label="需求版本" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.requirementVersionName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="baselineCommit" label="基准 commit" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ (row.baselineCommit || '').slice(0, 8) }}</template>
        </el-table-column>
        <el-table-column prop="headCommit" label="HEAD commit" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ (row.headCommit || '').slice(0, 8) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status).type as any" size="small">{{ statusTag(row.status).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用例" width="70" align="center">
          <template #default="{ row }">{{ row.totalCases || 0 }}</template>
        </el-table-column>
        <el-table-column label="通过/失败" width="100" align="center">
          <template #default="{ row }">{{ row.execPass || 0 }}/{{ row.execFail || 0 }}</template>
        </el-table-column>
        <el-table-column label="分支覆盖率" width="100" align="center">
          <template #default="{ row }">{{ fmtCoverage(row.branchCoverage) }}</template>
        </el-table-column>
        <el-table-column label="变异得分" width="100" align="center">
          <template #default="{ row }">{{ fmtCoverage(row.mutationScore) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" />
      </el-table>
    </el-card>

    <!-- ===== 用例详情弹窗 ===== -->
    <el-dialog v-model="detailVisible" :title="currentTest?.caseTitle || '用例详情'" width="760px" top="6vh">
      <template v-if="currentTest">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="测试类">{{ currentTest.testClassName }}</el-descriptions-item>
          <el-descriptions-item label="测试方法">{{ currentTest.testMethodName }}</el-descriptions-item>
          <el-descriptions-item label="类型">
            {{ currentTest.caseType === 'EXCEPTION' ? '异常' : '正常' }}
          </el-descriptions-item>
          <el-descriptions-item label="优先级">{{ currentTest.priority }}</el-descriptions-item>
          <el-descriptions-item label="编译状态">
            {{ statusTag(currentTest.compileStatus).label }}
          </el-descriptions-item>
          <el-descriptions-item label="执行状态">
            {{ statusTag(currentTest.execStatus).label }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="currentTest.preconditions" class="detail-section">
          <h4>前置条件</h4>
          <p>{{ currentTest.preconditions }}</p>
        </div>
        <div v-if="currentTest.operationSteps" class="detail-section">
          <h4>操作步骤</h4>
          <pre class="steps-block">{{ currentTest.operationSteps }}</pre>
        </div>
        <div v-if="currentTest.expectedResult" class="detail-section">
          <h4>预期结果</h4>
          <p>{{ currentTest.expectedResult }}</p>
        </div>
        <div v-if="currentTest.execMessage" class="detail-section">
          <h4>执行信息</h4>
          <pre class="code-block">{{ currentTest.execMessage }}</pre>
        </div>
        <div v-if="currentTest.testCode" class="detail-section">
          <div class="code-header">
            <h4>JUnit 测试代码</h4>
            <el-button type="primary" link size="small" @click="copyTestCode">复制代码</el-button>
          </div>
          <pre class="code-block">{{ currentTest.testCode }}</pre>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.section-card { margin-bottom: 16px; }
.card-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.card-title { font-weight: 600; font-size: 15px; color: #303133; }
.card-desc { font-size: 12px; color: #909399; margin-left: 12px; }

.form-row { display: flex; gap: 16px; }
.form-row .el-form-item { flex: 1; }

/* 终端样式日志 */
.task-log {
  background: #1e1e1e; border-radius: 6px; padding: 12px 16px;
  max-height: 260px; overflow-y: auto; font-family: 'Consolas', monospace; font-size: 12px;
}
.log-line { color: #d4d4d4; line-height: 1.8; white-space: pre-wrap; word-break: break-all; }

/* 统计宫格 */
.stats-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.stat-box {
  background: #f5f7fa; border-radius: 6px; padding: 14px 8px; text-align: center;
}
.stat-value { font-size: 20px; font-weight: 700; color: #303133; }
.stat-success .stat-value { color: #67c23a; }
.stat-danger .stat-value { color: #f56c6c; }
.stat-label { font-size: 12px; color: #909399; margin-top: 4px; }

/* 用例详情 */
.detail-section { margin-top: 14px; }
.detail-section h4 { font-size: 13px; color: #606266; margin-bottom: 6px; }
.detail-section p { font-size: 13px; color: #303133; line-height: 1.6; }
.steps-block, .code-block {
  background: #f5f7fa; border: 1px solid #ebeef5; border-radius: 4px;
  padding: 12px; font-family: 'Consolas', monospace; font-size: 12px;
  overflow-x: auto; white-space: pre-wrap; word-break: break-all; color: #303133;
  max-height: 320px; overflow-y: auto;
}
.code-header { display: flex; align-items: center; justify-content: space-between; }

/* 导出栏 */
.export-bar { display: flex; align-items: center; gap: 8px; }
.export-label { font-size: 13px; color: #606266; }

.text-muted { color: #c0c4cc; }
</style>
