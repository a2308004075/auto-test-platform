<!--
 @author HXN
 @date 2026-09-24
 @description 自动化套件选择弹窗（搜索 + 分页 + 多选）
-->
<script setup lang="ts">
/**
 * 自动化套件选择弹窗
 * 支持关键字搜索、分页浏览、多选勾选
 * 确认后 emit 选中行数组（含 id/name/caseCount）
 */
import { ref, reactive, computed, watch } from 'vue'
import { getAutoSuites } from '@/api/autoSuite'
import ProPagination from '@/components/ProPagination/index.vue'

const props = defineProps<{
  visible: boolean
  projectId: number
  /** 已关联的套件 ID 列表（弹窗内默认不可重复勾选） */
  excludeIds?: number[]
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'confirm', rows: Array<{ id: number; name: string; caseCount: number }>): void
}>()

const dialogVisible = computed({
  get: () => props.visible,
  set: (value) => emit('update:visible', value),
})

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

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getAutoSuites(props.projectId, {
      keyword: keyword.value || undefined,
      page: pagination.current,
      pageSize: pagination.pageSize,
    })
    list.value = (res.data?.items || []).map((item: any) => ({
      id: item.id,
      name: item.name,
      caseCount: item.caseCount || 0,
      priority: item.priority,
    }))
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

function selectable(row: any) {
  // 已关联的套件不可再勾选
  return !props.excludeIds?.includes(row.id)
}

function handleConfirm() {
  if (selectedRows.value.length === 0) return
  emit(
    'confirm',
    selectedRows.value.map((row) => ({ id: row.id, name: row.name, caseCount: row.caseCount })),
  )
  dialogVisible.value = false
}
</script>

<template>
  <el-dialog v-model="dialogVisible" title="选择自动化套件" width="640px" destroy-on-close>
    <div style="display: flex; gap: 8px; margin-bottom: 12px">
      <el-input
        v-model="keyword"
        placeholder="搜索套件名称"
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
    >
      <el-table-column type="selection" width="45" :selectable="selectable" />
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="套件名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="priority" label="优先级" width="80" />
      <el-table-column prop="caseCount" label="用例数" width="80" align="center" />
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
      <el-button type="primary" :disabled="selectedRows.length === 0" @click="handleConfirm">
        确定{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>
