<!--
 @author HXN
 @date 2026-08-30
 @description 新建缺陷视图
-->
<script setup lang="ts">
/**
 * 新建缺陷
 * 表单：标题、内容（富文本）、字段信息（所属分组 + 【字段管理】动态字段）
 * 动态字段按当前项目【字段管理】中【缺陷-新建缺陷】的字段列表渲染
 * 编辑已有缺陷请使用缺陷详情页（查看/编辑模式切换）
 */
import { ref, reactive, onMounted, computed, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createDefect, getDefectGroups } from '@/api/defect'
import { getCustomFieldsForRender } from '@/api/customField'
import PageHeader from '@/components/PageHeader/index.vue'
import DynamicFieldGrid from '@/components/DynamicFieldGrid/index.vue'
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
    const payload = { ...form, customFields: { ...customFieldValues.value } }
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
      </el-form>
    </div>
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
</style>
