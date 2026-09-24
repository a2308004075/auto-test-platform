<!--
 @author HXN
 @date 2026-08-30
 @description 用例选择弹窗（手动化用例 + 自动化用例，搜索 + 分页 + 勾选）
-->
<script setup lang="ts">
/**
 * 用例选择弹窗
 * 通过 Tab 切换手动化用例/自动化用例，支持关键字搜索、分页浏览、多选/单选
 * 确认后 emit 选中行数组（含 caseType 字段标识来源）
 */
import { ref, reactive, computed, watch } from 'vue'
import { getManualCases } from '@/api/manualCase'
import { getAutoCases } from '@/api/autoCase'
import ProPagination from '@/components/ProPagination/index.vue'
import { useDict } from '@/composables/useDict'

const props = defineProps<{
  visible: boolean
  projectId: number
  /** 是否多选（默认多选） */
  multiple?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'confirm', rows: Array<{ id: number; title: string; caseType: string }>): void
}>()

const { options: caseTypeOptions } = useDict('case_type')

const dialogVisible = computed({
  get: () => props.visible,
  set: (value) => emit('update:visible', value),
})

const activeTab = ref('MANUAL_CASE')
const keyword = ref('')
const loading = ref(false)
const list = ref<any[]>([])
const selectedRows = ref<any[]>([])
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      keyword.value = ''
      selectedRows.value = []
      pagination.current = 1
      fetchList()
    }
  },
)

function switchTab(tab: string) {
  activeTab.value = tab
  keyword.value = ''
  selectedRows.value = []
  pagination.current = 1
  fetchList()
}

async function fetchList() {
  loading.value = true
  try {
    if (activeTab.value === 'MANUAL_CASE') {
      const res: any = await getManualCases(props.projectId, {
        keyword: keyword.value || undefined,
        page: pagination.current,
        pageSize: pagination.pageSize,
      })
      list.value = (res.data?.items || []).map((item: any) => ({
        id: item.id,
        title: item.title,
        // 优先级为动态字段（由【页面配置-手动用例字段】驱动），值存 customFields.priority
        priority: item.customFields?.priority,
        status: item.caseStatus,
      }))
      pagination.total = res.data?.total || 0
    } else {
      const res: any = await getAutoCases(props.projectId, {
        keyword: keyword.value || undefined,
        page: pagination.current,
        pageSize: pagination.pageSize,
      })
      list.value = (res.data?.items || []).map((item: any) => ({
        id: item.id,
        title: item.name,
        priority: item.priority,
        status: item.isActive,
      }))
      pagination.total = res.data?.total || 0
    }
  } catch {
    list.value = []
    pagination.total = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.current = 1
  fetchList()
}

function handleSelectionChange(rows: any[]) {
  selectedRows.value = rows
}

function handleRowClick(row: any) {
  // 单选模式：点击行即选中并确认
  if (!props.multiple) {
    emit('confirm', [{ id: row.id, title: row.title, caseType: activeTab.value }])
    dialogVisible.value = false
  }
}

function handleConfirm() {
  if (selectedRows.value.length === 0) return
  emit(
    'confirm',
    selectedRows.value.map((row) => ({ id: row.id, title: row.title, caseType: activeTab.value })),
  )
  dialogVisible.value = false
}
</script>

<template>
  <el-dialog v-model="dialogVisible" title="选择用例" width="640px" destroy-on-close>
    <el-tabs v-model="activeTab" @tab-change="switchTab">
      <el-tab-pane
        v-for="opt in caseTypeOptions"
        :key="opt.value"
        :label="opt.label"
        :name="opt.value"
      />
    </el-tabs>

    <div style="display: flex; gap: 8px; margin-bottom: 12px">
      <el-input
        v-model="keyword"
        :placeholder="activeTab === 'MANUAL_CASE' ? '搜索用例标题' : '搜索自动化用例名称'"
        clearable
        style="width: 260px"
        @keyup.enter="handleSearch"
      />
      <el-button type="primary" @click="handleSearch">搜索</el-button>
    </div>

    <el-table
      :data="list"
      v-loading="loading"
      border
      stripe
      max-height="360"
      @selection-change="handleSelectionChange"
      @row-click="handleRowClick"
    >
      <el-table-column v-if="multiple" type="selection" width="45" />
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="priority" label="优先级" width="80" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '废弃' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <ProPagination
      v-model:current-page="pagination.current"
      v-model:page-size="pagination.pageSize"
      :total="pagination.total"
      :page-sizes="[10, 20, 50]"
      layout="total, prev, pager, next"
      @change="(p: number) => { pagination.current = p; fetchList() }"
    />

    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button v-if="multiple" type="primary" :disabled="selectedRows.length === 0" @click="handleConfirm">
        确定{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>
