<!--
 @author HXN
 @date 2026-08-30
 @description 通用变更记录面板
-->
<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { getChangeLogs } from '@/api/changeLog'
import type { ChangeLogItem } from '@/api/changeLog'

const props = defineProps<{
  bizType: string
  bizId: number | null | undefined
  fieldName?: string
  fieldLabelMap?: Record<string, string>
  valueLabelMap?: Record<string, Record<string, string>>
}>()

const loading = ref(false)
const logs = ref<ChangeLogItem[]>([])

const effectiveFieldLabelMap = computed(() => ({
  title: '标题',
  description: '描述',
  reqType: '需求类型',
  priority: '优先级',
  status: '状态',
  assignee: '负责人',
  deadline: '截止日期',
  content: '内容',
  preconditions: '前置条件',
  operationSteps: '操作步骤',
  expectedResult: '预期结果',
  caseType: '用例类型',
  groupId: '所属分组',
  runInTestEnv: '测试环境执行',
  runInProdEnv: '生产环境执行',
  caseStatus: '用例状态',
  ...props.fieldLabelMap,
}))

function getFieldLabel(fieldName: string) {
  return (effectiveFieldLabelMap.value as Record<string, string>)[fieldName] || fieldName
}

/** 去除富文本标签保留纯文本（富文本字段变更值展示用） */
function stripHtml(html: string): string {
  return (html || '').replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim()
}

function getValueLabel(fieldName: string, value: string | null | undefined) {
  if (value === null || value === undefined || value === '') return '空'
  const map = props.valueLabelMap?.[fieldName]
  if (map && map[value] !== undefined) return map[value]
  // 富文本字段（如用例/缺陷"内容"）变更值去标签截断展示，避免渲染原始 HTML
  if (fieldName === 'content') {
    const text = stripHtml(value)
    return text.length > 100 ? `${text.substring(0, 100)}...` : text
  }
  return value
}

async function fetchLogs() {
  if (!props.bizId) {
    logs.value = []
    return
  }
  loading.value = true
  try {
    const res: any = await getChangeLogs(props.bizType, props.bizId, props.fieldName)
    logs.value = res.data || []
  } catch {
    logs.value = []
  } finally {
    loading.value = false
  }
}

function formatTime(time: string | null | undefined) {
  if (!time) return '-'
  return time.replace('T', ' ').substring(0, 16)
}

watch(() => [props.bizId, props.fieldName], fetchLogs, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="change-log-panel">
    <div v-if="logs.length > 0" class="change-log-list">
      <div v-for="log in logs" :key="log.id" class="change-log-item">
        <div class="change-log-header">
          <span class="change-log-author">{{ log.createdByName || '系统' }}</span>
          <span class="change-log-time">{{ formatTime(log.createdAt) }}</span>
        </div>
        <div class="change-log-content">
          <span class="field-label">{{ getFieldLabel(log.fieldName) }}</span>
          <span class="value old">{{ getValueLabel(log.fieldName, log.oldValue) }}</span>
          <span class="arrow">→</span>
          <span class="value new">{{ getValueLabel(log.fieldName, log.newValue) }}</span>
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无变更记录" />
  </div>
</template>

<style scoped>
.change-log-panel {
  padding: 4px 0;
}
.change-log-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.change-log-item {
  padding: 12px;
  background: #f5f7fa;
  border-radius: 6px;
}
.change-log-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.change-log-author {
  font-weight: 600;
  color: #303133;
}
.change-log-time {
  color: #909399;
  font-size: 12px;
}
.change-log-content {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  color: #606266;
}
.field-label {
  font-weight: 500;
  color: #303133;
  min-width: 80px;
}
.value {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}
.value.old {
  background: #fef0f0;
  color: #f56c6c;
}
.value.new {
  background: #f0f9ff;
  color: #409eff;
}
.arrow {
  color: #909399;
  font-weight: 600;
}
</style>
