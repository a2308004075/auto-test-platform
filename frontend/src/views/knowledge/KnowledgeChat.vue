<!--
 @author HXN
 @date 2026-09-15
 @description 知识库智能问答页（支持流式输出）
-->
<script setup lang="ts">
/**
 * 知识库智能问答
 * 左侧会话列表 + 右侧对话窗口，支持 SSE 流式输出和引用来源展示
 */
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader/index.vue'
import {
  getConversations,
  getConversationMessages,
  createConversation,
  deleteConversation,
  getStreamUrl,
  getDefaultKnowledgeBase,
  syncKnowledgeDocuments,
} from '@/api/knowledge'

const route = useRoute()
const projectId = computed(() => Number(route.params.id))
// 知识库 ID 不来自路由（菜单直达问答）：进入页面时获取/创建项目默认知识库
const kbId = ref<number | null>(null)

// ===== 会话管理 =====
const conversations = ref<any[]>([])
const currentConvId = ref<number | null>(null)
const messages = ref<any[]>([])
const inputMessage = ref('')
const sending = ref(false)
const chatContainer = ref<HTMLElement | null>(null)

async function loadConversations() {
  try {
    const res: any = await getConversations(projectId.value, kbId.value!)
    conversations.value = res.data || res || []
  } catch (e: any) {
    // 静默
  }
}

async function loadMessages(convId: number) {
  try {
    const res: any = await getConversationMessages(projectId.value, kbId.value!, convId)
    messages.value = res.data || res || []
    await nextTick()
    scrollToBottom()
  } catch (e: any) {
    ElMessage.error('加载消息失败')
  }
}

async function selectConversation(conv: any) {
  currentConvId.value = conv.id
  await loadMessages(conv.id)
}

async function createNewConversation() {
  // 空对话不允许重复创建：已存在消息数为 0 的会话时直接选中复用
  const emptyConv = conversations.value.find((c: any) => (c.messageCount || 0) === 0)
  if (emptyConv) {
    await selectConversation(emptyConv)
    return
  }
  try {
    const res: any = await createConversation(projectId.value, kbId.value!)
    const conv = res.data || res
    currentConvId.value = conv.id
    messages.value = []
    await loadConversations()
  } catch (e: any) {
    ElMessage.error('创建会话失败')
  }
}

async function handleDeleteConversation(conv: any) {
  try {
    await deleteConversation(projectId.value, kbId.value!, conv.id)
    if (currentConvId.value === conv.id) {
      currentConvId.value = null
      messages.value = []
    }
    await loadConversations()
  } catch (e: any) {
    ElMessage.error('删除会话失败')
  }
}

// ===== 发送消息（流式） =====
async function sendMessage() {
  const msg = inputMessage.value.trim()
  if (!msg || sending.value) return

  sending.value = true
  inputMessage.value = ''

  // 添加用户消息到界面
  messages.value.push({ role: 'user', content: msg })
  await nextTick()
  scrollToBottom()

  // 添加助手占位消息
  const assistantMsg = { role: 'assistant', content: '', sources: [], _streaming: true }
  messages.value.push(assistantMsg)

  try {
    const url = getStreamUrl(projectId.value, kbId.value!)
    const token = localStorage.getItem('token') || ''

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({
        conversationId: currentConvId.value,
        message: msg,
        topK: 5,
        stream: true,
      }),
    })

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }

    const reader = response.body?.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    if (reader) {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        // SSE 事件以空行分隔；按事件块解析，块内区分 event/data 行
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
        const blocks = buffer.split('\n\n')
        buffer = blocks.pop() || ''

        for (const block of blocks) {
          if (block.trim()) {
            await handleSseEvent(parseSseBlock(block), assistantMsg)
          }
        }
      }
      // 流关闭时处理残留的未终止事件块
      if (buffer.trim()) {
        await handleSseEvent(parseSseBlock(buffer), assistantMsg)
      }
    }

    assistantMsg._streaming = false

    // 刷新会话列表（标题/计数已更新，消息与来源已由事件推送）
    await loadConversations()

  } catch (e: any) {
    assistantMsg._streaming = false
    assistantMsg.content = assistantMsg.content || `请求失败: ${e.message || '网络错误'}`
    ElMessage.error('发送失败')
  } finally {
    sending.value = false
  }
}

/** 解析单个 SSE 事件块为 { event, data } */
function parseSseBlock(block: string): { event: string; data: string } {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.substring(6).trim()
    } else if (line.startsWith('data:')) {
      // 按 SSE 规范仅去掉一个前导空格，保留内容原始格式（含换行拼接）
      const payload = line.substring(5)
      dataLines.push(payload.startsWith(' ') ? payload.substring(1) : payload)
    }
  }
  return { event, data: dataLines.join('\n') }
}

/** 处理后端推送的 SSE 事件（meta/token/sources/done/error） */
async function handleSseEvent(evt: { event: string; data: string }, assistantMsg: any) {
  if (evt.event === 'token') {
    if (evt.data) {
      assistantMsg.content += evt.data
      await nextTick()
      scrollToBottom()
    }
  } else if (evt.event === 'meta') {
    // 首次对话时后端自动创建会话，提前同步会话 ID
    try {
      const meta = JSON.parse(evt.data)
      if (meta.conversationId && currentConvId.value == null) {
        currentConvId.value = meta.conversationId
      }
    } catch { /* ignore */ }
  } else if (evt.event === 'sources') {
    try {
      assistantMsg.sources = JSON.parse(evt.data)
    } catch { /* ignore */ }
  } else if (evt.event === 'done') {
    assistantMsg._streaming = false
    const convId = Number(evt.data)
    if (evt.data && !Number.isNaN(convId) && currentConvId.value == null) {
      currentConvId.value = convId
    }
  } else if (evt.event === 'error') {
    assistantMsg._streaming = false
    assistantMsg.content = assistantMsg.content || `回答生成失败: ${evt.data || '未知错误'}`
    ElMessage.error('回答生成失败')
  }
}

function handleKeyDown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

function scrollToBottom() {
  if (chatContainer.value) {
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight
  }
}

// ===== 手动同步项目资料到知识库 =====
const syncing = ref(false)
async function handleSync() {
  if (syncing.value || kbId.value == null) return
  syncing.value = true
  try {
    await syncKnowledgeDocuments(projectId.value, kbId.value)
    ElMessage.success('资料同步已开始，完成后即可基于最新资料问答')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '同步失败')
  } finally {
    syncing.value = false
  }
}

onMounted(async () => {
  // 先解析项目默认知识库（不存在时后端自动创建并触发资料全量同步），再加载会话
  try {
    const res: any = await getDefaultKnowledgeBase(projectId.value)
    const kb = res.data || res
    kbId.value = kb.id
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '加载知识库失败')
    return
  }
  await loadConversations()
  if (conversations.value.length > 0) {
    await selectConversation(conversations.value[0])
  }
})
</script>

<template>
  <div class="chat-layout">
    <PageHeader title="智能问答">
      <el-button :loading="syncing" :disabled="kbId == null" @click="handleSync">同步资料</el-button>
    </PageHeader>

    <div class="chat-container">
      <!-- 左侧会话列表 -->
      <div class="conv-sidebar">
        <div class="conv-header">
          <span>对话列表</span>
          <el-button size="small" type="primary" link @click="createNewConversation">+ 新对话</el-button>
        </div>
        <div class="conv-list">
          <div
            v-for="conv in conversations"
            :key="conv.id"
            :class="['conv-item', { active: conv.id === currentConvId }]"
            @click="selectConversation(conv)"
          >
            <span class="conv-title">{{ conv.title || '新对话' }}</span>
            <el-button
              size="small"
              type="danger"
              link
              @click.stop="handleDeleteConversation(conv)"
            >x</el-button>
          </div>
          <div v-if="conversations.length === 0" class="conv-empty">暂无对话</div>
        </div>
      </div>

      <!-- 右侧对话窗口 -->
      <div class="chat-main">
        <!-- 消息区域 -->
        <div ref="chatContainer" class="chat-messages">
          <div v-if="messages.length === 0 && !currentConvId" class="chat-welcome">
            <h3>知识库智能问答</h3>
            <p>基于知识库文档内容，AI 将为您提供准确的回答。选择左侧对话或创建新对话开始。</p>
          </div>

          <div
            v-for="(msg, idx) in messages"
            :key="idx"
            :class="['message-item', msg.role]"
          >
            <div class="message-avatar">
              {{ msg.role === 'user' ? '我' : 'AI' }}
            </div>
            <div class="message-body">
              <div class="message-content" v-html="renderMarkdown(msg.content)" />
              <!-- 引用来源 -->
              <div v-if="msg.sources && msg.sources.length > 0 && !msg._streaming" class="message-sources">
                <el-collapse>
                  <el-collapse-item :title="`引用来源 (${msg.sources.length})`">
                    <div v-for="(src, si) in msg.sources" :key="si" class="source-item">
                      <div class="source-header">
                        <el-tag size="small">{{ src.docName }}</el-tag>
                        <span class="source-chunk">分块 #{{ src.chunkIndex }}</span>
                        <span v-if="src.score" class="source-score">
                          相似度: {{ (src.score * 100).toFixed(1) }}%
                        </span>
                      </div>
                      <div class="source-content">{{ src.content }}</div>
                    </div>
                  </el-collapse-item>
                </el-collapse>
              </div>
              <span v-if="msg._streaming" class="typing-cursor">|</span>
            </div>
          </div>
        </div>

        <!-- 输入区域 -->
        <div class="chat-input">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :rows="2"
            :disabled="sending"
            placeholder="输入问题，按 Enter 发送（Shift+Enter 换行）"
            @keydown="handleKeyDown"
          />
          <el-button
            type="primary"
            :loading="sending"
            :disabled="!inputMessage.trim()"
            @click="sendMessage"
          >
            {{ sending ? '思考中...' : '发送' }}
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
/** 简易 Markdown 渲染（加粗、换行、代码块） */
function renderMarkdown(text: string): string {
  if (!text) return ''
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\n/g, '<br>')
}
</script>

<style scoped>
.chat-layout { height: calc(100vh - 120px); display: flex; flex-direction: column; }
.chat-container { flex: 1; display: flex; border: 1px solid #ebeef5; border-radius: 8px; overflow: hidden; min-height: 0; }

/* 左侧会话列表 */
.conv-sidebar { width: 240px; border-right: 1px solid #ebeef5; display: flex; flex-direction: column; background: #fafafa; }
.conv-header { padding: 12px 16px; font-weight: 600; font-size: 14px; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #ebeef5; }
.conv-list { flex: 1; overflow-y: auto; }
.conv-item { padding: 10px 16px; cursor: pointer; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #f0f0f0; transition: background 0.15s; }
.conv-item:hover { background: #f0f2f5; }
.conv-item.active { background: #e8f4ff; }
.conv-title { font-size: 13px; color: #303133; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.conv-empty { padding: 24px; text-align: center; color: #909399; font-size: 13px; }

/* 右侧对话窗口 */
.chat-main { flex: 1; display: flex; flex-direction: column; }
.chat-messages { flex: 1; overflow-y: auto; padding: 16px; }

.chat-welcome { text-align: center; padding: 60px 40px; color: #909399; }
.chat-welcome h3 { color: #303133; margin-bottom: 8px; }

.message-item { display: flex; gap: 12px; margin-bottom: 16px; }
.message-item.user { flex-direction: row-reverse; }
.message-avatar { width: 32px; height: 32px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600; flex-shrink: 0; }
.message-item.user .message-avatar { background: #409eff; color: #fff; }
.message-item.assistant .message-avatar { background: #67c23a; color: #fff; }

.message-body { max-width: 70%; }
.message-content { padding: 10px 14px; border-radius: 12px; font-size: 14px; line-height: 1.6; word-break: break-word; }
.message-item.user .message-content { background: #409eff; color: #fff; border-top-right-radius: 4px; }
.message-item.assistant .message-content { background: #f5f7fa; color: #303133; border-top-left-radius: 4px; }
.message-content :deep(code) { background: rgba(0,0,0,0.06); padding: 2px 4px; border-radius: 3px; font-size: 13px; }

.typing-cursor { animation: blink 0.8s infinite; color: #409eff; }
@keyframes blink { 0%, 50% { opacity: 1; } 51%, 100% { opacity: 0; } }

/* 引用来源 */
.message-sources { margin-top: 8px; }
.source-item { padding: 8px 0; border-bottom: 1px solid #f0f0f0; }
.source-item:last-child { border-bottom: none; }
.source-header { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.source-chunk { font-size: 12px; color: #909399; }
.source-score { font-size: 12px; color: #67c23a; margin-left: auto; }
.source-content { font-size: 12px; color: #606266; line-height: 1.5; max-height: 80px; overflow: hidden; }

/* 输入区域 */
.chat-input { padding: 12px 16px; border-top: 1px solid #ebeef5; display: flex; gap: 8px; align-items: flex-end; }
.chat-input .el-textarea { flex: 1; }
</style>
