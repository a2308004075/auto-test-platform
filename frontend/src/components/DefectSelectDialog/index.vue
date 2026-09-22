<!--
 @author HXN
 @date 2026-08-30
 @description 缺陷选择弹窗（搜索 + 分页 + 勾选）
-->
<script setup lang="ts">
/**
 * 缺陷选择弹窗
 * 支持关键字/状态搜索、分页浏览、多选/单选
 * 确认后 emit 选中行数组
 */
import { ref, reactive, computed, watch } from 'vue'
import { getDefects } from '@/api/defect'
import ProPagination from '@/components/ProPagination/index.vue'
import { useDefectStatusOptions } from '@/composables/useDefectStatus'

const props = defineProps<{
  visible: boolean
  projectId: number
  /** 是否多选（默认多选） */
  multiple?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'confirm', rows: Array<{ id: number; defectNo: string; title: string; status: string }>): void
}>()

// 状态选项优先读【字段管理-缺陷字段】的"状态"字段配置（按项目），无配置回退字典
const { options: statusOptions } = useDefectStatusOptions(() => props.projectId)

const statusLabelMap = computed(() => {
  const map: Record<string, string> = {}
  statusOptions.value.forEach((o) => {
    map[o.value] = o.label
  })
  return map
})

const dialogVisible = computed({
  get: () => props.visible,
  set: (value) => emit('update:visible', value),
})

const keyword = ref('')
const status = ref('')
const loading = ref(false)
const list = ref<any[]>([])
const selectedRows = ref<any[]>([])
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      keyword.value = ''
      status.value = ''
      selectedRows.value = []
      pagination.current = 1
      fetchList()
    }
  },
)

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getDefects(props.projectId, {
      keyword: keyword.value || undefined,
      status: status.value || undefined,
      page: pagination.current,
      pageSize: pagination.pageSize,
    })
    list.value = res.data?.items || []
    pagination.total = res.data?.total || 0
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
    emit('confirm', [{ id: row.id, defectNo: row.defectNo, title: row.title, status: row.status }])
    dialogVisible.value = false
  }
}

function handleConfirm() {
  if (selectedRows.value.length === 0) return
  emit(
    'confirm',
    selectedRows.value.map((row) => ({ id: row.id, defectNo: row.defectNo, title: row.title, status: row.status })),
  )
  dialogVisible.value = false
}
</script>

<template>
  <el-dialog v-model="dialogVisible" title="选择缺陷" width="680px" destroy-on-close>
    <div style="display: flex; gap: 8px; margin-bottom: 12px">
      <el-input
        v-model="keyword"
        placeholder="搜索缺陷编号/标题/原因描述"
        clearable
        style="width: 260px"
        @keyup.enter="handleSearch"
      />
      <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px">
        <el-option v-for="s in statusOptions" :key="s.value" :value="s.value" :label="s.label" />
      </el-select>
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
      <el-table-column prop="defectNo" label="缺陷编号" width="150" show-overflow-tooltip />
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="severity" label="严重级别" width="90" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small">{{ statusLabelMap[row.status] || row.status }}</el-tag>
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
