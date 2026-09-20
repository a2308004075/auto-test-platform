<!--
 @author HXN
 @date 2026-08-30
 @description 缺陷详情视图
-->
<script setup lang="ts">
/**
 * 缺陷详情（内置查看/编辑模式）
 * 内容（富文本）：进入编辑模式后修改（取消/保存在内容模块标题行）
 * 缺陷标题：任意模式下点击标题即行内编辑，失焦自动保存
 * 字段信息：无需编辑模式，直接编辑、变更即保存
 * 附件 / 关联：无需编辑模式，直接操作
 */
import { ref, reactive, onMounted, computed, watch, shallowRef, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDefect, updateDefect, deleteDefect, transitionDefectStatus,
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
// 标题行内编辑（任意模式下可编辑，失焦自动保存）
const titleEditing = ref(false)
const titleInputRef = ref()
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
})
// 动态字段值（fieldKey -> 值）：详情加载与每次保存后同步自后端
const fieldValues = ref<Record<string, any>>({})
// 编辑态动态字段配置（【字段管理】中【缺陷-编辑缺陷】视图）
const editFields = ref<any[]>([])

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
// 内容全屏状态（全屏时「退出全屏」按钮浮动于编辑器上方）
const isFullscreen = ref(false)
const editorConfig = {
  readOnly: true,
  // Toolbar 组件直接以本对象为工具栏配置（读取顶层 excludeKeys）：全屏按钮已移至「内容」标题旁，工具栏不再内置
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

const statusLabelMap = computed(() => {
  const map: Record<string, string> = {}
  statusOptions.value.forEach((o) => { map[o.value] = o.label })
  return map
})

/** 新建（NEW）为初始状态：流转出去后不允许再切回，仅当前仍处于新建时保留该选项 */
const selectableStatusOptions = computed(() =>
  detail.value.status === 'NEW'
    ? statusOptions.value
    : statusOptions.value.filter((s: any) => s.value !== 'NEW')
)

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
    fieldValues.value = { ...(detail.value.customFields || {}) }
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

/** 点击缺陷标题：进入行内编辑（任意模式下可编辑） */
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
    ElMessage.warning('缺陷标题不能为空')
    return
  }
  if (title === (detail.value.title || '')) return
  try {
    await updateDefect(projectId.value, defectId.value, { title })
    ElMessage.success('已保存')
    await fetchDetail()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
    await fetchDetail()
  }
}

/** 进入编辑态：以当前详情初始化内容表单 */
function startEdit() {
  if (!detail.value.id) { ElMessage.warning('缺陷加载中，请稍后再试'); return }
  form.content = detail.value.content || ''
  editing.value = true
}

/** 取消编辑：丢弃本次修改（表单值在下次进入编辑态时重新初始化） */
function handleCancelEdit() {
  editing.value = false
}

/** 保存编辑：仅提交内容，保存后退出编辑态并刷新详情，停留本页 */
async function handleSave() {
  saving.value = true
  try {
    await updateDefect(projectId.value, defectId.value, {
      content: form.content,
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

/** 字段信息直接编辑：动态字段变更即保存 */
async function handleFieldSave() {
  const payload: any = { customFields: { ...fieldValues.value } }
  try {
    await updateDefect(projectId.value, defectId.value, payload)
    ElMessage.success('已保存')
    await fetchDetail()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
    await fetchDetail()
  }
}

/** 字段信息直接编辑：所属分组变更即保存 */
async function handleGroupChange(val: number | string | undefined) {
  const groupId = typeof val === 'number' ? val : null
  if (groupId === (detail.value.groupId ?? null)) return
  try {
    await updateDefect(projectId.value, defectId.value, { groupId: groupId ?? undefined })
    ElMessage.success('已保存')
    await fetchDetail()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
    await fetchDetail()
  }
}

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
  if (targetStatus === detail.value.status) return
  try {
    await transitionDefectStatus(projectId.value, defectId.value, { targetStatus })
    ElMessage.success('状态更新成功')
    fetchDetail()
  } catch { ElMessage.error('操作失败') }
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
  fetchGroups()
  fetchEditFields()
})
</script>

<template>
  <div class="defect-detail-page">
    <PageHeader :title="detail.defectNo || '缺陷详情'">
      <!-- 编号右侧：标题（查看态文本 / 编辑态输入框） -->
      <template #title-suffix>
        <el-input
          v-if="titleEditing"
          ref="titleInputRef"
          v-model="form.title"
          class="header-title-input"
          placeholder="请输入缺陷标题"
          maxlength="500"
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
      <template v-if="!editing">
        <el-button type="danger" @click="handleDelete">删除</el-button>
      </template>
    </PageHeader>

    <div class="detail-layout">
      <!-- 左侧主信息 -->
      <div v-loading="loading" class="detail-main">
        <div class="detail-card">
          <div class="detail-header">
            <div class="detail-meta">
              <span class="meta-item">创建人：{{ detail.createdByName || '-' }}</span>
              <span class="meta-item">创建时间：{{ detail.createdAt }}</span>
              <el-select
                v-if="!editing"
                class="meta-status"
                :model-value="detail.status"
                style="width: 110px"
                @change="(val: string) => handleTransition(val)"
              >
                <el-option v-for="s in selectableStatusOptions" :key="s.value" :value="s.value" :label="s.label" />
              </el-select>
            </div>
          </div>

          <!-- 内容（编辑态可编辑，编辑/取消/保存在本模块标题行；标题在页头编号右侧编辑） -->
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
              <div class="detail-actions">
                <template v-if="editing">
                  <el-button @click="handleCancelEdit">取消</el-button>
                  <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
                </template>
                <el-button v-else type="primary" @click="startEdit">编辑</el-button>
              </div>
            </div>
            <div class="editor-wrapper">
              <!-- 工具栏仅编辑模式显示；v-show 保持 DOM 以兼容全屏（工具栏与编辑区需同父级） -->
              <Toolbar v-show="editing" :editor="editorRef" :default-config="editorConfig" mode="default" style="border-bottom: 1px solid #ccc" />
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

          <!-- 字段信息（直接编辑、变更即保存：所属分组 + 动态字段） -->
          <div class="detail-block">
            <div class="block-title">字段信息</div>
            <el-form label-position="top">
              <DynamicFieldGrid
                v-if="editFields.length > 0"
                :fields="editFields"
                :model-value="fieldValues"
                @update:model-value="fieldValues = $event"
                @field-change="handleFieldSave"
              >
                <template #prepend>
                  <el-form-item label="所属分组">
                    <el-select :model-value="detail.groupId ?? null" placeholder="未分组" clearable filterable style="width: 100%" @change="handleGroupChange">
                      <el-option v-for="g in userGroups" :key="g.id" :value="g.id" :label="g.name" />
                    </el-select>
                  </el-form-item>
                </template>
              </DynamicFieldGrid>
              <el-form-item v-else label="所属分组">
                <el-select :model-value="detail.groupId ?? null" placeholder="未分组" clearable filterable style="width: 100%" @change="handleGroupChange">
                  <el-option v-for="g in userGroups" :key="g.id" :value="g.id" :label="g.name" />
                </el-select>
              </el-form-item>
            </el-form>
            <div class="field-item"><span class="field-label">重新打开次数：</span><span>{{ detail.reopenCount ?? 0 }}</span></div>
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
/* 页面撑满可视主区：相对 .app-main-wrapper（Layout 中 position: relative）绝对定位，
   不依赖中间层 flex 高度的百分比解析；卡片样式内聚到本页，覆盖同位的 .app-main */
.defect-detail-page {
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
  display: flex;
  align-items: center;
  justify-content: space-between;
}
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
/* 页头编号右侧标题：查看态文本 */
.header-title-text {
  font-size: 15px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 页头标题：任意模式下点击即行内编辑 */
.header-title-editable {
  cursor: pointer;
}
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
.detail-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.meta-item {
  font-size: 13px;
  color: #909399;
}
/* 状态流程下拉框固定在「创建人/创建时间」行右侧 */
.meta-status {
  margin-left: auto;
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
.history-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  /* 超出 tab 内容区时内部滚动（与评论列表行为一致）；父级 el-tab-pane 的 height:100% 使本链成立 */
  height: 100%;
  overflow-y: auto;
  /* 滚到边界时不再联动外层滚动 */
  overscroll-behavior: contain;
}
/* 无变更记录时「暂无变更记录」垂直居中 */
.history-list .el-empty:only-child {
  margin: auto;
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
