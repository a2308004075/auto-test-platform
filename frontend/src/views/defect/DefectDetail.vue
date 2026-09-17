<!--
 @author HXN
 @date 2026-08-30
 @description 缺陷详情视图
-->
<script setup lang="ts">
/**
 * 缺陷详情（内置查看/编辑模式）
 * 查看态：标签页展示 内容、字段、工时、层级、关联、附件、变更记录
 * 编辑态：页头编号右侧编辑标题，内容 Tab（富文本）、字段 Tab（所属分组+动态字段+汇总工时）可编辑，保存后停留本页
 */
import { ref, reactive, onMounted, computed, watch, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDefect, updateDefect, deleteDefect, transitionDefectStatus,
  addDefectWorkLog, deleteDefectWorkLog,
  addDefectRelation, deleteDefectRelation,
  addDefectAttachment, deleteDefectAttachment, getDefectGroups
} from '@/api/defect'
import PageHeader from '@/components/PageHeader/index.vue'
import CaseSelectDialog from '@/components/CaseSelectDialog/index.vue'
import DynamicFieldGrid from '@/components/DynamicFieldGrid/index.vue'
import CommentPanel from '@/components/CommentPanel/index.vue'
import { getCustomFieldsForRender } from '@/api/customField'
import { useDict } from '@/composables/useDict'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import '@wangeditor/editor/dist/css/style.css'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))
const defectId = computed(() => Number(route.params.defectId))
const { options: relationTypeOptions } = useDict('defect_relation_type')
const { options: targetTypeOptions } = useDict('defect_target_type')
const { options: statusOptions } = useDict('defect_status')

const relationTypeLabelMap = computed(() => {
  const map: Record<string, string> = {}
  relationTypeOptions.value.forEach((o) => { map[o.value] = o.label })
  return map
})
const targetTypeLabelMap = computed(() => {
  const map: Record<string, string> = {}
  targetTypeOptions.value.forEach((o) => { map[o.value] = o.label })
  return map
})

const loading = ref(false)
const detail = ref<any>({})
// 右侧面板 Tab：评论 / 变更记录
const sideTab = ref('comments')

// ===== 查看/编辑模式（编辑功能与旧编辑页保持一致） =====
const editing = ref(false)
const saving = ref(false)
const groups = ref<any[]>([])
const userGroups = computed(() => groups.value.filter((g) => g.isSystem !== 1))
const form = reactive({
  title: '',
  content: '',
  groupId: null as number | null,
  estimatedHours: 0,
  actualHours: 0,
  remainingHours: 0,
})
// 编辑态动态字段值（fieldKey -> 值）
const fieldValues = ref<Record<string, any>>({})
// 编辑态动态字段配置（【字段管理】中【缺陷-编辑缺陷】视图）
const editFields = ref<any[]>([])

// 工时
const workLogForm = reactive({ logDate: '', hours: 0, workType: 'ACTUAL', description: '' })
const workLogVisible = ref(false)

// 关联
const relationForm = reactive({ relationType: 'RELATED', targetType: 'AUTO_CASE', targetId: undefined as number | undefined, targetTitle: '' })
const relationVisible = ref(false)
// 用例类目标（手动/自动化用例）支持搜索选择，其余类型手动输入
const isCaseTarget = computed(() => ['MANUAL_CASE', 'AUTO_CASE'].includes(relationForm.targetType))
const caseSelectVisible = ref(false)

function handleTargetTypeChange() {
  relationForm.targetId = undefined
  relationForm.targetTitle = ''
}

function handleCaseConfirm(rows: Array<{ id: number; title: string }>) {
  if (rows.length === 0) return
  relationForm.targetId = rows[0].id
  relationForm.targetTitle = rows[0].title
}

// 附件
const attachmentForm = reactive({ fileName: '', fileUrl: '', fileSize: undefined as number | undefined })
const attachmentVisible = ref(false)

// WangEditor（查看态只读；defaultConfig 仅创建时生效，切换编辑态用 enable/disable）
const editorRef = shallowRef<any>(null)
const editorConfig = {
  readOnly: true,
  MENU_CONF: {
    uploadImage: { disabled: true },
    uploadVideo: { disabled: true },
  },
}
function onEditorCreated(editor: any) { editorRef.value = editor }

// 编辑器内容绑定：查看态展示详情内容，编辑态读写表单内容
const contentModel = computed({
  get: () => (editing.value ? form.content : (detail.value.content || '')),
  set: (val: string) => { if (editing.value) form.content = val },
})

watch(editing, (val) => {
  const editor = editorRef.value
  if (!editor) return
  if (val) editor.enable()
  else editor.disable()
})

// 标签色为前端展示样式；状态名称统一取自字典（sys_dict: defect_status）
const statusTypeMap: Record<string, string> = {
  NEW: 'info',
  TO_CONFIRM: 'warning',
  FIXING: 'primary',
  TO_DEPLOY: 'warning',
  PENDING: 'warning',
  COMPLETED: 'success',
  REOPENED: 'danger',
  DEFERRED: 'info',
  CLOSED: 'info',
}
const statusLabelMap = computed(() => {
  const map: Record<string, string> = {}
  statusOptions.value.forEach((o) => { map[o.value] = o.label })
  return map
})

// 动态字段（【字段管理】中【缺陷】create/edit 视图字段配置，按 fieldKey 去重合并）
const customFields = ref<any[]>([])

async function fetchCustomFields() {
  try {
    const [createRes, editRes]: any[] = await Promise.all([
      getCustomFieldsForRender({ projectId: projectId.value, module: 'defect', viewType: 'create' }),
      getCustomFieldsForRender({ projectId: projectId.value, module: 'defect', viewType: 'edit' }),
    ])
    const createFields = createRes.data || []
    const editOnlyFields = (editRes.data || []).filter(
      (f: any) => !createFields.some((c: any) => c.fieldKey === f.fieldKey)
    )
    customFields.value = [...createFields, ...editOnlyFields]
  } catch {
    customFields.value = []
  }
}

// 展示值：选项类字段（select/user/environment）将 value 映射为 label
const OPTION_FIELD_TYPES = ['select', 'user', 'environment']
function displayFieldValue(field: any): string {
  const value = detail.value.customFields?.[field.fieldKey]
  if (value === undefined || value === null || value === '') return '-'
  if (OPTION_FIELD_TYPES.includes(field.fieldType) && Array.isArray(field.options)) {
    const opt = field.options.find((o: any) => String(o.value) === String(value))
    if (opt) return opt.label
  }
  return String(value)
}

// ===== 变更记录展示（中文字段名 + 旧值 → 新值） =====
/** 变更记录字段名 → 中文标签（未收录字段显示原文） */
const HISTORY_FIELD_LABELS: Record<string, string> = {
  title: '标题',
  content: '内容',
  assigneeId: '负责人',
  dueDate: '截止日期',
  foundVersion: '发现版本',
  moduleName: '所属模块',
  severity: '严重程度',
  source: '来源',
  environmentId: '环境',
  reasonDescription: '原因描述',
  responsibleId: '责任人',
  fixedVersion: '修复版本',
  planTestDate: '计划测试日期',
  status: '状态',
  groupId: '所属分组',
  parentId: '父缺陷',
  estimatedHours: '总估算工时',
  actualHours: '总实际工时',
  remainingHours: '总剩余工时',
  remark: '备注',
}

function historyFieldLabel(fieldName: string): string {
  return HISTORY_FIELD_LABELS[fieldName] || fieldName
}

/** 分组 ID → 分组名（含系统分组） */
const groupNameMap = computed(() => {
  const map: Record<string, string> = {}
  groups.value.forEach((g: any) => { map[String(g.id)] = g.name })
  return map
})

/** 去除富文本标签，保留纯文本（用于内容变更展示） */
function stripHtml(html: string): string {
  return html.replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim()
}

/** 变更记录值展示：状态/分组映射为名称，富文本内容去标签，空值显示「空」 */
function historyValueText(fieldName: string, value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '空'
  if (fieldName === 'status') return statusLabelMap.value[value] || value
  if (fieldName === 'groupId') return groupNameMap.value[value] || value
  if (fieldName === 'content') return stripHtml(value)
  return value
}

function formatHistoryTime(time: string | null | undefined): string {
  if (!time) return '-'
  return time.replace('T', ' ').substring(0, 16)
}

async function fetchDetail() {
  loading.value = true
  try {
    const res: any = await getDefect(projectId.value, defectId.value)
    detail.value = res.data || {}
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '加载缺陷详情失败')
  } finally {
    loading.value = false
  }
}

async function fetchGroups() {
  try {
    const res: any = await getDefectGroups(projectId.value)
    groups.value = res.data || []
  } catch { groups.value = [] }
}

/** 编辑态动态字段配置（与旧编辑页一致：取【缺陷-编辑缺陷】视图） */
async function fetchEditFields() {
  try {
    const res: any = await getCustomFieldsForRender({
      projectId: projectId.value,
      module: 'defect',
      viewType: 'edit',
    })
    editFields.value = res.data || []
  } catch { editFields.value = [] }
}

/** 进入编辑态：以当前详情初始化表单与动态字段值 */
function startEdit() {
  if (!detail.value.id) { ElMessage.warning('缺陷加载中，请稍后再试'); return }
  form.title = detail.value.title || ''
  form.content = detail.value.content || ''
  form.groupId = detail.value.groupId ?? null
  form.estimatedHours = detail.value.estimatedHours || 0
  form.actualHours = detail.value.actualHours || 0
  form.remainingHours = detail.value.remainingHours || 0

  const values = { ...(detail.value.customFields || {}) }
  for (const field of editFields.value) {
    if (field.defaultValue !== null && field.defaultValue !== undefined && field.defaultValue !== '') {
      if (values[field.fieldKey] === undefined) values[field.fieldKey] = field.defaultValue
    }
  }
  fieldValues.value = values
  editing.value = true
}

/** 取消编辑：丢弃本次修改（表单值在下次进入编辑态时重新初始化） */
function handleCancelEdit() {
  editing.value = false
}

/** 保存编辑：提交后退出编辑态并刷新详情，停留在本页 */
async function handleSave() {
  if (!form.title.trim()) {
    ElMessage.warning('请输入缺陷标题')
    return
  }
  saving.value = true
  try {
    await updateDefect(projectId.value, defectId.value, {
      title: form.title,
      content: form.content,
      groupId: form.groupId,
      estimatedHours: form.estimatedHours,
      actualHours: form.actualHours,
      remainingHours: form.remainingHours,
      customFields: { ...fieldValues.value },
    })
    ElMessage.success('更新成功')
    await fetchDetail()
    editing.value = false
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

// 自动计算总估算工时 = 计划完成修复时间 - 计划开始修复时间（小时，与旧编辑页一致）
watch(
  () => [fieldValues.value['defect_plan_start'], fieldValues.value['defect_plan_end']],
  ([start, end]) => {
    if (start && end) {
      const ms = new Date(end).getTime() - new Date(start).getTime()
      const hours = Math.round(ms / (1000 * 60 * 60))
      form.estimatedHours = hours >= 0 ? hours : 0
    } else {
      form.estimatedHours = 0
    }
  },
)

function handleDelete() {
  ElMessageBox.confirm(`确定删除缺陷「${detail.value.defectNo}」？`, '确认删除', { type: 'warning' })
    .then(async () => {
      await deleteDefect(projectId.value, defectId.value)
      ElMessage.success('删除成功')
      router.push(`/project/${projectId.value}/defects`)
    })
    .catch(() => {})
}

async function handleTransition(targetStatus: string) {
  try {
    await transitionDefectStatus(projectId.value, defectId.value, { targetStatus })
    ElMessage.success('状态更新成功')
    fetchDetail()
  } catch { ElMessage.error('操作失败') }
}

/** 流转目标 = 除当前状态外的全部状态（宽松白名单） */
function transitionTargets(current: string) {
  return statusOptions.value.filter((o) => o.value !== current)
}

// 工时
async function handleAddWorkLog() {
  if (!workLogForm.hours) { ElMessage.warning('请输入工时'); return }
  try {
    await addDefectWorkLog(projectId.value, defectId.value, {
      logDate: workLogForm.logDate || undefined,
      hours: workLogForm.hours,
      workType: workLogForm.workType,
      description: workLogForm.description,
    })
    ElMessage.success('添加成功')
    workLogVisible.value = false
    Object.assign(workLogForm, { logDate: '', hours: 0, workType: 'ACTUAL', description: '' })
    fetchDetail()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || '添加失败') }
}

async function handleDeleteWorkLog(id: number) {
  try {
    await deleteDefectWorkLog(projectId.value, defectId.value, id)
    ElMessage.success('删除成功')
    fetchDetail()
  } catch { ElMessage.error('删除失败') }
}

// 关联
async function handleAddRelation() {
  if (!relationForm.targetId) {
    ElMessage.warning(isCaseTarget.value ? '请选择关联的用例' : '请输入关联目标 ID')
    return
  }
  try {
    await addDefectRelation(projectId.value, defectId.value, relationForm)
    ElMessage.success('添加成功')
    relationVisible.value = false
    Object.assign(relationForm, { relationType: 'RELATED', targetType: 'AUTO_CASE', targetId: undefined, targetTitle: '' })
    fetchDetail()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || '添加失败') }
}

async function handleDeleteRelation(id: number) {
  try {
    await deleteDefectRelation(projectId.value, defectId.value, id)
    ElMessage.success('删除成功')
    fetchDetail()
  } catch { ElMessage.error('删除失败') }
}

// 附件
async function handleAddAttachment() {
  if (!attachmentForm.fileName || !attachmentForm.fileUrl) { ElMessage.warning('请填写文件名和链接'); return }
  try {
    await addDefectAttachment(projectId.value, defectId.value, {
      fileName: attachmentForm.fileName,
      fileUrl: attachmentForm.fileUrl,
      fileSize: attachmentForm.fileSize,
    })
    ElMessage.success('添加成功')
    attachmentVisible.value = false
    Object.assign(attachmentForm, { fileName: '', fileUrl: '', fileSize: undefined })
    fetchDetail()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || '添加失败') }
}

async function handleDeleteAttachment(id: number) {
  try {
    await deleteDefectAttachment(projectId.value, defectId.value, id)
    ElMessage.success('删除成功')
    fetchDetail()
  } catch { ElMessage.error('删除失败') }
}

function openFile(url: string) {
  window.open(url, '_blank')
}

onMounted(() => {
  fetchDetail()
  fetchCustomFields()
  fetchGroups()
  fetchEditFields()
})
</script>

<template>
  <div>
    <PageHeader :title="detail.defectNo || '缺陷详情'">
      <!-- 编号右侧：标题（查看态文本 / 编辑态输入框） -->
      <template #title-suffix>
        <el-input
          v-if="editing"
          v-model="form.title"
          class="header-title-input"
          placeholder="请输入缺陷标题"
          maxlength="500"
        />
        <span v-else class="header-title-text">{{ detail.title }}</span>
      </template>
      <template v-if="editing">
        <el-button @click="handleCancelEdit">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
      <template v-else>
        <el-button type="primary" @click="startEdit">编辑</el-button>
        <el-dropdown split-button type="primary" @command="handleTransition">
          状态流转
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="s in transitionTargets(detail.status)" :key="s.value" :command="s.value">{{ s.label }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button type="danger" @click="handleDelete">删除</el-button>
      </template>
    </PageHeader>

    <div class="detail-layout">
      <!-- 左侧主信息 -->
      <div v-loading="loading" class="detail-main">
        <div class="detail-card">
          <div class="detail-header">
            <div class="detail-meta">
              <el-tag :type="(statusTypeMap[detail.status] || 'info') as any" size="small">{{ statusLabelMap[detail.status] || detail.status }}</el-tag>
              <span class="meta-item">创建人：{{ detail.createdByName || '-' }}</span>
              <span class="meta-item">创建时间：{{ detail.createdAt }}</span>
            </div>
          </div>

          <!-- 内容（编辑态可编辑；标题在页头编号右侧编辑） -->
          <div class="detail-block">
            <div class="block-title">内容</div>
            <div class="editor-wrapper">
              <Toolbar :editor="editorRef" :default-config="editorConfig" mode="default" style="border-bottom: 1px solid #ccc" />
              <Editor v-model="contentModel" :default-config="editorConfig" mode="default" style="height: 400px; overflow-y: hidden" @on-created="onEditorCreated" />
            </div>
          </div>

          <!-- 附件 -->
          <div class="detail-block">
            <div class="block-title">附件</div>
            <div class="tab-toolbar">
              <el-button type="primary" size="small" @click="attachmentVisible = true">添加附件</el-button>
            </div>
            <el-table :data="detail.attachments || []" border stripe>
              <el-table-column prop="fileName" label="文件名" />
              <el-table-column prop="fileSize" label="大小（字节）" width="130" />
              <el-table-column prop="createdByName" label="上传人" width="120" />
              <el-table-column label="操作" width="140">
                <template #default="{ row }">
                  <el-button type="primary" link size="small" @click="openFile(row.fileUrl)">下载</el-button>
                  <el-button type="danger" link size="small" @click="handleDeleteAttachment(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 字段信息（查看态只读；编辑态：所属分组 + 动态字段） -->
          <div class="detail-block">
            <div class="block-title">字段信息</div>
            <div v-if="!editing" class="field-grid">
              <div v-for="f in customFields" :key="f.id" class="field-item">
                <span class="field-label">{{ f.fieldLabel }}：</span><span>{{ displayFieldValue(f) }}</span>
              </div>
              <div class="field-item"><span class="field-label">重新打开次数：</span><span>{{ detail.reopenCount ?? 0 }}</span></div>
            </div>

            <el-form v-else label-position="top" :model="form">
              <DynamicFieldGrid
                v-if="editFields.length > 0"
                :fields="editFields"
                :model-value="fieldValues"
                @update:model-value="fieldValues = $event"
              >
                <template #prepend>
                  <el-form-item label="所属分组">
                    <el-select v-model="form.groupId" placeholder="未分组" clearable filterable style="width: 100%">
                      <el-option v-for="g in userGroups" :key="g.id" :value="g.id" :label="g.name" />
                    </el-select>
                  </el-form-item>
                </template>
              </DynamicFieldGrid>
              <el-form-item v-else label="所属分组">
                <el-select v-model="form.groupId" placeholder="未分组" clearable filterable style="width: 100%">
                  <el-option v-for="g in userGroups" :key="g.id" :value="g.id" :label="g.name" />
                </el-select>
              </el-form-item>
            </el-form>
          </div>

          <!-- 汇总工时（编辑态可改实际/剩余，总估算自动计算） -->
          <div class="detail-block">
            <div class="block-title">汇总工时</div>
            <div v-if="!editing" class="field-grid">
              <div class="field-item"><span class="field-label">总估算工时：</span><span>{{ detail.estimatedHours ?? 0 }} 小时</span></div>
              <div class="field-item"><span class="field-label">总实际工时：</span><span>{{ detail.actualHours ?? 0 }} 小时</span></div>
              <div class="field-item"><span class="field-label">总剩余工时：</span><span>{{ detail.remainingHours ?? 0 }} 小时</span></div>
            </div>
            <el-form v-else label-position="top" :model="form">
              <div class="form-row">
                <el-form-item label="总估算工时（小时）" style="flex: 1; max-width: 260px">
                  <el-input :model-value="form.estimatedHours" disabled style="width: 100%" />
                </el-form-item>
                <el-form-item label="总实际工时" style="flex: 1">
                  <el-input-number v-model="form.actualHours" :min="0" :precision="2" style="width: 100%" />
                </el-form-item>
                <el-form-item label="总剩余工时" style="flex: 1">
                  <el-input-number v-model="form.remainingHours" :min="0" :precision="2" style="width: 100%" />
                </el-form-item>
              </div>
            </el-form>
          </div>

          <!-- 工时记录 -->
          <div class="detail-block">
            <div class="block-title">工时记录</div>
            <div class="tab-toolbar">
              <el-button type="primary" size="small" @click="workLogVisible = true">添加工时</el-button>
            </div>
            <el-table :data="detail.workLogs || []" border stripe>
              <el-table-column prop="logDate" label="日期" width="120" />
              <el-table-column prop="hours" label="工时（小时）" width="120" />
              <el-table-column prop="workType" label="类型" width="120" />
              <el-table-column prop="description" label="说明" />
              <el-table-column prop="userName" label="记录人" width="120" />
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button type="danger" link size="small" @click="handleDeleteWorkLog(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 关联 -->
          <div class="detail-block">
            <div class="block-title">关联</div>
            <div class="tab-toolbar">
              <el-button type="primary" size="small" @click="relationVisible = true">添加关联</el-button>
            </div>
            <el-table :data="detail.relations || []" border stripe>
              <el-table-column label="关联类型" width="120">
                <template #default="{ row }">{{ relationTypeLabelMap[row.relationType] || row.relationType }}</template>
              </el-table-column>
              <el-table-column label="目标类型" width="140">
                <template #default="{ row }">{{ targetTypeLabelMap[row.targetType] || row.targetType }}</template>
              </el-table-column>
              <el-table-column prop="targetId" label="目标 ID" width="100" />
              <el-table-column prop="targetTitle" label="目标标题" />
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button type="danger" link size="small" @click="handleDeleteRelation(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 层级（子缺陷） -->
          <div class="detail-block">
            <div class="block-title">层级</div>
            <el-table :data="detail.children || []" border stripe>
              <el-table-column prop="defectNo" label="缺陷编号" width="160" />
              <el-table-column prop="title" label="标题" />
              <el-table-column prop="status" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="(statusTypeMap[row.status] || 'info') as any" size="small">{{ statusLabelMap[row.status] || row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="assigneeName" label="负责人" width="120" />
            </el-table>
          </div>

        </div>
      </div>

      <!-- 右侧：评论 / 变更记录 -->
      <div class="detail-side">
        <el-tabs v-model="sideTab" type="card" class="side-tabs">
          <el-tab-pane label="评论" name="comments">
            <CommentPanel biz-type="DEFECT" :biz-id="defectId" />
          </el-tab-pane>
          <el-tab-pane label="变更记录" name="histories">
            <div class="history-list">
              <div v-for="h in (detail.histories || [])" :key="h.id" class="history-item">
                <div class="history-head">
                  <span class="history-user">{{ h.changedByName || '系统' }}</span>
                  <span>更新了 {{ historyFieldLabel(h.fieldName) }}</span>
                  <span class="history-time">{{ formatHistoryTime(h.createdAt) }}</span>
                </div>
                <div class="history-values">
                  <span class="history-value old">{{ historyValueText(h.fieldName, h.oldValue) }}</span>
                  <span class="history-arrow">→</span>
                  <span class="history-value new">{{ historyValueText(h.fieldName, h.newValue) }}</span>
                </div>
              </div>
              <el-empty v-if="(detail.histories || []).length === 0" description="暂无变更记录" />
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>

    <!-- 添加工时弹窗 -->
    <el-dialog v-model="workLogVisible" title="添加工时" width="460px">
      <el-form label-position="top">
        <el-form-item label="日期">
          <el-date-picker v-model="workLogForm.logDate" type="date" placeholder="选择日期" style="width: 100%" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="工时（小时）" required>
          <el-input-number v-model="workLogForm.hours" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="workLogForm.workType" style="width: 100%">
            <el-option value="ACTUAL" label="实际工时" />
            <el-option value="ESTIMATE" label="估算工时" />
            <el-option value="REMAINING" label="剩余工时" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="workLogForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="workLogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAddWorkLog">确定</el-button>
      </template>
    </el-dialog>

    <!-- 添加关联弹窗 -->
    <el-dialog v-model="relationVisible" title="添加关联" width="460px">
      <el-form label-position="top">
        <el-form-item label="关联类型">
          <el-select v-model="relationForm.relationType" style="width: 100%">
            <el-option v-for="r in relationTypeOptions" :key="r.value" :value="r.value" :label="r.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标类型">
          <el-select v-model="relationForm.targetType" style="width: 100%" @change="handleTargetTypeChange">
            <el-option v-for="t in targetTypeOptions" :key="t.value" :value="t.value" :label="t.label" />
          </el-select>
        </el-form-item>
        <!-- 用例类目标：搜索选择，自动带出 ID/标题 -->
        <el-form-item v-if="isCaseTarget" label="关联目标" required>
          <div style="display: flex; gap: 8px; width: 100%">
            <el-input :model-value="relationForm.targetTitle" placeholder="点击右侧按钮选择用例" readonly style="flex: 1" />
            <el-button type="primary" @click="caseSelectVisible = true">选择用例</el-button>
          </div>
        </el-form-item>
        <!-- 其余目标类型：保持手动输入 -->
        <template v-else>
          <el-form-item label="目标 ID" required>
            <el-input-number v-model="relationForm.targetId" :controls="false" style="width: 100%" />
          </el-form-item>
          <el-form-item label="目标标题">
            <el-input v-model="relationForm.targetTitle" placeholder="关联目标标题快照" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="relationVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAddRelation">确定</el-button>
      </template>
    </el-dialog>

    <!-- 用例选择弹窗（单选） -->
    <CaseSelectDialog v-model:visible="caseSelectVisible" :project-id="projectId" @confirm="handleCaseConfirm" />

    <!-- 添加附件弹窗 -->
    <el-dialog v-model="attachmentVisible" title="添加附件" width="460px">
      <el-form label-position="top">
        <el-form-item label="文件名" required>
          <el-input v-model="attachmentForm.fileName" />
        </el-form-item>
        <el-form-item label="文件链接" required>
          <el-input v-model="attachmentForm.fileUrl" placeholder="文件访问 URL" />
        </el-form-item>
        <el-form-item label="文件大小（字节）">
          <el-input-number v-model="attachmentForm.fileSize" :controls="false" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="attachmentVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAddAttachment">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 左右两栏：左侧主信息 + 右侧评论/变更记录面板 */
.detail-layout {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 16px;
}
.detail-main {
  flex: 1 1 560px;
  min-width: 420px;
}
.detail-side {
  flex: 0 0 400px;
  max-width: 100%;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px 16px 16px;
}
@media (max-width: 1180px) {
  .detail-main,
  .detail-side {
    flex-basis: 100%;
    min-width: 0;
  }
}
.side-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
.detail-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 20px 24px;
}
.detail-header {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #ebeef5;
}
/* 左侧信息区块：小标题 + 区块间分隔线 */
.detail-block {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #ebeef5;
}
.detail-header + .detail-block {
  margin-top: 0;
  padding-top: 0;
  border-top: none;
}
.block-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
}
/* 页头编号右侧标题：查看态文本 */
.header-title-text {
  font-size: 15px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 页头编号右侧标题：编辑态输入框 */
.header-title-input {
  width: 480px;
  max-width: 60vw;
}
.detail-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.meta-item {
  font-size: 13px;
  color: #909399;
}
.editor-wrapper {
  border: 1px solid #ccc;
  border-radius: 4px;
  overflow: hidden;
}
.field-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px 32px;
}
.field-item {
  display: flex;
  font-size: 14px;
  color: #606266;
}
/* 多行文本字段值保留换行展示 */
.field-item > span:last-child {
  white-space: pre-wrap;
  word-break: break-word;
}
.field-label {
  color: #909399;
  min-width: 100px;
}
.tab-toolbar {
  margin-bottom: 12px;
}
.form-row {
  display: flex;
  gap: 16px;
}
.form-row > * {
  min-width: 0;
}
.history-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.history-item {
  font-size: 13px;
  color: #606266;
}
.history-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.history-user {
  font-weight: 600;
  color: #303133;
}
.history-time {
  margin-left: auto;
  color: #909399;
  font-size: 12px;
}
.history-values {
  display: flex;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 6px;
  line-height: 1.6;
}
.history-value {
  padding: 1px 6px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
}
.history-value.old {
  background: #fef0f0;
  color: #f56c6c;
}
.history-value.new {
  background: #f0f9eb;
  color: #67c23a;
}
.history-arrow {
  color: #909399;
}
</style>
