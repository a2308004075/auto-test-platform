<!--
 @author HXN
 @date 2026-08-30
 @description 用例-缺陷关联面板（用例视角：查看/添加/解除关联的缺陷）
-->
<script setup lang="ts">
/**
 * 用例-缺陷关联面板
 * 反查当前用例被哪些缺陷关联，支持选择缺陷批量添加、解除关联
 */
import { ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDefectRelationsByTarget } from '@/api/relation'
import { addDefectRelation, deleteDefectRelation } from '@/api/defect'
import DefectSelectDialog from '@/components/DefectSelectDialog/index.vue'
import { useDict } from '@/composables/useDict'
import { useDefectStatusOptions } from '@/composables/useDefectStatus'

const props = defineProps<{
  projectId: number
  /** 目标类型：MANUAL_CASE-手动化用例，AUTO_CASE-自动化用例 */
  targetType: string
  targetId: number | null | undefined
}>()

const { options: relationTypeOptions } = useDict('defect_relation_type')
const relationTypeLabelMap = computed(() => {
  const map: Record<string, string> = {}
  relationTypeOptions.value.forEach((o) => {
    map[o.value] = o.label
  })
  return map
})

// 状态选项优先读【页面配置-缺陷字段】的"状态"字段配置（按项目），无配置回退字典
const { options: statusOptions } = useDefectStatusOptions(() => props.projectId)
const statusLabelMap = computed(() => {
  const map: Record<string, string> = {}
  statusOptions.value.forEach((o) => {
    map[o.value] = o.label
  })
  return map
})

const loading = ref(false)
const list = ref<any[]>([])
const selectVisible = ref(false)
const submitting = ref(false)

async function fetchList() {
  if (!props.targetId) {
    list.value = []
    return
  }
  loading.value = true
  try {
    const res: any = await getDefectRelationsByTarget(props.projectId, props.targetType, props.targetId)
    list.value = res.data || []
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

async function handleConfirm(rows: Array<{ id: number }>) {
  if (!props.targetId || rows.length === 0) return
  submitting.value = true
  let failed = 0
  try {
    for (const row of rows) {
      try {
        await addDefectRelation(props.projectId, row.id, {
          relationType: 'RELATED',
          targetType: props.targetType,
          targetId: props.targetId,
        })
      } catch {
        failed++
      }
    }
    if (failed > 0) {
      ElMessage.warning(`${rows.length - failed} 条关联添加成功，${failed} 条失败（可能已存在关联）`)
    } else {
      ElMessage.success(`已添加 ${rows.length} 条关联`)
    }
    await fetchList()
  } finally {
    submitting.value = false
  }
}

function handleRemove(row: any) {
  ElMessageBox.confirm(`确定解除与缺陷「${row.defectTitle}」的关联？`, '解除关联', {
    type: 'warning',
    confirmButtonText: '解除',
    cancelButtonText: '取消',
  })
    .then(async () => {
      try {
        await deleteDefectRelation(props.projectId, row.defectId, row.id)
        ElMessage.success('已解除关联')
        await fetchList()
      } catch (e: any) {
        ElMessage.error(e?.response?.data?.message || '解除关联失败')
      }
    })
    .catch(() => {})
}

watch(() => props.targetId, fetchList, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="relation-panel">
    <div class="panel-toolbar">
      <el-button type="primary" size="small" :loading="submitting" @click="selectVisible = true">
        添加关联
      </el-button>
    </div>

    <el-table :data="list" border stripe size="small">
      <el-table-column prop="defectNo" label="缺陷编号" width="140" show-overflow-tooltip />
      <el-table-column prop="defectTitle" label="缺陷标题" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small">{{ statusLabelMap[row.defectStatus] || row.defectStatus }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="关联类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.relationType === 'RELATED' ? 'info' : 'warning'" size="small">
            {{ relationTypeLabelMap[row.relationType] || row.relationType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button type="danger" link size="small" @click="handleRemove(row)">解除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && list.length === 0" description="暂无关联缺陷" :image-size="60" />

    <DefectSelectDialog v-model:visible="selectVisible" :project-id="projectId" multiple @confirm="handleConfirm" />
  </div>
</template>

<style scoped>
.relation-panel {
  padding: 4px 0;
}
.panel-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}
</style>
