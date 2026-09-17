<!--
 @author HXN
 @date 2026-08-31
 @description 需求条目编辑视图（新建/编辑）
-->
<script setup lang="ts">
/**
 * 需求条目编辑/新建
 * 表单：标题、描述、需求类型、优先级、状态、负责人、截止日期
 */
import { ref, reactive, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  getRequirementItem,
  createRequirementItem,
  updateRequirementItem,
} from '@/api/requirement'
import { getCustomFieldsForRender } from '@/api/customField'
import PageHeader from '@/components/PageHeader/index.vue'
import DynamicFieldGrid from '@/components/DynamicFieldGrid/index.vue'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))
const itemId = computed(() => route.params.itemId ? Number(route.params.itemId) : null)
const isNew = computed(() => !route.params.itemId)
/** 新建时通过 query 参数传入 versionId */
const versionId = computed(() => Number(route.query.versionId) || 0)

const loading = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

// 动态字段
const customFields = ref<any[]>([])
const customFieldValues = ref<Record<string, any>>({})

const form = reactive({
  title: '',
  description: '',
  reqType: 'FEATURE',
  priority: 'MEDIUM',
  status: 'PENDING',
  assignee: '',
  deadline: '',
})

const rules = reactive<FormRules>({
  title: [
    { required: true, message: '请输入需求标题', trigger: 'blur' },
    { max: 200, message: '需求标题长度不能超过 200 个字符', trigger: 'blur' },
  ],
})

async function fetchDetail() {
  if (isNew.value || !itemId.value) return
  loading.value = true
  try {
    const res: any = await getRequirementItem(itemId.value)
    const data = res.data
    if (data) {
      form.title = data.title || ''
      form.description = data.description || ''
      form.reqType = data.reqType || 'FEATURE'
      form.priority = data.priority || 'MEDIUM'
      form.status = data.status || 'PENDING'
      form.assignee = data.assignee || ''
      form.deadline = data.deadline || ''
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '加载需求详情失败')
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  formRef.value?.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      const payload = {
        title: form.title,
        description: form.description || undefined,
        reqType: form.reqType,
        priority: form.priority,
        status: form.status,
        assignee: form.assignee || undefined,
        deadline: form.deadline || undefined,
        customFields: { ...customFieldValues.value },
      }
      if (isNew.value) {
        if (!versionId.value) {
          ElMessage.warning('缺少版本信息，请从版本列表中新建需求')
          return
        }
        await createRequirementItem(versionId.value, payload)
        ElMessage.success('创建成功')
      } else if (itemId.value) {
        await updateRequirementItem(itemId.value, payload)
        ElMessage.success('保存成功')
      }
      router.push(`/project/${projectId.value}/requirements`)
    } catch (e: any) {
      ElMessage.error(e?.response?.data?.message || '保存失败')
    } finally {
      saving.value = false
    }
  })
}

function handleCancel() {
  router.push(`/project/${projectId.value}/requirements`)
}

onMounted(() => {
  fetchDetail()
  fetchCustomFields()
})

async function fetchCustomFields() {
  try {
    const viewType = isNew.value ? 'create' : 'edit'
    const res: any = await getCustomFieldsForRender({
      projectId: projectId.value,
      module: 'requirement',
      viewType,
    })
    customFields.value = res.data || []
    // 初始化默认值
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
</script>

<template>
  <div>
    <PageHeader :title="isNew ? '新建需求' : '编辑需求'">
      <el-button @click="handleCancel">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </PageHeader>

    <div v-loading="loading" class="edit-form">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <!-- 基本信息 -->
        <div class="form-section">
          <div class="form-section-title">基本信息</div>
          <el-form-item label="标题" prop="title">
            <el-input v-model="form.title" placeholder="请输入需求标题" maxlength="200" show-word-limit />
          </el-form-item>
          <el-form-item label="描述" prop="description">
            <el-input v-model="form.description" type="textarea" :rows="4" placeholder="需求详细描述" />
          </el-form-item>
        </div>

        <!-- 属性信息 -->
        <div class="form-section">
          <div class="form-section-title">属性信息</div>
          <div class="form-row">
            <el-form-item label="需求类型" style="flex: 1">
              <el-select v-model="form.reqType" placeholder="类型" style="width: 100%">
                <el-option label="功能" value="FEATURE" />
                <el-option label="优化" value="IMPROVEMENT" />
                <el-option label="Bug" value="BUG" />
              </el-select>
            </el-form-item>
            <el-form-item label="优先级" style="flex: 1">
              <el-select v-model="form.priority" placeholder="优先级" style="width: 100%">
                <el-option label="高" value="HIGH" />
                <el-option label="中" value="MEDIUM" />
                <el-option label="低" value="LOW" />
              </el-select>
            </el-form-item>
            <el-form-item label="状态" style="flex: 1">
              <el-select v-model="form.status" placeholder="状态" style="width: 100%">
                <el-option label="待处理" value="PENDING" />
                <el-option label="进行中" value="IN_PROGRESS" />
                <el-option label="已完成" value="COMPLETED" />
              </el-select>
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="负责人" style="flex: 1">
              <el-input v-model="form.assignee" placeholder="负责人" maxlength="50" />
            </el-form-item>
            <el-form-item label="截止日期" style="flex: 1">
              <el-date-picker
                v-model="form.deadline"
                type="date"
                placeholder="截止日期"
                format="YYYY-MM-DD"
                value-format="YYYY-MM-DD"
                style="width: 100%"
              />
            </el-form-item>
          </div>
        </div>

        <!-- 动态字段 -->
        <div v-if="customFields.length > 0" class="form-section">
          <div class="form-section-title">字段信息</div>
          <DynamicFieldGrid
            :fields="customFields"
            :model-value="customFieldValues"
            @update:model-value="customFieldValues = $event"
          />
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
.form-row {
  display: flex;
  gap: 16px;
}
.form-row > * {
  min-width: 0;
}
</style>
