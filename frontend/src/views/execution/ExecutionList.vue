<!--
 @author HXN
 @date 2026-08-18 17:31
 @description 执行记录列表视图
-->
<script setup lang="ts">
/**
 * 执行记录列表 - M9
 * 对齐原型 execution-list.html
 * ProSearchCard（含折叠行）+ 进度条 + 动态操作按钮
 */
import { ref, reactive, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getExecutions, cancelExecution, startExecution } from '@/api/execution'
import { getEnvironments } from '@/api/environment'
import { useDict, type DictOption } from '@/composables/useDict'
import { usePermission } from '@/composables/usePermission'
import ProSearchCard from '@/components/ProSearchCard/index.vue'
import ProPagination from '@/components/ProPagination/index.vue'
import PageHeader from '@/components/PageHeader/index.vue'

const route = useRoute()
const router = useRouter()
const { hasPermission } = usePermission()
const projectId = computed(() => Number(route.params.id))

const loading = ref(false)
const list = ref<any[]>([])
const pagination = reactive({ current: 1, pageSize: 20, total: 0 })

// ===== 搜索条件 =====
const search = reactive({
  planName: '',
  environmentId: '' as string | number,
  triggerType: '',
  status: '',
  startedAtFrom: '',
  startedAtTo: '',
  finishedAtFrom: '',
  finishedAtTo: '',
})

// ===== 下拉选项 =====
const environments = ref<any[]>([])
const { options: statusOptions } = useDict('execution_status')
const { options: triggerOptions } = useDict('trigger_type')

const statusTypeMap: Record<string, string> = {
  PENDING: 'info',
  QUEUED: 'info',
  RUNNING: '',
  WAITING_MANUAL: 'warning',
  COMPLETED: 'success',
  FAILED: 'danger',
  CANCELLED: 'warning',
}

function getLabel(options: DictOption[], value: string): string {
  return options.find((o) => o.value === value)?.label || value
}

async function loadEnvironments() {
  try {
    const res: any = await getEnvironments(projectId.value)
    environments.value = res.data || []
  } catch { environments.value = [] }
}

// ===== 列表查询 =====
async function fetchList() {
  loading.value = true
  try {
    const res: any = await getExecutions(projectId.value, {
      planName: search.planName || undefined,
      environmentId: search.environmentId ? Number(search.environmentId) : undefined,
      status: search.status || undefined,
      triggerType: search.triggerType || undefined,
      startedAtFrom: search.startedAtFrom || undefined,
      startedAtTo: search.startedAtTo || undefined,
      finishedAtFrom: search.finishedAtFrom || undefined,
      finishedAtTo: search.finishedAtTo || undefined,
      page: pagination.current,
      pageSize: pagination.pageSize,
    })
    list.value = res.data?.items || []
    pagination.total = res.data?.total || 0
  } catch { list.value = [] } finally { loading.value = false }
}

function handleSearch() { pagination.current = 1; fetchList() }

function handleReset() {
  Object.assign(search, {
    planName: '', environmentId: '', triggerType: '', status: '',
    startedAtFrom: '', startedAtTo: '', finishedAtFrom: '', finishedAtTo: '',
  })
  handleSearch()
}

function viewDetail(record: any) {
  router.push(`/project/${projectId.value}/executions/${record.id}`)
}

function handleCancel(record: any) {
  const manualSheet = record.status === 'WAITING_MANUAL' && record.planType === 'MANUAL'
  ElMessageBox.confirm(
    manualSheet
      ? `确定作废「${record.planName}」的测试结果记录单？已标记的结果将保留`
      : `确定取消执行「${record.planName}」？`,
    manualSheet ? '作废记录单' : '取消执行',
    { type: 'warning' },
  )
    .then(async () => {
      try {
        await cancelExecution(record.id)
        ElMessage.success(manualSheet ? '记录单已作废' : '已取消')
        fetchList()
      } catch { ElMessage.error('操作失败') }
    })
    .catch(() => {})
}

function handleReRun(record: any) {
  if (!record.planId) { ElMessage.warning('无关联计划，无法重新执行'); return }
  ElMessageBox.confirm(`确定重新执行「${record.planName}」？`, '重新执行', { type: 'info' })
    .then(async () => {
      try {
        const res: any = await startExecution(record.planId)
        ElMessage.success('执行已触发')
        router.push(`/project/${projectId.value}/executions/${res.data.id}`)
      } catch { ElMessage.error('触发失败') }
    })
    .catch(() => {})
}

onMounted(() => { loadEnvironments(); fetchList() })
</script>

<template>
  <div>
    <!-- 页头 -->
    <PageHeader title="测试记录" />

    <!-- 搜索卡片 -->
    <ProSearchCard :loading="loading" @search="handleSearch" @reset="handleReset">
      <div class="pro-search-field">
        <span class="pro-search-label">测试计划名称</span>
        <el-input v-model="search.planName" placeholder="模糊查询" clearable style="width:180px" @keyup.enter="handleSearch" />
      </div>
      <div class="pro-search-field">
        <span class="pro-search-label">环境</span>
        <el-select v-model="search.environmentId" placeholder="全部环境" clearable style="width:160px">
          <el-option v-for="env in environments" :key="env.id" :value="env.id" :label="env.name" />
        </el-select>
      </div>
      <div class="pro-search-field">
        <span class="pro-search-label">触发方式</span>
        <el-select v-model="search.triggerType" placeholder="全部触发方式" clearable style="width:160px">
          <el-option v-for="t in triggerOptions" :key="t.value" :value="t.value" :label="t.label" />
        </el-select>
      </div>
      <template #collapse>
        <div class="pro-search-field">
          <span class="pro-search-label">状态</span>
          <el-select v-model="search.status" placeholder="全部状态" clearable style="width:160px">
            <el-option v-for="s in statusOptions" :key="s.value" :value="s.value" :label="s.label" />
          </el-select>
        </div>
        <div class="pro-search-field">
          <span class="pro-search-label">开始执行时间</span>
          <el-date-picker v-model="search.startedAtFrom" type="date" placeholder="起始日期" value-format="YYYY-MM-DD" style="width:140px" />
          <span style="color:#909399">至</span>
          <el-date-picker v-model="search.startedAtTo" type="date" placeholder="结束日期" value-format="YYYY-MM-DD" style="width:140px" />
        </div>
        <div class="pro-search-field">
          <span class="pro-search-label">结束执行时间</span>
          <el-date-picker v-model="search.finishedAtFrom" type="date" placeholder="起始日期" value-format="YYYY-MM-DD" style="width:140px" />
          <span style="color:#909399">至</span>
          <el-date-picker v-model="search.finishedAtTo" type="date" placeholder="结束日期" value-format="YYYY-MM-DD" style="width:140px" />
        </div>
      </template>
    </ProSearchCard>

    <!-- 执行记录表格 -->
    <el-table v-loading="loading" :data="list" row-key="id" border style="width:100%">
      <el-table-column label="测试计划" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="viewDetail(row)">
            {{ row.planName || '-' }} #{{ row.id }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="环境" width="100">
        <template #default="{ row }">
          <span style="font-size:12px">{{ row.environmentName || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="触发方式" width="100">
        <template #default="{ row }">
          <el-tag
            :type="row.triggerType === 'MANUAL' ? '' : row.triggerType === 'SCHEDULED' ? 'success' : row.triggerType === 'CI' ? 'warning' : 'info'"
            size="small"
          >{{ getLabel(triggerOptions, row.triggerType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="执行进度" width="140">
        <template #default="{ row }">
          <div style="display:flex;align-items:center;gap:8px">
            <el-progress
              :percentage="row.progressPercent || 0"
              :stroke-width="8"
              :color="row.status === 'RUNNING' ? '#409eff' : row.status === 'FAILED' ? '#f56c6c' : '#67c23a'"
              :show-text="false"
              style="flex:1"
            />
            <span :style="{
              fontSize: '12px', whiteSpace: 'nowrap', fontWeight: 500,
              color: row.progressPercent >= 100 ? '#606266' : '#409eff'
            }">{{ row.progressPercent || 0 }}%</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="(statusTypeMap[row.status] || 'info') as any" size="small">
            {{ getLabel(statusOptions, row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="用例（通过/失败/跳过）" width="170">
        <template #default="{ row }">
          <span v-if="row.totalCases != null" style="font-size:12px">
            {{ row.totalCases }}
            (<span style="color:#67c23a">{{ row.passedCases || 0 }}</span>/<span style="color:#f56c6c">{{ row.failedCases || 0 }}</span>/{{ row.skippedCases || 0 }})
          </span>
          <span v-else style="color:#c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column label="通过率" width="80">
        <template #default="{ row }">
          <span v-if="row.passRate != null" :style="{
            fontWeight: 600, fontSize: '13px',
            color: row.passRate >= 90 ? '#67c23a' : row.passRate >= 60 ? '#e6a23c' : '#f56c6c'
          }">{{ row.passRate }}%</span>
          <span v-else style="color:#c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column label="开始执行时间" width="160">
        <template #default="{ row }">
          <span style="font-size:12px;color:#909399">{{ row.startedAt?.substring(0, 19).replace('T', ' ') || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结束执行时间" width="160">
        <template #default="{ row }">
          <span style="font-size:12px;color:#909399">{{ row.finishedAt?.substring(0, 19).replace('T', ' ') || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewDetail(row)">详情</el-button>
          <el-button v-if="(row.status === 'RUNNING' || row.status === 'PENDING' || row.status === 'QUEUED' || row.status === 'WAITING_MANUAL') && hasPermission('project:execution:cancel')"
            type="danger" link size="small" @click="handleCancel(row)">{{ row.status === 'WAITING_MANUAL' && row.planType === 'MANUAL' ? '作废记录单' : '取消执行' }}</el-button>
          <el-button v-if="row.status !== 'RUNNING' && row.status !== 'PENDING' && row.status !== 'QUEUED'"
            type="primary" link size="small" @click="handleReRun(row)">重新执行</el-button>
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
</template>
