<!--
 @author HXN
 @date 2026-08-30
 @description 通用评论面板（支持评论、回复、删除）
-->
<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getComments, createComment, deleteComment } from '@/api/comment'
import type { CommentItem, CommentCreateRequest } from '@/api/comment'
import { useUserStore } from '@/stores'

const props = defineProps<{
  bizType: string
  bizId: number | null | undefined
}>()

const userStore = useUserStore()
const loading = ref(false)
const comments = ref<CommentItem[]>([])
const replyTarget = ref<CommentItem | null>(null)
const commentText = ref('')
const submitting = ref(false)

const canDelete = (item: CommentItem) => {
  return userStore.isAdmin || (userStore.userId && item.createdBy === userStore.userId)
}

async function fetchComments() {
  if (!props.bizId) {
    comments.value = []
    return
  }
  loading.value = true
  try {
    const res: any = await getComments(props.bizType, props.bizId)
    comments.value = res.data || []
  } catch {
    comments.value = []
  } finally {
    loading.value = false
  }
}

function handleReply(item: CommentItem) {
  replyTarget.value = item
  commentText.value = ''
}

function cancelReply() {
  replyTarget.value = null
  commentText.value = ''
}

async function handleSubmit() {
  const content = commentText.value.trim()
  if (!content) {
    ElMessage.warning('请输入评论内容')
    return
  }
  if (!props.bizId) return

  submitting.value = true
  try {
    const data: CommentCreateRequest = {
      bizType: props.bizType,
      bizId: props.bizId,
      content,
      parentId: replyTarget.value?.id ?? null,
    }
    await createComment(data)
    ElMessage.success(replyTarget.value ? '回复成功' : '评论成功')
    commentText.value = ''
    replyTarget.value = null
    await fetchComments()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '发表评论失败')
  } finally {
    submitting.value = false
  }
}

function handleDelete(item: CommentItem) {
  ElMessageBox.confirm('确定删除该评论？关联的回复也将一并删除。', '确认删除', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消',
  })
    .then(async () => {
      try {
        await deleteComment(item.id)
        ElMessage.success('删除成功')
        await fetchComments()
      } catch (e: any) {
        ElMessage.error(e?.response?.data?.message || '删除失败')
      }
    })
    .catch(() => {})
}

function formatTime(time: string | null | undefined) {
  if (!time) return '-'
  return time.replace('T', ' ').substring(0, 16)
}

watch(() => props.bizId, fetchComments, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="comment-panel">
    <div class="comment-list">
      <div v-for="item in comments" :key="item.id" class="comment-item">
        <div class="comment-header">
          <span class="comment-author">{{ item.createdByName || '匿名用户' }}</span>
          <span class="comment-time">{{ formatTime(item.createdAt) }}</span>
        </div>
        <div class="comment-content">{{ item.content }}</div>
        <div class="comment-actions">
          <el-button type="primary" link size="small" @click="handleReply(item)">回复</el-button>
          <el-button v-if="canDelete(item)" type="danger" link size="small" @click="handleDelete(item)">删除</el-button>
        </div>

        <div v-if="item.children && item.children.length > 0" class="comment-children">
          <div v-for="child in item.children" :key="child.id" class="comment-child-item">
            <div class="comment-header">
              <span class="comment-author">{{ child.createdByName || '匿名用户' }}</span>
              <span class="comment-time">{{ formatTime(child.createdAt) }}</span>
            </div>
            <div class="comment-content">{{ child.content }}</div>
            <div class="comment-actions">
              <el-button type="primary" link size="small" @click="handleReply(item)">回复</el-button>
              <el-button v-if="canDelete(child)" type="danger" link size="small" @click="handleDelete(child)">删除</el-button>
            </div>
          </div>
        </div>
      </div>

      <el-empty v-if="!loading && comments.length === 0" description="暂无评论" />
    </div>

    <div class="comment-input-area">
      <div v-if="replyTarget" class="reply-hint">
        回复 <strong>{{ replyTarget.createdByName || '匿名用户' }}</strong>
        <el-button type="primary" link size="small" @click="cancelReply">取消回复</el-button>
      </div>
      <el-input
        v-model="commentText"
        type="textarea"
        :rows="3"
        :placeholder="replyTarget ? '请输入回复内容' : '发表评论...'"
        maxlength="2000"
        show-word-limit
      />
      <div class="comment-submit">
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ replyTarget ? '回复' : '评论' }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.comment-panel {
  padding: 4px 0;
}
.comment-input-area {
  margin-top: 16px;
}
.reply-hint {
  margin-bottom: 8px;
  color: #606266;
  font-size: 13px;
}
.comment-submit {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
.comment-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.comment-item {
  padding: 12px;
  background: #f5f7fa;
  border-radius: 6px;
}
.comment-child-item {
  margin-top: 12px;
  padding: 10px;
  background: #fff;
  border-radius: 4px;
  border-left: 3px solid #409eff;
}
.comment-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.comment-author {
  font-weight: 600;
  color: #303133;
}
.comment-time {
  color: #909399;
  font-size: 12px;
}
.comment-content {
  color: #606266;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.comment-actions {
  margin-top: 8px;
}
</style>
