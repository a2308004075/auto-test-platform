<!--
 @author HXN
 @date 2026-09-23
 @description 内容模板编辑面板（【页面配置】左侧功能页下的「内容模板」叶子）
-->
<script setup lang="ts">
/**
 * 内容模板编辑面板（【页面配置】左侧 功能页 → 内容模板 叶子）
 * 按项目 + 业务类型（缺陷/手动用例/需求，由 bizType prop 指定）各维护一个"内容"富文本模板，
 * 直接内嵌富文本编辑框（不做列表管理；首次保存创建、之后保存更新，名称固定）。
 * 模板仅用于新建页：打开对应业务的新建页时自动填入「内容」编辑框
 */
import { ref, computed, watch, onMounted, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import { getContentTemplates, createContentTemplate, updateContentTemplate } from '@/api/contentTemplate'
import { useProjectStore } from '@/stores'
import { usePermission } from '@/composables/usePermission'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import '@wangeditor/editor/dist/css/style.css'

const props = defineProps<{
  /** 业务类型：defect-缺陷，manual_case-手动用例，requirement-需求 */
  bizType: string
}>()

const { hasPermission } = usePermission()
const projectStore = useProjectStore()

// ===== 当前项目（全局状态：从首页进入项目时设置） =====
const hasCurrentProject = computed(() => !!projectStore.currentProjectId)
const currentProjectId = computed(() => projectStore.currentProjectId)

// ===== 业务类型元信息（模板名称固定 + 提示文案） =====
const BIZ_TYPE_META: Record<string, { name: string; hint: string }> = {
  defect: { name: '缺陷模板', hint: '新建缺陷时自动填入「内容」编辑框' },
  manual_case: { name: '手动用例模板', hint: '新建手动用例时自动填入「内容」编辑框' },
  requirement: { name: '需求模板', hint: '新建需求时自动填入「内容」编辑框' },
}
const meta = computed(() => BIZ_TYPE_META[props.bizType] || { name: '内容模板', hint: '' })

// ===== 模板数据（本项目本类型唯一模板：首次保存创建、之后更新） =====
const loading = ref(false)
const saving = ref(false)
const templateId = ref<number | null>(null)
const content = ref('')

// 保存权限：新增或编辑任一即可（页面配置仅 ADMIN 可进入，权限码做细粒度兜底）
const canSave = computed(
  () =>
    hasPermission('system:page-config:template:add') ||
    hasPermission('system:page-config:template:edit'),
)

async function fetchTemplate() {
  if (!hasCurrentProject.value) {
    templateId.value = null
    content.value = ''
    return
  }
  loading.value = true
  try {
    const res: any = await getContentTemplates({
      projectId: currentProjectId.value,
      bizType: props.bizType,
    })
    // 每类型仅一个模板：按 bizType 精确匹配（后端已按类型过滤；此处再校验归属，
    // 防止接口返回混入其他类型记录时误读/误覆盖）
    const tpl = (res.data || []).find((x: any) => x.bizType === props.bizType) || null
    templateId.value = tpl ? tpl.id : null
    content.value = tpl ? tpl.content || '' : ''
  } catch {
    templateId.value = null
    content.value = ''
  } finally {
    loading.value = false
  }
}

// 当前项目变化（重新进入其他项目后返回本页）：重新加载模板内容
watch(
  () => projectStore.currentProjectId,
  () => {
    fetchTemplate()
  },
)

// ===== 保存（首次=创建，之后=更新；名称固定，归属当前项目与本类型） =====
async function handleSave() {
  if (!stripHtml(content.value)) {
    ElMessage.warning('请输入模板内容')
    return
  }
  saving.value = true
  try {
    const payload = {
      projectId: currentProjectId.value,
      bizType: props.bizType,
      name: meta.value.name,
      content: content.value,
    }
    if (templateId.value != null) {
      await updateContentTemplate(templateId.value, payload)
    } else {
      await createContentTemplate(payload)
    }
    ElMessage.success('模板保存成功')
    fetchTemplate()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 去除富文本标签，保留纯文本（富文本空标签视为空） */
function stripHtml(html: string): string {
  return (html || '')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

// ===== WangEditor（与缺陷"内容"同款配置：禁上传；工具栏不内置全屏，入口在「内容模板」标题旁） =====
const editorRef = shallowRef<any>(null)
// 全屏状态（全屏时「退出全屏」按钮浮动于编辑器上方）
const isFullscreen = ref(false)
const editorConfig = {
  placeholder: '请输入模板内容...',
  // Toolbar 组件直接以本对象为工具栏配置（读取顶层 excludeKeys）：全屏按钮已移至「内容模板」标题旁，工具栏不再内置
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

/** 切换编辑器全屏（入口为「内容模板」标题旁按钮） */
function toggleFullscreen() {
  const editor = editorRef.value
  if (!editor) return
  if (isFullscreen.value) editor.unFullScreen()
  else editor.fullScreen()
}

onMounted(fetchTemplate)
</script>

<template>
  <div class="ct-layout">
    <!-- 单模板编辑框（直接维护当前功能页的唯一模板） -->
    <div v-if="hasCurrentProject" v-loading="loading" class="ct-card ct-editor-card">
      <div class="ct-editor-head">
        <span class="ct-editor-head-left">
          <span class="ct-editor-title">内容模板</span>
          <el-button
            class="fullscreen-btn"
            :class="{ 'is-floating': isFullscreen }"
            size="small"
            @click="toggleFullscreen"
          >{{ isFullscreen ? '退出全屏' : '全屏' }}</el-button>
        </span>
        <el-button v-if="canSave" type="primary" :loading="saving" @click="handleSave">
          保存模板
        </el-button>
      </div>
      <div class="ct-editor-wrapper">
        <Toolbar
          :editor="editorRef"
          :default-config="editorConfig"
          mode="default"
          style="border-bottom: 1px solid #ccc"
        />
        <Editor
          v-model="content"
          :default-config="editorConfig"
          mode="default"
          style="height: 400px; overflow-y: hidden"
          @on-created="onEditorCreated"
        />
      </div>
    </div>

    <!-- 未选择项目引导 -->
    <div v-else class="ct-card ct-placeholder">
      <el-empty description="请先从首页进入项目，再使用页面配置" />
    </div>
  </div>
</template>

<style scoped>
.ct-layout {
  /* 直接位于右栏（无页签头）：高度基线 164 */
  min-height: calc(100vh - 164px);
  display: flex;
  flex-direction: column;
}

.ct-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 16px;
}

.ct-editor-card {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.ct-editor-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.ct-editor-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ct-editor-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

/* 全屏时「退出全屏」按钮浮动于编辑器上方（全屏遮罩 z-index: 3001） */
.fullscreen-btn.is-floating {
  position: fixed;
  top: 12px;
  right: 20px;
  z-index: 3002;
}

.ct-editor-wrapper {
  flex: 1;
  min-height: 0;
  border: 1px solid #ccc;
  border-radius: 4px;
  overflow: hidden;
}

/* 全屏时仅编辑器覆盖视口；wangeditor 全屏类无 z-index，需高于页签栏(3000)避免其他元素浮入 */
.ct-editor-wrapper.w-e-full-screen-container {
  z-index: 3001;
  background: #fff;
}

.ct-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
