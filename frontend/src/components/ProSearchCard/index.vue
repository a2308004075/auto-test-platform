<!--
 @author HXN
 @date 2026-08-21 15:30
 @description 增强搜索卡片组件
-->
<script setup lang="ts">
/**
 * 高级搜索折叠卡
 * 筛选项与操作按钮分栏，筛选项按每行最多 3 个分排（超出自动换行）；
 * 无折叠行时，若默认筛选项超过一行则自动折叠并显示展开按钮
 */
import { ref, computed, useSlots, onMounted, onBeforeUnmount, Comment, Fragment, Text } from 'vue'
import type { VNode, FunctionalComponent } from 'vue'

interface Props {
  /** 是否折叠态（默认收起折叠行） */
  defaultCollapsed?: boolean
  /** 查询按钮 loading */
  loading?: boolean
  /** 查询按钮文案 */
  searchText?: string
  /** 重置按钮文案 */
  resetText?: string
}

const props = withDefaults(defineProps<Props>(), {
  defaultCollapsed: true,
  loading: false,
  searchText: '查询',
  resetText: '重置',
})

const emit = defineEmits<{
  (e: 'search'): void
  (e: 'reset'): void
}>()

const slots = useSlots()
const hasCollapse = computed(() => !!slots.collapse)
const expanded = ref(!props.defaultCollapsed)

// ===== 筛选项分行：一行最多 3 个 =====

/** 每行最多展示的筛选项数量 */
const FIELDS_PER_ROW = 3

/** 行渲染器：将一行内的筛选项 vnode 平铺渲染（Fragment） */
const RowRender: FunctionalComponent<{ nodes: VNode[] }> = (rowProps) => rowProps.nodes

/** 是否为真实的筛选项节点（排除注释与空白文本节点） */
function isRealField(node: VNode): boolean {
  if (node.type === Comment) return false
  if (node.type === Text) return String(node.children ?? '').trim() !== ''
  return true
}

/** 递归收集插槽 vnode 中的筛选项（展开 Fragment，兼容 v-for 等动态节点） */
function collectFields(input: unknown, result: VNode[]): void {
  if (Array.isArray(input)) {
    input.forEach((item) => collectFields(item, result))
    return
  }
  const node = input as VNode | null | undefined
  if (!node || typeof node !== 'object') return
  if (node.type === Fragment) {
    collectFields(node.children, result)
    return
  }
  if (isRealField(node)) {
    result.push(node)
  }
}

/** 将插槽内容按每行 3 个切分为多行，保证筛选栏一行最多显示 3 个筛选项 */
function chunkRows(nodes: VNode[] | undefined): VNode[][] {
  const fields: VNode[] = []
  collectFields(nodes, fields)
  const rows: VNode[][] = []
  for (let i = 0; i < fields.length; i += FIELDS_PER_ROW) {
    rows.push(fields.slice(i, i + FIELDS_PER_ROW))
  }
  return rows
}

const hasOverflow = ref(false)
const everHadOverflow = ref(false)
const fieldsRef = ref<HTMLElement>()

function toggleExpand() {
  expanded.value = !expanded.value
}
function onSearch() {
  emit('search')
}
function onReset() {
  emit('reset')
}

function checkOverflow() {
  const el = fieldsRef.value
  if (!el) return
  if (expanded.value || hasCollapse.value) {
    hasOverflow.value = false
    return
  }
  const overflow = el.scrollHeight > el.clientHeight + 1
  hasOverflow.value = overflow
  if (overflow) {
    everHadOverflow.value = true
  }
}

let ro: ResizeObserver | null = null
onMounted(() => {
  if (!fieldsRef.value) return
  ro = new ResizeObserver(() => checkOverflow())
  ro.observe(fieldsRef.value)
  checkOverflow()
})
onBeforeUnmount(() => {
  ro?.disconnect()
})
</script>

<template>
  <div class="pro-search-card">
    <div class="pro-search-main">
      <div
        ref="fieldsRef"
        class="pro-search-fields"
        :class="{ collapsed: !expanded && !hasCollapse }"
      >
        <div v-for="(row, idx) in chunkRows(slots.default?.())" :key="idx" class="pro-search-row">
          <RowRender :nodes="row" />
        </div>
      </div>
      <div class="pro-search-actions">
        <el-button type="primary" :loading="loading" @click="onSearch">{{ searchText }}</el-button>
        <el-button @click="onReset">{{ resetText }}</el-button>
        <el-button v-if="hasCollapse || everHadOverflow" link @click="toggleExpand">
          <span class="arrow" :class="{ expanded }">▾</span>
          <span>{{ expanded ? '收起' : '展开' }}</span>
        </el-button>
      </div>
    </div>
    <div v-show="expanded && hasCollapse" class="pro-search-collapse">
      <div v-for="(row, idx) in chunkRows(slots.collapse?.())" :key="idx" class="pro-search-row">
        <RowRender :nodes="row" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.pro-search-card {
  background: #fff;
  border: 1px solid var(--el-border-color-light, #ebeef5);
  border-radius: 6px;
  padding: 16px 20px;
  margin-bottom: 16px;
}
.pro-search-main {
  display: flex;
  align-items: flex-start;
  gap: 24px;
}
.pro-search-fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex: 1;
  min-width: 0;
}
.pro-search-fields.collapsed {
  max-height: 44px;
  overflow: hidden;
}
/* 单行容器：一行最多 3 个筛选项，放不下时在行内换行（窄屏优雅降级） */
.pro-search-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 12px 24px;
}
.pro-search-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.pro-search-collapse {
  display: flex;
  flex-direction: column;
  gap: 12px;
  border-top: 1px dashed var(--el-border-color-lighter, #f0f0f0);
  padding-top: 16px;
  margin-top: 12px;
}
.arrow {
  display: inline-block;
  transition: transform 0.2s;
  font-size: 12px;
  margin-right: 2px;
}
.arrow.expanded {
  transform: rotate(180deg);
}
/* 筛选项通用样式：父组件在 slot 内使用 .pro-search-field */
:deep(.pro-search-field) {
  display: flex;
  align-items: center;
  gap: 8px;
}
:deep(.pro-search-field > .pro-search-label) {
  font-size: 13px;
  color: var(--el-text-color-secondary, #606266);
  white-space: nowrap;
}
</style>
