<!--
 @author HXN
 @date 2026-09-24
 @description 测试计划关联内容独立页（手动计划=手动化用例表格含动态字段 / 自动计划=自动化套件表格）
-->
<script setup lang="ts">
/**
 * 测试计划关联内容独立页 - M9
 * 基础信息与执行策略在计划列表页「编辑」弹窗维护，本页仅承载关联内容：
 *   手动计划：手动化用例表格（含【页面配置-测试计划】动态字段列，行内设置即时保存）
 *   自动计划：自动化套件表格
 *   添加/移除均即时保存，无需「保存」按钮
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getPlan, updatePlan, updatePlanCaseFields } from '@/api/plan'
import { getCustomFieldsForRender } from '@/api/customField'
import EditPageHeader from '@/components/EditPageHeader/index.vue'
import CaseSelectDialog from '@/components/CaseSelectDialog/index.vue'
import AutoSuiteSelectDialog from '@/components/AutoSuiteSelectDialog/index.vue'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))
const planId = computed(() => Number(route.params.planId))

const loading = ref(false)
const relationSaving = ref(false)

// 计划基础标识（仅用于页头展示与类型分支，编辑入口在计划列表页「编辑」弹窗）
const form = reactive({
  name: '',
  planType: 'AUTO' as string,
})

const isManual = computed(() => form.planType === 'MANUAL')

// 关联内容明细（由后端 manualCaseDetails/autoSuiteDetails 驱动，添加/移除即时保存）
const manualCaseDetails = ref<any[]>([])
const autoSuiteDetails = ref<any[]>([])

function applyPlanData(p: any) {
  form.name = p.name || ''
  form.planType = p.planType || 'AUTO'
  manualCaseDetails.value = p.manualCaseDetails || []
  autoSuiteDetails.value = p.autoSuiteDetails || []
}

async function fetchPlan() {
  loading.value = true
  try {
    const res: any = await getPlan(planId.value)
    applyPlanData(res.data)
  } catch {
    ElMessage.error('加载计划失败')
  } finally {
    loading.value = false
  }
}

// ===== 关联内容管理（添加/移除即时保存，无需点「保存」） =====
const caseDialogVisible = ref(false)
const suiteDialogVisible = ref(false)

const manualCaseIds = computed(() => manualCaseDetails.value.map((c: any) => c.id))
const autoSuiteIds = computed(() => autoSuiteDetails.value.map((s: any) => s.id))

async function saveRelationIds(payload: { manualCaseIds?: number[]; autoSuiteIds?: number[] }, successMsg: string) {
  relationSaving.value = true
  try {
    const res: any = await updatePlan(planId.value, payload)
    applyPlanData(res.data)
    ElMessage.success(successMsg)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  } finally {
    relationSaving.value = false
  }
}

function handleAddManualCases(rows: Array<{ id: number; title: string }>) {
  const current = manualCaseIds.value
  const merged = [...current]
  let added = 0
  for (const r of rows) {
    if (!merged.includes(r.id)) { merged.push(r.id); added++ }
  }
  if (added === 0) { ElMessage.info('所选用例均已关联'); return }
  saveRelationIds({ manualCaseIds: merged }, `已添加 ${added} 条手动化用例`)
}

function handleRemoveManualCase(row: any) {
  const next = manualCaseIds.value.filter((id) => id !== row.id)
  saveRelationIds({ manualCaseIds: next }, `已移除用例「${row.title}」`)
}

function handleAddSuites(rows: Array<{ id: number; name: string }>) {
  const current = autoSuiteIds.value
  const merged = [...current]
  let added = 0
  for (const r of rows) {
    if (!merged.includes(r.id)) { merged.push(r.id); added++ }
  }
  if (added === 0) { ElMessage.info('所选套件均已关联'); return }
  saveRelationIds({ autoSuiteIds: merged }, `已添加 ${added} 个自动化套件`)
}

function handleRemoveSuite(row: any) {
  const next = autoSuiteIds.value.filter((id) => id !== row.id)
  saveRelationIds({ autoSuiteIds: next }, `已移除套件「${row.name}」`)
}

// ===== 关联用例动态字段（【页面配置-测试计划】配置，行内设置即时保存） =====
const caseFields = ref<any[]>([])
// 行级字段保存中状态（key = relationId:fieldKey，保存期间禁用对应控件防连点）
const caseFieldSaving = reactive<Record<string, boolean>>({})

async function loadCaseFields() {
  try {
    const res: any = await getCustomFieldsForRender({
      projectId: projectId.value,
      module: 'plan_case',
      viewType: 'edit',
    })
    caseFields.value = res.data || []
  } catch {
    caseFields.value = []
  }
}

/** 行内字段显示值：已保存值优先，未设置时回退字段默认值（仅展示，用户改动才写库） */
function caseFieldValue(row: any, field: any): string {
  const saved = row.fieldValues?.[field.fieldKey]
  if (saved !== undefined && saved !== null && saved !== '') {
    return saved
  }
  return field.defaultValue || ''
}

/** 下拉类控件选项（select 静态选项 / user 用户 / environment 环境由后端统一组装） */
function caseFieldOptions(field: any): Array<{ label: string; value: string }> {
  return field.options || []
}

function isSelectLikeField(field: any): boolean {
  return ['select', 'user', 'environment'].includes(field.fieldType)
}

function isCaseFieldSaving(row: any, field: any): boolean {
  return !!caseFieldSaving[`${row.relationId}:${field.fieldKey}`]
}

/** 行内字段值变更：即时保存（仅提交变更的单个字段，对齐关联内容即时保存语义） */
async function handleCaseFieldChange(row: any, field: any, value: string | number | null) {
  if (!row.relationId) {
    ElMessage.warning('关联信息加载中，请稍后重试')
    return
  }
  const saveKey = `${row.relationId}:${field.fieldKey}`
  const val = value === null || value === undefined ? '' : String(value)
  caseFieldSaving[saveKey] = true
  try {
    await updatePlanCaseFields(planId.value, row.relationId, { [field.fieldKey]: val })
    if (!row.fieldValues) {
      row.fieldValues = {}
    }
    if (val === '') {
      delete row.fieldValues[field.fieldKey]
    } else {
      row.fieldValues[field.fieldKey] = val
    }
    ElMessage.success(`「${row.title}」${field.fieldLabel} 已更新`)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    delete caseFieldSaving[saveKey]
  }
}

function goBack() {
  router.push(`/project/${projectId.value}/plans`)
}

onMounted(() => {
  fetchPlan()
  loadCaseFields()
})
</script>

<template>
  <div v-loading="loading">
    <EditPageHeader :title="`测试计划：${form.name || '加载中...'}`">
      <el-button @click="goBack">返回</el-button>
      <el-button v-if="isManual" type="primary" @click="caseDialogVisible = true">添加用例</el-button>
      <el-button v-else type="primary" @click="suiteDialogVisible = true">添加套件</el-button>
    </EditPageHeader>

    <!-- 关联内容（添加/移除即时保存） -->
    <el-card>
      <div v-loading="relationSaving">
        <!-- 手动测试计划：手动化用例表格 -->
        <template v-if="isManual">
          <el-table :data="manualCaseDetails" row-key="id" border stripe>
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="title" label="用例标题" min-width="300" show-overflow-tooltip />
            <el-table-column label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="row.caseStatus === 1 ? 'success' : 'info'" size="small">
                  {{ row.caseStatus === 1 ? '使用' : '废弃' }}
                </el-tag>
              </template>
            </el-table-column>
            <!-- 关联用例动态字段列（【页面配置-测试计划】配置，行内设置即时保存） -->
            <el-table-column
              v-for="f in caseFields"
              :key="f.fieldKey"
              :label="f.fieldLabel"
              :width="isSelectLikeField(f) ? 130 : 180"
              align="center"
            >
              <template #default="{ row }">
                <el-select
                  v-if="isSelectLikeField(f)"
                  :model-value="caseFieldValue(row, f)"
                  :disabled="isCaseFieldSaving(row, f)"
                  style="width: 110px"
                  @change="handleCaseFieldChange(row, f, $event)"
                >
                  <el-option
                    v-for="opt in caseFieldOptions(f)"
                    :key="opt.value"
                    :value="opt.value"
                    :label="opt.label"
                  />
                </el-select>
                <el-date-picker
                  v-else-if="f.fieldType === 'datetime'"
                  :model-value="caseFieldValue(row, f) || null"
                  type="datetime"
                  value-format="YYYY-MM-DD HH:mm"
                  :disabled="isCaseFieldSaving(row, f)"
                  style="width: 175px"
                  @change="handleCaseFieldChange(row, f, $event)"
                />
                <el-input-number
                  v-else-if="f.fieldType === 'number'"
                  :model-value="caseFieldValue(row, f) === '' ? undefined : Number(caseFieldValue(row, f))"
                  :controls="false"
                  :disabled="isCaseFieldSaving(row, f)"
                  style="width: 160px"
                  @change="handleCaseFieldChange(row, f, $event)"
                />
                <el-input
                  v-else
                  :model-value="caseFieldValue(row, f)"
                  :disabled="isCaseFieldSaving(row, f)"
                  style="width: 160px"
                  @change="handleCaseFieldChange(row, f, $event)"
                />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80" align="center">
              <template #default="{ row }">
                <el-button type="danger" link size="small" @click="handleRemoveManualCase(row)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="manualCaseDetails.length === 0" class="empty-tip">暂无关联手动化用例，点击右上角「添加用例」</div>
        </template>
        <!-- 自动测试计划：自动化套件表格 -->
        <template v-else>
          <el-table :data="autoSuiteDetails" row-key="id" border stripe>
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="name" label="套件名称" min-width="300" show-overflow-tooltip />
            <el-table-column prop="caseCount" label="用例数" width="100" align="center" />
            <el-table-column label="操作" width="80" align="center">
              <template #default="{ row }">
                <el-button type="danger" link size="small" @click="handleRemoveSuite(row)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="autoSuiteDetails.length === 0" class="empty-tip">暂无关联自动化套件，点击右上角「添加套件」</div>
        </template>
      </div>
    </el-card>

    <!-- 选择弹窗：手动化用例（锁定手动用例 Tab，多选） -->
    <CaseSelectDialog
      v-model:visible="caseDialogVisible"
      :project-id="projectId"
      multiple
      fixed-tab="MANUAL_CASE"
      :exclude-ids="manualCaseIds"
      @confirm="handleAddManualCases"
    />
    <!-- 选择弹窗：自动化套件（多选） -->
    <AutoSuiteSelectDialog
      v-model:visible="suiteDialogVisible"
      :project-id="projectId"
      :exclude-ids="autoSuiteIds"
      @confirm="handleAddSuites"
    />
  </div>
</template>

<style scoped>
.empty-tip {
  text-align: center;
  color: #909399;
  padding: 20px;
  font-size: 13px;
}
</style>
