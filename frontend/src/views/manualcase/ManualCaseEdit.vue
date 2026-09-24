<!--
 @author HXN
 @date 2026-08-30
 @description 手动化用例新建/详情统一视图
-->
<script setup lang="ts">
/**
 * 手动化用例统一视图（新建 / 详情同一界面，按路由是否带 caseId 区分模式）
 * 新建模式（/manual-cases/new）：无创建人/创建时间/状态、删除与右侧评论/变更记录；
 * 标题在页头直接输入，内容（富文本）直接编辑，参考缺陷"内容"（WangEditor + 全屏；新建页自动填入内容模板），
 * 附件与关联本页暂存、随创建一次性提交（关联参考缺陷"关联"：统一表格，弹窗选目标类型=需求/缺陷），保存后返回列表
 * 详情模式（/manual-cases/:caseId）：标题点击行内编辑失焦保存；内容查看态只读，编辑/取消/保存在内容标题行；
 * 用例状态由页头状态下拉切换（值走 case_status 列，选项来自【页面配置-手动用例字段】）
 * 字段信息：统一使用【页面配置-手动用例字段】配置，按"显示位置"区分新建/详情可见性
 * 附件 / 关联：新建本页暂存；详情直接操作（增删即时保存）
 */
import { ref, reactive, computed, watch, onMounted, nextTick, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getManualCase, createManualCase, updateManualCase, deleteManualCase, toggleManualCaseStatus,
  addManualCaseAttachment, deleteManualCaseAttachment, getManualCaseGroups,
} from '@/api/manualCase'
import {
  getCaseRequirementRelations, addRequirementCaseRelation, deleteRequirementCaseRelation,
  getDefectRelationsByTarget,
} from '@/api/relation'
import { addDefectRelation, deleteDefectRelation } from '@/api/defect'
import PageHeader from '@/components/PageHeader/index.vue'
import DynamicFieldGrid from '@/components/DynamicFieldGrid/index.vue'
import CommentPanel from '@/components/CommentPanel/index.vue'
import ChangeLogPanel from '@/components/ChangeLogPanel/index.vue'
import RequirementItemSelectDialog from '@/components/RequirementItemSelectDialog/index.vue'
import DefectSelectDialog from '@/components/DefectSelectDialog/index.vue'
import { getCustomFieldsForRender } from '@/api/customField'
import { useManualCaseStatusOptions } from '@/composables/useManualCaseStatus'
import { isScopeVisible } from '@/utils/customFieldScope'
import { getContentTemplates } from '@/api/contentTemplate'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import '@wangeditor/editor/dist/css/style.css'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))
const caseId = computed(() => Number(route.params.caseId))
/** 新建模式：/manual-cases/new 路由不带 caseId 参数 */
const isCreate = computed(() => !route.params.caseId)
// 状态选项优先读【页面配置-手动用例字段】的"状态"字段配置（按项目），无配置回退内置选项
const { options: statusOptions } = useManualCaseStatusOptions(() => projectId.value)

const loading = ref(false)
const saving = ref(false)
const detail = ref<any>({})
// 右侧面板 Tab：评论 / 变更记录
const sideTab = ref('comments')
// 标题行内编辑（详情模式点击标题进入，失焦自动保存）
const titleEditing = ref(false)
const titleInputRef = ref()

const groups = ref<any[]>([])
const userGroups = computed(() => groups.value.filter((g) => g.isSystem !== 1))
/** 分组 ID → 分组名（变更记录值翻译用） */
const groupNameMap = computed(() => {
  const map: Record<string, string> = {}
  groups.value.forEach((g: any) => { map[String(g.id)] = g.name })
  return map
})

const form = reactive({
  title: '',
  // 内容（富文本：前置条件/操作步骤/预期结果统一维护，参考缺陷"内容"）
  content: '',
  // 所属分组（仅新建模式使用；详情模式分组变更即保存，不走表单）
  groupId: null as number | null,
})
// 动态字段值（fieldKey -> 值）：新建初始化默认值；详情加载与每次保存后同步自后端
const fieldValues = ref<Record<string, any>>({})
// 动态字段配置全量（【页面配置-手动用例字段】统一存 edit 视图；含全部显示位置，变更记录翻译用）
const editFields = ref<any[]>([])
/** 「状态」必填标记：取【页面配置-手动用例字段】配置的 isRequired（系统预置为 1），页头状态区据此显示红星 */
const statusRequired = ref(false)

/** 当前模式可见字段：按"显示位置"过滤（新建=含"新建"位置；详情=含"详情"位置） */
const visibleEditFields = computed(() =>
  editFields.value.filter((f: any) => isScopeVisible(f.displayScope, isCreate.value ? 'create' : 'detail'))
)

// 附件（新建模式本页暂存随创建提交；详情模式实时增删）
const attachmentVisible = ref(false)
const attachmentForm = reactive({ fileName: '', fileUrl: '', fileSize: undefined as number | undefined })
// 新建模式待提交附件列表
const draftAttachments = ref<any[]>([])
/** 附件列表：新建模式为待提交暂存列表，详情模式为后端数据 */
const attachmentList = computed(() => (isCreate.value ? draftAttachments.value : (detail.value.attachments || [])))

// ===== 关联（参考缺陷"关联"：统一表格 + 添加弹窗；新建模式暂存随创建提交，详情模式实时增删） =====
// 添加关联弹窗表单（目标类型=需求/缺陷，均走选择器单选）
const RELATION_TARGET_TYPES = [
  { value: 'REQUIREMENT', label: '需求' },
  { value: 'DEFECT', label: '缺陷' },
]
const relationForm = reactive({
  // 目标类型：REQUIREMENT-需求，DEFECT-缺陷
  targetType: 'REQUIREMENT',
  targetId: null as number | null,
  targetTitle: '',
})
const relationVisible = ref(false)
const requirementSelectVisible = ref(false)
const defectSelectVisible = ref(false)
// 新建模式待提交关联列表（统一行：targetType + targetId + targetTitle；创建时按类型拆回两组提交）
const draftRelations = ref<Array<{ targetType: string; targetId: number; targetTitle: string }>>([])
// 详情模式两类关联列表（反查接口，分别对应 requirement_case_relation / defect_relation 两表）
const requirementRelations = ref<any[]>([])
const defectRelations = ref<any[]>([])
/** 目标类型 → 中文名（表格「目标类型」列展示） */
const relationTargetLabelMap: Record<string, string> = { REQUIREMENT: '需求', DEFECT: '缺陷' }
/** 关联列表（归一化行）：新建=本地暂存；详情=需求/缺陷两路反查数据合并（raw 保留原始行供删除分发） */
const relationList = computed(() => {
  if (isCreate.value) return draftRelations.value
  return [
    ...requirementRelations.value.map((r: any) => ({
      targetType: 'REQUIREMENT',
      targetId: r.requirementItemId,
      targetTitle: r.requirementItemTitle || '',
      raw: r,
    })),
    ...defectRelations.value.map((r: any) => ({
      targetType: 'DEFECT',
      targetId: r.defectId,
      targetTitle: r.defectTitle || '',
      raw: r,
    })),
  ]
})

/** 解析动态字段选项：优先 field.options，回退 optionsJson（与 DynamicFieldGrid 逻辑一致） */
function parseFieldOptions(field: any): any[] {
  if (field?.options && field.options.length > 0) return field.options
  if (!field?.optionsJson) return []
  try { return JSON.parse(field.optionsJson) } catch { return [] }
}

// ===== 变更记录展示（字段中文名 + 值翻译；动态字段以 customField: 前缀记录） =====
/** 变更记录字段名 → 中文标签（未收录字段显示原文；动态字段取字段配置名称） */
const historyFieldLabels = computed(() => {
  const map: Record<string, string> = {
    title: '用例标题',
    content: '内容',
    groupId: '所属分组',
    caseStatus: '用例状态',
    attachment: '附件',
    relation: '关联',
  }
  editFields.value.forEach((f: any) => { map[`customField:${f.fieldKey}`] = f.fieldLabel })
  return map
})

/** 变更记录值 → 展示文案（状态/分组/动态字段枚举翻译为选项 label） */
const historyValueLabels = computed(() => {
  const map: Record<string, Record<string, string>> = {
    groupId: groupNameMap.value,
  }
  const statusMap: Record<string, string> = {}
  statusOptions.value.forEach((o: any) => { statusMap[String(o.value)] = o.label })
  map.caseStatus = statusMap
  editFields.value.forEach((f: any) => {
    const opts = parseFieldOptions(f)
    if (opts.length > 0) {
      const m: Record<string, string> = {}
      opts.forEach((o: any) => { m[String(o.value)] = o.label })
      map[`customField:${f.fieldKey}`] = m
    }
  })
  return map
})

/** 创建时间：展示到秒（yyyy-MM-dd HH:mm:ss） */
function formatDateTime(time: string | null | undefined): string {
  if (!time) return '-'
  return time.replace('T', ' ').substring(0, 19)
}

async function fetchDetail() {
  loading.value = true
  try {
    const res: any = await getManualCase(projectId.value, caseId.value)
    detail.value = res.data || {}
    fieldValues.value = { ...(detail.value.customFields || {}) }
    // 同步内容编辑值（详情模式查看态只读，进入内容编辑态时再从详情回填）
    form.content = detail.value.content || ''
    fetchRequirementRelations()
    fetchDefectRelations()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '加载用例详情失败')
  } finally {
    loading.value = false
  }
}

async function fetchGroups() {
  try {
    const res: any = await getManualCaseGroups(projectId.value)
    groups.value = res.data || []
  } catch { groups.value = [] }
}

/** 动态字段配置（【页面配置-手动用例字段】统一存 edit 视图；新建模式初始化可见字段默认值） */
async function fetchEditFields() {
  try {
    const res: any = await getCustomFieldsForRender({
      projectId: projectId.value,
      module: 'manual_case',
      viewType: 'edit',
    })
    const fields: any[] = res.data || []
    // 「状态」必填标记：取页面配置（系统预置必填），页头状态区据此显示红星
    statusRequired.value = fields.find((f: any) => f.fieldKey === 'case_status')?.isRequired === 1
    // 状态字段（case_status）仅作为页头切换下拉的选项来源，不进字段信息区渲染（其值走 manual_case.case_status 列，不走自定义字段值）
    editFields.value = fields.filter((f: any) => f.fieldKey !== 'case_status')
    // 新建模式：初始化可见字段默认值（详情模式由后端回填值，无需默认值）
    if (isCreate.value) {
      for (const field of visibleEditFields.value) {
        if (field.defaultValue !== null && field.defaultValue !== undefined && field.defaultValue !== '') {
          if (fieldValues.value[field.fieldKey] === undefined) {
            fieldValues.value[field.fieldKey] = field.defaultValue
          }
        }
      }
    }
  } catch { editFields.value = [] }
}

/** 详情模式：反查用例关联的需求条目 */
async function fetchRequirementRelations() {
  if (isCreate.value) return
  try {
    const res: any = await getCaseRequirementRelations(projectId.value, 'MANUAL_CASE', caseId.value)
    requirementRelations.value = res.data || []
  } catch { requirementRelations.value = [] }
}

/** 详情模式：按目标反查用例关联的缺陷 */
async function fetchDefectRelations() {
  if (isCreate.value) return
  try {
    const res: any = await getDefectRelationsByTarget(projectId.value, 'MANUAL_CASE', caseId.value)
    defectRelations.value = res.data || []
  } catch { defectRelations.value = [] }
}

// ===== 标题（新建页头常驻输入；详情点击行内编辑、失焦保存） =====
/** 点击用例标题：进入行内编辑（详情模式） */
function startTitleEdit() {
  if (!detail.value.id) return
  form.title = detail.value.title || ''
  titleEditing.value = true
  nextTick(() => {
    const input = titleInputRef.value as any
    if (input) {
      input.focus()
      if (typeof input.select === 'function') input.select()
    }
  })
}

/** 标题失焦：退出编辑并自动保存（空标题回退原值） */
async function handleTitleBlur() {
  titleEditing.value = false
  const title = form.title.trim()
  if (!title) {
    ElMessage.warning('用例标题不能为空')
    return
  }
  if (title === (detail.value.title || '')) return
  try {
    await updateManualCase(projectId.value, caseId.value, { title })
    ElMessage.success('已保存')
    await fetchDetail()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
    await fetchDetail()
  }
}

// ===== 内容（WangEditor 富文本，参考缺陷"内容"；新建直接编辑，详情模式编辑/取消/保存） =====
// WangEditor（查看态只读；defaultConfig 仅创建时生效，切换编辑态用 enable/disable）
const editorRef = shallowRef<any>(null)
// 内容全屏状态（全屏时「退出全屏」按钮浮动于编辑器上方）
const isFullscreen = ref(false)
const editorConfig = {
  // 新建模式内容直接编辑；详情模式查看态只读，进入编辑态用 enable/disable 切换
  readOnly: !isCreate.value,
  placeholder: '请输入用例内容...',
  // 全屏按钮已移至「内容」标题旁，工具栏不再内置
  excludeKeys: ['fullScreen'],
  MENU_CONF: {
    uploadImage: { disabled: true },
    uploadVideo: { disabled: true },
  },
}
function onEditorCreated(editor: any) {
  editorRef.value = editor
  editor.on('fullScreen', () => { isFullscreen.value = true })
  editor.on('unFullScreen', () => { isFullscreen.value = false })
}

/** 切换内容编辑器全屏（入口为「内容」标题旁按钮） */
function toggleFullscreen() {
  const editor = editorRef.value
  if (!editor) return
  if (isFullscreen.value) editor.unFullScreen()
  else editor.fullScreen()
}

// 编辑器内容绑定：新建模式直接读写表单；详情模式查看态展示详情内容，编辑态读写表单内容
const contentEditing = ref(false)
const contentModel = computed({
  get: () => (isCreate.value || contentEditing.value ? form.content : (detail.value.content || '')),
  set: (val: string) => { if (isCreate.value || contentEditing.value) form.content = val },
})

// 详情模式内容编辑态：编辑/取消/保存（富文本无法用失焦保存——点击工具栏即失焦）
watch(contentEditing, (val) => {
  const editor = editorRef.value
  if (!editor) return
  if (val) editor.enable()
  else editor.disable()
})

/** 进入内容编辑：从详情回填表单内容 */
function startContentEdit() {
  form.content = detail.value.content || ''
  contentEditing.value = true
}

/** 取消内容编辑：退出编辑态（表单内容不提交） */
function handleContentCancel() {
  contentEditing.value = false
}

/** 内容保存：仅提交内容字段（标题/分组/动态字段各自独立保存） */
async function handleContentSave() {
  saving.value = true
  try {
    await updateManualCase(projectId.value, caseId.value, { content: form.content })
    ElMessage.success('已保存')
    contentEditing.value = false
    await fetchDetail()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
    await fetchDetail()
  } finally {
    saving.value = false
  }
}

// ===== 内容模板（【页面配置-内容模板】按项目 + 业务类型维护；仅新建页自动填入，详情页无套用入口） =====
/**
 * 新建模式：加载「手动用例」内容模板并自动填入内容区（模板内容非空时）；
 * 模板仅作用于新建页，进入页面即填充，无需手动套用
 */
async function applyContentTemplateOnCreate() {
  if (!isCreate.value) return
  try {
    const res: any = await getContentTemplates({ projectId: projectId.value, bizType: 'manual_case' })
    const tpl = (res.data || [])[0]
    if (tpl && stripHtml(tpl.content || '')) {
      form.content = tpl.content
    }
  } catch {
    // 模板加载失败不阻塞新建页（内容留空由用户自行填写）
  }
}

/** 去除富文本标签，保留纯文本（内容模板非空判断用） */
function stripHtml(html: string): string {
  return (html || '').replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim()
}

/** 新建模式：整单提交创建（附件/关联随创建一并提交），成功后返回用例列表 */
async function handleCreate() {
  if (!form.title.trim()) {
    ElMessage.warning('请输入用例标题')
    return
  }
  saving.value = true
  try {
    await createManualCase(projectId.value, {
      title: form.title,
      content: form.content,
      groupId: form.groupId,
      customFields: { ...fieldValues.value },
      // 待提交附件/关联列表（本页暂存，随创建一并保存；关联按目标类型拆回两组，与后端创建契约一致）
      attachments: draftAttachments.value,
      requirementItemIds: draftRelations.value.filter((r) => r.targetType === 'REQUIREMENT').map((r) => r.targetId),
      defectRelations: draftRelations.value.filter((r) => r.targetType === 'DEFECT').map((r) => ({ relationType: 'RELATED', defectId: r.targetId })),
    })
    ElMessage.success('创建成功')
    router.push(`/project/${projectId.value}/manual-cases`)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 新建模式：取消并返回用例列表 */
function handleCancel() {
  router.push(`/project/${projectId.value}/manual-cases`)
}

/** 字段信息直接编辑：动态字段变更即保存（新建模式值走表单，无需即时保存） */
async function handleFieldSave() {
  if (isCreate.value) return
  try {
    await updateManualCase(projectId.value, caseId.value, { customFields: { ...fieldValues.value } })
    ElMessage.success('已保存')
    await fetchDetail()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
    await fetchDetail()
  }
}

/** 字段信息直接编辑：所属分组变更即保存（详情模式） */
async function handleGroupChange(val: number | string | undefined) {
  const groupId = typeof val === 'number' ? val : null
  if (groupId === (detail.value.groupId ?? null)) return
  try {
    await updateManualCase(projectId.value, caseId.value, { groupId: groupId ?? undefined })
    ElMessage.success('已保存')
    await fetchDetail()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
    await fetchDetail()
  }
}

/** 页头状态切换：值走 case_status 列（新建固定为使用，由详情页下拉切换） */
async function handleStatusChange(val: string) {
  const target = Number(val)
  if (target === detail.value.caseStatus) return
  try {
    await toggleManualCaseStatus(projectId.value, caseId.value, target)
    ElMessage.success('状态更新成功')
    fetchDetail()
  } catch { ElMessage.error('操作失败') }
}

/** 返回上一页：优先浏览器历史返回（从哪来回哪去）；无历史记录（直接打开链接）时兜底跳转用例列表 */
function handleBack() {
  if (window.history.state?.back) router.back()
  else router.push(`/project/${projectId.value}/manual-cases`)
}

function handleDelete() {
  ElMessageBox.confirm(`确定删除手动化用例「${detail.value.title}」？`, '确认删除', { type: 'warning' })
    .then(async () => {
      await deleteManualCase(projectId.value, caseId.value)
      ElMessage.success('删除成功')
      router.push(`/project/${projectId.value}/manual-cases`)
    })
    .catch(() => {})
}

// ===== 关联（统一弹窗：目标类型=需求/缺陷；选择器单选回填） =====
/** 打开添加关联弹窗：重置表单（目标类型默认需求） */
function openRelationDialog() {
  relationForm.targetType = 'REQUIREMENT'
  relationForm.targetId = null
  relationForm.targetTitle = ''
  relationVisible.value = true
}

/** 目标类型切换：清空已选目标（重新选择） */
function handleTargetTypeChange() {
  relationForm.targetId = null
  relationForm.targetTitle = ''
}

/** 打开目标选择器（按当前目标类型） */
function openTargetSelector() {
  if (relationForm.targetType === 'REQUIREMENT') requirementSelectVisible.value = true
  else defectSelectVisible.value = true
}

/** 关联目标选择确认（单选，点击行即确认）：回填目标 ID/标题（需求/缺陷弹窗 confirm 均含 id 与 title） */
function handleRelationTargetConfirm(rows: Array<{ id: number; title: string }>) {
  const row = rows[0]
  if (!row) return
  relationForm.targetId = row.id
  relationForm.targetTitle = row.title
}

/** 添加关联确认：新建模式暂存（本地防重复）；详情模式按目标类型调对应接口即时保存 */
async function handleAddRelation() {
  if (!relationForm.targetId) {
    ElMessage.warning('请选择关联目标')
    return
  }
  if (isCreate.value) {
    if (draftRelations.value.some((r) => r.targetType === relationForm.targetType && r.targetId === relationForm.targetId)) {
      ElMessage.warning('该目标已在关联列表中')
      return
    }
    draftRelations.value.push({
      targetType: relationForm.targetType,
      targetId: relationForm.targetId,
      targetTitle: relationForm.targetTitle,
    })
    relationVisible.value = false
    return
  }
  try {
    if (relationForm.targetType === 'REQUIREMENT') {
      // 需求关联落 requirement_case_relation（用例视角正向关联）
      await addRequirementCaseRelation(relationForm.targetId, { caseType: 'MANUAL_CASE', caseId: caseId.value })
    } else {
      // 缺陷关联落 defect_relation（本用例作为目标被关联，类型固定 RELATED）
      await addDefectRelation(projectId.value, relationForm.targetId, {
        relationType: 'RELATED',
        targetType: 'MANUAL_CASE',
        targetId: caseId.value,
      })
    }
    ElMessage.success('已添加关联')
    relationVisible.value = false
    fetchRequirementRelations()
    fetchDefectRelations()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '添加关联失败')
  }
}

/** 删除关联：新建模式移除暂存项；详情模式按目标类型调对应删除接口 */
async function handleDeleteRelation(row: any, index: number) {
  if (isCreate.value) {
    draftRelations.value.splice(index, 1)
    return
  }
  try {
    if (row.targetType === 'REQUIREMENT') {
      await deleteRequirementCaseRelation(row.raw.id)
    } else {
      await deleteDefectRelation(projectId.value, row.raw.defectId, row.raw.id)
    }
    ElMessage.success('已解除关联')
    fetchRequirementRelations()
    fetchDefectRelations()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '解除关联失败')
  }
}

// ===== 附件 =====
async function handleAddAttachment() {
  if (!attachmentForm.fileName || !attachmentForm.fileUrl) { ElMessage.warning('请填写文件名和链接'); return }
  // 新建模式：暂存到待提交列表，随创建一并提交
  if (isCreate.value) {
    draftAttachments.value.push({
      fileName: attachmentForm.fileName,
      fileUrl: attachmentForm.fileUrl,
      fileSize: attachmentForm.fileSize,
    })
    attachmentVisible.value = false
    Object.assign(attachmentForm, { fileName: '', fileUrl: '', fileSize: undefined })
    return
  }
  try {
    await addManualCaseAttachment(projectId.value, caseId.value, {
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

/** 删除附件：新建模式移除暂存项；详情模式调后端删除 */
async function handleDeleteAttachment(row: any, index: number) {
  if (isCreate.value) {
    draftAttachments.value.splice(index, 1)
    return
  }
  try {
    await deleteManualCaseAttachment(projectId.value, caseId.value, row.id)
    ElMessage.success('删除成功')
    fetchDetail()
  } catch { ElMessage.error('删除失败') }
}

function openFile(url: string) {
  window.open(url, '_blank')
}

onMounted(() => {
  fetchGroups()
  fetchEditFields()
  applyContentTemplateOnCreate()
  // 新建模式无详情可拉取（附件/关联为本页暂存）
  if (!isCreate.value) fetchDetail()
})
</script>

<template>
  <div class="manual-case-detail-page">
    <PageHeader :title="isCreate ? '新建手动化用例' : '手动化用例详情'">
      <!-- 新建模式：常驻标题输入框；详情模式：查看态文本 / 编辑态输入框（位置一致） -->
      <template #title-suffix>
        <el-input
          v-if="isCreate"
          v-model="form.title"
          class="header-title-input"
          placeholder="请输入用例标题"
          maxlength="200"
        />
        <el-input
          v-else-if="titleEditing"
          ref="titleInputRef"
          v-model="form.title"
          class="header-title-input"
          placeholder="请输入用例标题"
          maxlength="200"
          @blur="handleTitleBlur"
          @keyup.enter="handleTitleBlur"
        />
        <span
          v-else
          class="header-title-text header-title-editable"
          title="点击编辑标题"
          @click="startTitleEdit"
        >{{ detail.title }}</span>
      </template>
      <!-- 新建模式：取消/保存；详情模式：返回 + 删除 -->
      <template v-if="isCreate">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleCreate">保存</el-button>
      </template>
      <template v-else>
        <el-button @click="handleBack">返回</el-button>
        <el-button type="danger" @click="handleDelete">删除</el-button>
      </template>
    </PageHeader>

    <div class="detail-layout">
      <!-- 左侧主信息 -->
      <div v-loading="loading" class="detail-main">
        <div class="detail-card">
          <!-- 创建人/创建时间/状态：仅详情模式展示 -->
          <div v-if="!isCreate" class="detail-header">
            <div class="detail-meta">
              <span class="meta-item">创建人：{{ detail.createdByName || '-' }}</span>
              <span class="meta-item">创建时间：{{ formatDateTime(detail.createdAt) }}</span>
              <div class="meta-status-group">
                <!-- 必填标记：「状态」在【页面配置】中配置为必填时显示红星 -->
                <span v-if="statusRequired" class="meta-asterisk">*</span>
                <span class="meta-item">状态：</span>
                <el-select
                  :model-value="detail.caseStatus != null ? String(detail.caseStatus) : ''"
                  style="width: 110px"
                  @change="(val: string) => handleStatusChange(val)"
                >
                  <el-option v-for="s in statusOptions" :key="s.value" :value="s.value" :label="s.label" />
                </el-select>
              </div>
            </div>
          </div>

          <!-- 内容（富文本：新建直接编辑；详情模式查看态只读，编辑/取消/保存在本模块标题行；参考缺陷"内容"） -->
          <div class="detail-block">
            <div class="block-title">
              <span class="block-title-left">
                <span>内容</span>
                <el-button
                  class="fullscreen-btn"
                  :class="{ 'is-floating': isFullscreen }"
                  size="small"
                  @click="toggleFullscreen"
                >{{ isFullscreen ? '退出全屏' : '全屏' }}</el-button>
              </span>
              <div v-if="!isCreate" class="detail-actions">
                <template v-if="contentEditing">
                  <el-button @click="handleContentCancel">取消</el-button>
                  <el-button type="primary" :loading="saving" @click="handleContentSave">保存</el-button>
                </template>
                <el-button v-else type="primary" @click="startContentEdit">编辑</el-button>
              </div>
            </div>
            <div class="editor-wrapper">
              <!-- 工具栏仅编辑模式显示（新建模式可直接编辑）；v-show 保持 DOM 以兼容全屏（工具栏与编辑区需同父级） -->
              <Toolbar v-show="isCreate || contentEditing" :editor="editorRef" :default-config="editorConfig" mode="default" style="border-bottom: 1px solid #ccc" />
              <Editor v-model="contentModel" :default-config="editorConfig" mode="default" style="height: 400px; overflow-y: hidden" @on-created="onEditorCreated" />
            </div>
          </div>

          <!-- 附件 -->
          <div class="detail-block">
            <div class="block-title">附件</div>
            <div class="tab-toolbar">
              <el-button type="primary" size="small" @click="attachmentVisible = true">添加附件</el-button>
            </div>
            <el-table :data="attachmentList" border stripe>
              <el-table-column prop="fileName" label="文件名" />
              <el-table-column prop="fileSize" label="大小（字节）" width="130" />
              <el-table-column v-if="!isCreate" prop="createdByName" label="上传人" width="120" />
              <el-table-column label="操作" width="140">
                <template #default="{ row, $index }">
                  <el-button type="primary" link size="small" @click="openFile(row.fileUrl)">下载</el-button>
                  <el-button type="danger" link size="small" @click="handleDeleteAttachment(row, $index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 字段信息（直接编辑、变更即保存：所属分组 + 动态字段） -->
          <div class="detail-block">
            <div class="block-title">字段信息</div>
            <el-form label-position="top">
              <DynamicFieldGrid
                v-if="visibleEditFields.length > 0"
                :fields="visibleEditFields"
                :model-value="fieldValues"
                @update:model-value="fieldValues = $event"
                @field-change="handleFieldSave"
              >
                <template #prepend>
                  <el-form-item label="所属分组">
                    <!-- 新建模式：值随表单提交；详情模式：变更即保存 -->
                    <el-select v-if="isCreate" v-model="form.groupId" placeholder="未分组" clearable filterable style="width: 100%">
                      <el-option v-for="g in userGroups" :key="g.id" :value="g.id" :label="g.name" />
                    </el-select>
                    <el-select v-else :model-value="detail.groupId ?? null" placeholder="未分组" clearable filterable style="width: 100%" @change="handleGroupChange">
                      <el-option v-for="g in userGroups" :key="g.id" :value="g.id" :label="g.name" />
                    </el-select>
                  </el-form-item>
                </template>
              </DynamicFieldGrid>
              <el-form-item v-else label="所属分组">
                <el-select v-if="isCreate" v-model="form.groupId" placeholder="未分组" clearable filterable style="width: 100%">
                  <el-option v-for="g in userGroups" :key="g.id" :value="g.id" :label="g.name" />
                </el-select>
                <el-select v-else :model-value="detail.groupId ?? null" placeholder="未分组" clearable filterable style="width: 100%" @change="handleGroupChange">
                  <el-option v-for="g in userGroups" :key="g.id" :value="g.id" :label="g.name" />
                </el-select>
              </el-form-item>
            </el-form>
          </div>

          <!-- 关联（参考缺陷"关联"：统一表格 + 添加弹窗选目标类型；新建模式本页暂存随创建提交，详情模式增删即时保存） -->
          <div class="detail-block">
            <div class="block-title">关联</div>
            <div class="tab-toolbar">
              <el-button type="primary" size="small" @click="openRelationDialog">添加关联</el-button>
            </div>
            <el-table :data="relationList" border stripe>
              <el-table-column label="目标类型" width="120">
                <template #default="{ row }">{{ relationTargetLabelMap[row.targetType] || row.targetType }}</template>
              </el-table-column>
              <el-table-column prop="targetId" label="目标 ID" width="100" />
              <el-table-column prop="targetTitle" label="目标标题" min-width="200" show-overflow-tooltip />
              <el-table-column label="操作" width="80">
                <template #default="{ row, $index }">
                  <el-button type="danger" link size="small" @click="handleDeleteRelation(row, $index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </div>

      <!-- 右侧：评论 / 变更记录（仅详情模式） -->
      <div v-if="!isCreate" class="detail-side">
        <el-tabs v-model="sideTab" type="card" class="side-tabs">
          <el-tab-pane label="评论" name="comments">
            <CommentPanel biz-type="MANUAL_CASE" :biz-id="caseId" />
          </el-tab-pane>
          <el-tab-pane label="变更记录" name="histories">
            <ChangeLogPanel
              biz-type="MANUAL_CASE"
              :biz-id="caseId"
              :field-label-map="historyFieldLabels"
              :value-label-map="historyValueLabels"
            />
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>

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

    <!-- 添加关联弹窗（目标类型=需求/缺陷，均走选择器单选，参考缺陷"添加关联"） -->
    <el-dialog v-model="relationVisible" title="添加关联" width="460px">
      <el-form label-position="top">
        <el-form-item label="目标类型" required>
          <el-select v-model="relationForm.targetType" style="width: 100%" @change="handleTargetTypeChange">
            <el-option v-for="t in RELATION_TARGET_TYPES" :key="t.value" :value="t.value" :label="t.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联目标" required>
          <div style="display: flex; gap: 8px; width: 100%">
            <el-input :model-value="relationForm.targetTitle" placeholder="点击右侧按钮选择目标" readonly style="flex: 1" />
            <el-button type="primary" @click="openTargetSelector">选择{{ relationTargetLabelMap[relationForm.targetType] }}</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="relationVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAddRelation">确定</el-button>
      </template>
    </el-dialog>

    <!-- 需求条目选择弹窗（单选：点击行即确认） -->
    <RequirementItemSelectDialog
      v-model:visible="requirementSelectVisible"
      :project-id="projectId"
      @confirm="handleRelationTargetConfirm"
    />

    <!-- 缺陷选择弹窗（单选：点击行即确认） -->
    <DefectSelectDialog
      v-model:visible="defectSelectVisible"
      :project-id="projectId"
      @confirm="handleRelationTargetConfirm"
    />
  </div>
</template>

<style scoped>
/* 页面撑满可视主区：相对 .app-main-wrapper（Layout 中 position: relative）绝对定位，
   不依赖中间层 flex 高度的百分比解析；卡片样式内聚到本页，覆盖同位的 .app-main */
.manual-case-detail-page {
  position: absolute;
  top: 16px;
  right: 16px;
  bottom: 16px;
  left: 16px;
  padding: 20px;
  background: #fff;
  border-radius: 4px;
  display: flex;
  flex-direction: column;
}
/* 左右两栏：左侧主信息 + 右侧评论/变更记录面板。
   注意不可加 flex-wrap：wrap 容器在子项内容高于容器时会把行高按内容撑开，
   导致 align-items:stretch 失效、子项溢出产生外层滚动条；窄屏由媒体查询改为堆叠 */
.detail-layout {
  display: flex;
  align-items: stretch;
  gap: 16px;
  flex: 1;
  min-height: 0;
}
.detail-main {
  flex: 1 1 560px;
  min-width: 420px;
  min-height: 0;
  overflow-y: auto;
  /* 滚到边界时不再联动外层页面滚动 */
  overscroll-behavior: contain;
}
.detail-side {
  /* 允许收缩：窄一点时左右两栏仍同行，避免在断点附近换行溢出产生外层滚动条 */
  flex: 0 1 400px;
  min-width: 360px;
  max-width: 100%;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px 16px 16px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
@media (max-width: 1180px) {
  /* 窄屏：整页仍固定高度（不出现外层滚动条），双栏上下堆叠、各自内部滚动 */
  .detail-layout {
    flex-direction: column;
    flex-wrap: nowrap;
  }
  .detail-main {
    flex: 1 1 0;
    min-width: 0;
    min-height: 0;
  }
  .detail-side {
    flex: 0 0 40%;
    max-width: 100%;
    min-width: 0;
    min-height: 0;
  }
}
.side-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
/* 评论面板（CommentPanel）根元素 height:100% 需要父级高度链成立 */
.side-tabs :deep(.el-tab-pane) {
  height: 100%;
}
.side-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
  flex-shrink: 0;
}
/* 评论 / 变更记录内容超出时内部滚轮 */
.side-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  /* 滚到边界时不再联动外层页面滚动 */
  overscroll-behavior: contain;
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
/* 首个区块无上分隔线：详情模式紧跟页头信息区；新建模式无信息区（区块为卡片首子元素） */
.detail-header + .detail-block,
.detail-card > .detail-block:first-child {
  margin-top: 0;
  padding-top: 0;
  border-top: none;
}
.block-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
/* 页头编号右侧标题：查看态文本 */
.header-title-text {
  font-size: 15px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 页头标题：详情模式点击即行内编辑（鼠标保持默认样式，不做手型暗示） */
.header-title-editable:hover {
  color: #409eff;
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
/* 状态切换下拉框组（「状态：」+ 下拉框）固定在「创建人/创建时间」行右侧 */
.meta-status-group {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 必填星号（「状态」在【页面配置】中配置为必填时显示，样式对齐 Element Plus 必填标记） */
.meta-asterisk {
  color: var(--el-color-danger);
  margin-right: -4px;
}
.field-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px 32px;
}
.tab-toolbar {
  margin-bottom: 12px;
}
/* 「内容」标题行左侧：标题 + 全屏按钮 */
.block-title-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
/* 全屏时「退出全屏」按钮浮动于编辑器上方（全屏遮罩 z-index: 3001） */
.fullscreen-btn.is-floating {
  position: fixed;
  top: 12px;
  right: 20px;
  z-index: 3002;
}
/* 内容编辑操作组（详情模式：编辑/取消/保存） */
.detail-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.editor-wrapper {
  border: 1px solid #ccc;
  border-radius: 4px;
  overflow: hidden;
}
/* 全屏时仅编辑器覆盖视口；wangeditor 全屏类无 z-index，需高于页签栏(3000)避免其他元素浮入 */
.editor-wrapper.w-e-full-screen-container {
  z-index: 3001;
  background: #fff;
}
</style>
