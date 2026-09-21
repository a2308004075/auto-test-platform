<!--
 @author HXN
 @date 2026-08-30
 @description 新建缺陷视图
-->
<script setup lang="ts">
/**
 * 新建缺陷
 * 表单：标题、内容（富文本）、附件与关联（本页暂存，随创建一并提交）、字段信息（所属分组 + 【字段管理】动态字段）
 * 动态字段按当前项目【字段管理】中【缺陷-新建缺陷】的字段列表渲染
 * 编辑已有缺陷请使用缺陷详情页（查看/编辑模式切换）
 */
import { ref, reactive, onMounted, computed, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createDefect, getDefectGroups } from '@/api/defect'
import { getCustomFieldsForRender } from '@/api/customField'
import CaseSelectDialog from '@/components/CaseSelectDialog/index.vue'
import PageHeader from '@/components/PageHeader/index.vue'
import DynamicFieldGrid from '@/components/DynamicFieldGrid/index.vue'
import { useDict } from '@/composables/useDict'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import '@wangeditor/editor/dist/css/style.css'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))

const saving = ref(false)
const groups = ref<any[]>([])

// 动态字段（由【字段管理】按项目/模块/视图配置驱动）
const customFields = ref<any[]>([])
const customFieldValues = ref<Record<string, any>>({})

const form = reactive({
  groupId: null as number | null,
  title: '',
  content: '',
})

const userGroups = computed(() => groups.value.filter((g) => g.isSystem !== 1))

// 关联（本页暂存，保存时随缺陷创建一并提交；类型选项与缺陷详情页一致）
const { options: relationTypeOptions } = useDict('defect_relation_type')
const { options: targetTypeOptions } = useDict('defect_target_type')
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
const relations = ref<any[]>([])
const relationVisible = ref(false)
const relationForm = reactive({ relationType: 'RELATED', targetType: 'AUTO_CASE', targetId: undefined as number | undefined, targetTitle: '' })
// 用例类目标（手动/自动化用例）支持搜索选择，其余类型手动输入
const isCaseTarget = computed(() => ['MANUAL_CASE', 'AUTO_CASE'].includes(relationForm.targetType))
const caseSelectVisible = ref(false)

// 附件（本页暂存，保存时随缺陷创建一并提交；录入方式与缺陷详情页一致）
const attachments = ref<any[]>([])
const attachmentVisible = ref(false)
const attachmentForm = reactive({ fileName: '', fileUrl: '', fileSize: undefined as number | undefined })

// WangEditor
const editorRef = shallowRef<any>(null)
const editorConfig = {
  placeholder: '请输入缺陷内容...',
  MENU_CONF: {
    uploadImage: { disabled: true },
    uploadVideo: { disabled: true },
  },
}
function onEditorCreated(editor: any) { editorRef.value = editor }
function onEditorChange(editor: any) { form.content = editor.getHtml() }

async function fetchGroups() {
  try {
    const res: any = await getDefectGroups(projectId.value)
    groups.value = res.data || []
  } catch { groups.value = [] }
}

async function handleSave() {
  if (!form.title.trim()) {
    ElMessage.warning('请输入缺陷标题')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      customFields: { ...customFieldValues.value },
      // 待提交附件/关联列表（本页暂存，随创建一并保存）
      attachments: attachments.value,
      relations: relations.value,
    }
    await createDefect(projectId.value, payload)
    ElMessage.success('创建成功')
    router.push(`/project/${projectId.value}/defects`)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function handleCancel() {
  router.push(`/project/${projectId.value}/defects`)
}

// ===== 关联操作 =====

/** 切换目标类型：清空已选目标 */
function handleTargetTypeChange() {
  relationForm.targetId = undefined
  relationForm.targetTitle = ''
}

/** 用例选择弹窗确认（单选） */
function handleCaseConfirm(rows: Array<{ id: number; title: string }>) {
  if (rows.length === 0) return
  relationForm.targetId = rows[0].id
  relationForm.targetTitle = rows[0].title
}

/** 暂存关联到待提交列表（用例类目标本地防重复，与后端校验一致） */
function handleAddRelation() {
  if (!relationForm.targetId) {
    ElMessage.warning(isCaseTarget.value ? '请选择关联的用例' : '请输入关联目标 ID')
    return
  }
  if (isCaseTarget.value && relations.value.some((r) => r.targetType === relationForm.targetType && r.targetId === relationForm.targetId)) {
    ElMessage.warning('该用例已添加，请勿重复添加')
    return
  }
  relations.value.push({
    relationType: relationForm.relationType,
    targetType: relationForm.targetType,
    targetId: relationForm.targetId,
    targetTitle: relationForm.targetTitle,
  })
  relationVisible.value = false
  Object.assign(relationForm, { relationType: 'RELATED', targetType: 'AUTO_CASE', targetId: undefined, targetTitle: '' })
}

/** 移除待提交关联 */
function handleRemoveRelation(index: number) {
  relations.value.splice(index, 1)
}

// ===== 附件操作 =====

/** 暂存附件到待提交列表 */
function handleAddAttachment() {
  if (!attachmentForm.fileName || !attachmentForm.fileUrl) {
    ElMessage.warning('请填写文件名和链接')
    return
  }
  attachments.value.push({
    fileName: attachmentForm.fileName,
    fileUrl: attachmentForm.fileUrl,
    fileSize: attachmentForm.fileSize,
  })
  attachmentVisible.value = false
  Object.assign(attachmentForm, { fileName: '', fileUrl: '', fileSize: undefined })
}

/** 移除待提交附件 */
function handleRemoveAttachment(index: number) {
  attachments.value.splice(index, 1)
}

async function fetchCustomFields() {
  try {
    const res: any = await getCustomFieldsForRender({
      projectId: projectId.value,
      module: 'defect',
      viewType: 'create',
    })
    customFields.value = res.data || []
    // 初始化默认值（详情已回填的值优先）
    for (const field of customFields.value) {
      if (field.defaultValue !== null && field.defaultValue !== undefined && field.defaultValue !== '') {
        if (customFieldValues.value[field.fieldKey] === undefined) {
          customFieldValues.value[field.fieldKey] = field.defaultValue
        }
      }
    }
  } catch {
    customFields.value = []
  }
}

onMounted(() => {
  fetchGroups()
  fetchCustomFields()
})
</script>

<template>
  <div>
    <PageHeader title="新建缺陷">
      <el-button @click="handleCancel">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </PageHeader>

    <div class="edit-form">
      <el-form label-position="top" :model="form">
        <!-- 基本信息 -->
        <div class="form-section">
          <div class="form-section-title">基本信息</div>
          <el-form-item label="缺陷标题" required>
            <el-input v-model="form.title" placeholder="请输入缺陷标题" maxlength="500" show-word-limit />
          </el-form-item>
        </div>

        <!-- 缺陷内容 -->
        <div class="form-section">
          <div class="form-section-title">内容</div>
          <div class="editor-wrapper">
            <Toolbar
              :editor="editorRef"
              :default-config="editorConfig"
              mode="default"
              style="border-bottom: 1px solid #ccc"
            />
            <Editor
              v-model="form.content"
              :default-config="editorConfig"
              mode="default"
              style="height: 400px; overflow-y: hidden"
              @on-created="onEditorCreated"
              @on-change="onEditorChange"
            />
          </div>
        </div>

        <!-- 附件：本页暂存，保存时随缺陷一并提交（与缺陷详情页附件功能一致） -->
        <div class="form-section">
          <div class="form-section-title">附件</div>
          <div class="section-toolbar">
            <el-button type="primary" size="small" @click="attachmentVisible = true">添加附件</el-button>
          </div>
          <el-table :data="attachments" border stripe>
            <el-table-column prop="fileName" label="文件名" />
            <el-table-column prop="fileSize" label="大小（字节）" width="130" />
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button type="danger" link size="small" @click="handleRemoveAttachment($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 字段信息：所属分组 + 动态字段（【字段管理】配置驱动），统一每行 3 个排列 -->
        <div class="form-section">
          <div class="form-section-title">字段信息</div>
          <DynamicFieldGrid
            v-if="customFields.length > 0"
            :fields="customFields"
            :model-value="customFieldValues"
            @update:model-value="customFieldValues = $event"
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
        </div>

        <!-- 关联：本页暂存，保存时随缺陷一并提交（与缺陷详情页关联功能一致） -->
        <div class="form-section">
          <div class="form-section-title">关联</div>
          <div class="section-toolbar">
            <el-button type="primary" size="small" @click="relationVisible = true">添加关联</el-button>
          </div>
          <el-table :data="relations" border stripe>
            <el-table-column label="关联类型" width="120">
              <template #default="{ row }">{{ relationTypeLabelMap[row.relationType] || row.relationType }}</template>
            </el-table-column>
            <el-table-column label="目标类型" width="140">
              <template #default="{ row }">{{ targetTypeLabelMap[row.targetType] || row.targetType }}</template>
            </el-table-column>
            <el-table-column prop="targetId" label="目标 ID" width="100" />
            <el-table-column prop="targetTitle" label="目标标题" />
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button type="danger" link size="small" @click="handleRemoveRelation($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-form>
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
  </div>
</template>

<style scoped>
.edit-form {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 20px 24px;
}
.form-section {
  margin-bottom: 24px;
}
.form-section:last-child {
  margin-bottom: 0;
}
.form-section-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}
.editor-wrapper {
  border: 1px solid #ccc;
  border-radius: 4px;
  overflow: hidden;
}
.section-toolbar {
  margin-bottom: 12px;
}
</style>
