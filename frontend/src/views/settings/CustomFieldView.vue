<!--
 @author HXN
 @date 2026-08-30
 @description 字段管理视图
-->
<script setup lang="ts">
/**
 * 字段管理页面（仅 ADMIN）
 * 左侧层级树：项目（当前项目）→ 模块（固定 缺陷/需求）→ 视图（固定 新建/编辑）；
 * 只允许在视图层级（叶子节点）编辑字段；右侧字段列表，新增/编辑通过弹窗填写
 */
import { ref, reactive, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CaretRight, Rank } from '@element-plus/icons-vue'
import Sortable from 'sortablejs'
import { getCustomFields, createCustomField, updateCustomField, sortCustomFields, deleteCustomField } from '@/api/customField'
import { useProjectStore } from '@/stores'
import { usePermission } from '@/composables/usePermission'
import PageHeader from '@/components/PageHeader/index.vue'
import TableFit from '@/components/TableFit/index.vue'

const { hasPermission } = usePermission()
const projectStore = useProjectStore()

// ===== 当前项目（全局状态：从首页进入项目时设置） =====
const hasCurrentProject = computed(() => !!projectStore.currentProjectId)
const currentProjectId = computed(() => projectStore.currentProjectId)
const currentProjectName = computed(() => projectStore.currentProjectName)

// ===== 层级树：项目 → 模块 → 视图（模块与视图固定） =====
interface ViewNode {
  label: string
  viewType: string
}
interface ModuleNode {
  label: string
  module: string
  views: ViewNode[]
}

const moduleTree: ModuleNode[] = [
  {
    label: '缺陷',
    module: 'defect',
    views: [
      { label: '新建缺陷', viewType: 'create' },
      { label: '编辑缺陷', viewType: 'edit' },
    ],
  },
  {
    label: '需求',
    module: 'requirement',
    views: [
      { label: '新建需求', viewType: 'create' },
      { label: '编辑需求', viewType: 'edit' },
    ],
  },
]

// 树展开状态
const rootExpanded = ref(true)
const moduleExpanded = ref<Record<string, boolean>>({ defect: true, requirement: true })

function toggleRoot() {
  rootExpanded.value = !rootExpanded.value
}

function toggleModule(module: string) {
  moduleExpanded.value[module] = !moduleExpanded.value[module]
}

// ===== 选中视图（仅叶子节点可选；只有视图层级允许编辑字段） =====
const selectedModule = ref('')
const selectedViewType = ref('')

const selectedModuleLabel = computed(
  () => moduleTree.find((m) => m.module === selectedModule.value)?.label || ''
)
const selectedViewLabel = computed(() => {
  const m = moduleTree.find((x) => x.module === selectedModule.value)
  return m?.views.find((v) => v.viewType === selectedViewType.value)?.label || ''
})

// 当前项目 + 模块 + 视图选齐后右侧才可编辑
const viewReady = computed(
  () => hasCurrentProject.value && !!selectedModule.value && !!selectedViewType.value
)

const placeholderText = computed(() => {
  if (!hasCurrentProject.value) return '请先从首页进入项目，再使用字段管理'
  return '请在左侧选择具体视图（如"新建缺陷"）'
})

const fieldTypeOptions = [
  { label: '单行文本', value: 'text' },
  { label: '多行文本', value: 'textarea' },
  { label: '下拉框', value: 'select' },
  { label: '日期时间', value: 'datetime' },
  { label: '数字', value: 'number' },
  { label: '用户选择', value: 'user' },
  { label: '环境选择', value: 'environment' },
]

const fieldTypeLabelMap: Record<string, string> = {
  text: '单行文本',
  textarea: '多行文本',
  select: '下拉框',
  datetime: '日期时间',
  number: '数字',
  user: '用户选择',
  environment: '环境选择',
}

// ===== 列表数据 =====
const loading = ref(false)
const fieldList = ref<any[]>([])

// ===== 新增/编辑弹窗（共用表单，归属取当前项目与左侧选中视图，不可改） =====
const dialogVisible = ref(false)
const isEdit = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)

const form = reactive({
  fieldLabel: '',
  fieldType: 'text',
  description: '',
  optionsJson: '',
  defaultValue: '',
  isRequired: 0,
  sortNo: 0,
})

// 下拉框选项动态编辑（仅填显示文本，存储值保存时按选项顺序自动生成 1、2、3…）
const optionRows = ref<{ label: string }[]>([])
const isSelectType = computed(() => form.fieldType === 'select')

// 类型切换时清理互斥配置（枚举选项仅 select 用）
function handleFieldTypeChange() {
  if (form.fieldType !== 'select') {
    optionRows.value = []
    form.optionsJson = ''
  }
}

// ===== 加载字段列表 =====
async function fetchList() {
  if (!viewReady.value) {
    fieldList.value = []
    return
  }
  loading.value = true
  try {
    const res: any = await getCustomFields({
      projectId: currentProjectId.value,
      module: selectedModule.value,
      viewType: selectedViewType.value,
    })
    fieldList.value = res.data || []
  } catch {
    fieldList.value = []
  } finally {
    loading.value = false
  }
  // 表格行渲染完成后再绑定拖拽排序（仅绑定一次，切换视图时 tbody 复用）
  await nextTick()
  ensureSortable()
}

// ===== 左侧选择视图叶子 =====
function selectView(module: string, viewType: string) {
  if (selectedModule.value === module && selectedViewType.value === viewType) return
  selectedModule.value = module
  selectedViewType.value = viewType
  resetForm()
  fetchList()
}

// 当前项目变化（重新进入其他项目后返回本页）：清空选择与表单
watch(
  () => projectStore.currentProjectId,
  () => {
    selectedModule.value = ''
    selectedViewType.value = ''
    dialogVisible.value = false
    resetForm()
    fieldList.value = []
  },
)

// ===== 描述列展示：最多 20 个字符，超出以省略号结尾（悬浮显示完整内容） =====
function truncateDescription(text: string): string {
  return text.length > 20 ? text.slice(0, 20) + '...' : text
}

// ===== 表单 =====
function resetForm() {
  isEdit.value = false
  editingId.value = null
  form.fieldLabel = ''
  form.fieldType = 'text'
  form.description = ''
  form.optionsJson = ''
  form.defaultValue = ''
  form.isRequired = 0
  form.sortNo = 0
  optionRows.value = []
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: any) {
  isEdit.value = true
  editingId.value = row.id
  form.fieldLabel = row.fieldLabel
  form.fieldType = row.fieldType
  form.description = row.description || ''
  form.optionsJson = row.optionsJson || ''
  form.defaultValue = row.defaultValue || ''
  form.isRequired = row.isRequired || 0
  form.sortNo = row.sortNo || 0

  // 解析已有选项（仅取显示文本；存储值保存时按顺序重新生成）
  if (row.fieldType === 'select' && row.optionsJson) {
    try {
      const parsed = JSON.parse(row.optionsJson)
      optionRows.value = Array.isArray(parsed)
        ? parsed.map((o: any) => ({ label: String(o?.label ?? '') }))
        : []
    } catch {
      optionRows.value = []
    }
  } else {
    optionRows.value = []
  }

  dialogVisible.value = true
}

// ===== 选项操作 =====
function addOptionRow() {
  optionRows.value.push({ label: '' })
}

function removeOptionRow(index: number) {
  optionRows.value.splice(index, 1)
}

// ===== 提交（新增 / 保存修改） =====
async function handleSubmit() {
  if (!form.fieldLabel.trim()) {
    ElMessage.warning('请填写字段标签')
    return
  }
  if (!form.fieldType) {
    ElMessage.warning('请选择字段类型')
    return
  }

  // 构建 optionsJson（仅 select 类型；存储值按选项顺序自动生成 1、2、3…，0 保留为默认/未设置）
  if (isSelectType.value) {
    const validOptions = optionRows.value
      .filter((r) => r.label.trim())
      .map((r, index) => ({ label: r.label, value: String(index + 1) }))
    form.optionsJson = JSON.stringify(validOptions)
  } else {
    form.optionsJson = ''
  }

  // 归属（项目/模块/视图）由当前项目与左侧选中视图决定，不可在表单中修改
  const data = {
    projectId: currentProjectId.value,
    module: selectedModule.value,
    viewType: selectedViewType.value,
    ...form,
  }

  saving.value = true
  try {
    if (isEdit.value && editingId.value !== null) {
      await updateCustomField(editingId.value, data)
      ElMessage.success('字段更新成功')
    } else {
      await createCustomField(data)
      ElMessage.success('字段新增成功')
    }
    resetForm()
    dialogVisible.value = false
    fetchList()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  } finally {
    saving.value = false
  }
}

// ===== 拖拽排序（拖动行首手柄调整顺序，落点即保存；保存期间禁用拖拽防连点） =====
const canSort = computed(() => hasPermission('system:custom-field:edit'))
const tableRef = ref()
let sortable: Sortable | null = null

function ensureSortable() {
  if (!canSort.value || sortable) return
  const tbody: HTMLElement | null = tableRef.value?.$el?.querySelector('.el-table__body-wrapper tbody')
  if (!tbody) return
  sortable = Sortable.create(tbody, {
    handle: '.cf-drag-handle',
    animation: 150,
    ghostClass: 'cf-drag-ghost',
    onEnd: ({ oldIndex, newIndex }) => handleDragEnd(oldIndex, newIndex),
  })
}

function destroySortable() {
  if (sortable) {
    sortable.destroy()
    sortable = null
  }
}

async function handleDragEnd(oldIndex?: number, newIndex?: number) {
  if (oldIndex == null || newIndex == null || oldIndex === newIndex) return
  // 按拖拽结果重排本地数据（行 DOM 已由 sortable 调整）
  const list = [...fieldList.value]
  const [moved] = list.splice(oldIndex, 1)
  list.splice(newIndex, 0, moved)
  fieldList.value = list

  sortable?.option('disabled', true)
  try {
    await sortCustomFields(list.map((f: any) => f.id))
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '排序保存失败')
  } finally {
    // 成功与失败均重新拉取，保证列表顺序与后端 sortNo 一致
    await fetchList()
    sortable?.option('disabled', false)
  }
}

// 视图未选齐时表格卸载，同步销毁拖拽实例（避免持有已卸载的 tbody）
watch(viewReady, (ready) => {
  if (!ready) destroySortable()
})

onBeforeUnmount(destroySortable)

// ===== 删除 =====
async function handleDelete(row: any) {
  try {
    await ElMessageBox.confirm(`确定删除字段「${row.fieldLabel}」？`, '删除确认', {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteCustomField(row.id)
    ElMessage.success('删除成功')
    fetchList()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}
</script>

<template>
  <div>
    <PageHeader title="字段管理" />

    <div class="cf-layout">
      <!-- 左侧：项目 → 模块 → 视图 层级树 -->
      <div class="cf-panel">
        <!-- 根节点：当前项目 -->
        <div
          :class="['cf-node', 'cf-node-root', { 'is-empty': !hasCurrentProject }]"
          @click="hasCurrentProject && toggleRoot()"
        >
          <el-icon v-if="hasCurrentProject" :class="['cf-arrow', { expanded: rootExpanded }]">
            <CaretRight />
          </el-icon>
          <span class="cf-node-label">
            {{ hasCurrentProject ? currentProjectName : '未选择项目' }}
          </span>
        </div>

        <!-- 二级模块 → 三级视图（仅当前项目存在时展示） -->
        <template v-if="hasCurrentProject && rootExpanded">
          <div v-for="m in moduleTree" :key="m.module">
            <div class="cf-node cf-node-module" @click="toggleModule(m.module)">
              <el-icon :class="['cf-arrow', { expanded: moduleExpanded[m.module] }]">
                <CaretRight />
              </el-icon>
              <span class="cf-node-label">{{ m.label }}</span>
            </div>
            <div v-show="moduleExpanded[m.module]">
              <div
                v-for="v in m.views"
                :key="v.viewType"
                :class="[
                  'cf-node',
                  'cf-node-leaf',
                  { active: selectedModule === m.module && selectedViewType === v.viewType },
                ]"
                @click="selectView(m.module, v.viewType)"
              >
                <span class="cf-node-label">{{ v.label }}</span>
              </div>
            </div>
          </div>
        </template>
      </div>

      <!-- 右侧：字段列表（新增/编辑弹窗操作） -->
      <div class="cf-content">
        <template v-if="viewReady">
          <!-- 字段列表 -->
          <div class="cf-card cf-table-card">
            <div class="cf-table-head">
              <div class="cf-table-head-left">
                <span class="cf-table-title">字段列表</span>
                <span class="cf-table-count">共 {{ fieldList.length }} 个字段</span>
              </div>
              <el-button
                v-if="hasPermission('system:custom-field:add')"
                type="primary"
                @click="openCreate"
              >
                + 添加字段
              </el-button>
            </div>
            <TableFit>
              <template #default="{ maxHeight }">
                <el-table
                  ref="tableRef"
                  v-loading="loading"
                  :data="fieldList"
                  stripe
                  :max-height="maxHeight"
                  style="width: 100%;"
                >
                  <el-table-column v-if="canSort" width="40" align="center">
                    <template #default>
                      <span class="cf-drag-handle" title="拖动调整顺序">
                        <el-icon><Rank /></el-icon>
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="fieldLabel" label="字段标签" min-width="140" />
                  <el-table-column label="类型" width="100">
                    <template #default="{ row }">
                      {{ fieldTypeLabelMap[row.fieldType] || row.fieldType }}
                    </template>
                  </el-table-column>
                  <el-table-column label="必填" width="70" align="center">
                    <template #default="{ row }">
                      <el-tag v-if="row.isRequired" type="danger" size="small">是</el-tag>
                      <el-tag v-else size="small">否</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="描述" min-width="240">
                    <template #default="{ row }">
                      <el-tooltip
                        v-if="row.description"
                        :content="row.description"
                        placement="top"
                        :show-after="300"
                      >
                        <span class="cf-desc-text">{{ truncateDescription(row.description) }}</span>
                      </el-tooltip>
                      <span v-else class="cf-desc-empty">--</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="120" align="right" fixed="right">
                    <template #default="{ row }">
                      <el-button
                        v-if="hasPermission('system:custom-field:edit')"
                        link
                        type="primary"
                        size="small"
                        @click="openEdit(row)"
                      >
                        编辑
                      </el-button>
                      <el-button
                        v-if="hasPermission('system:custom-field:delete')"
                        link
                        type="danger"
                        size="small"
                        @click="handleDelete(row)"
                      >
                        删除
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </template>
            </TableFit>
          </div>
        </template>

        <!-- 未选齐引导 -->
        <div v-else class="cf-card cf-placeholder">
          <el-empty :description="placeholderText" />
        </div>
      </div>
    </div>

    <!-- 新增 / 编辑字段弹窗 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑字段' : '新增字段'" width="640px">
      <div class="cf-form-context">
        当前配置：{{ currentProjectName }} / {{ selectedModuleLabel }} / {{ selectedViewLabel }}
      </div>
      <el-form :model="form" label-width="90px">
        <div class="cf-form-grid">
          <el-form-item label="字段标签" required>
            <el-input v-model="form.fieldLabel" placeholder="如：严重程度" maxlength="50" />
          </el-form-item>
          <el-form-item label="字段类型" required>
            <el-select
              v-model="form.fieldType"
              placeholder="请选择类型"
              style="width: 100%"
              @change="handleFieldTypeChange"
            >
              <el-option v-for="t in fieldTypeOptions" :key="t.value" :value="t.value" :label="t.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="默认值">
            <el-input v-model="form.defaultValue" placeholder="默认值（可选）" maxlength="200" />
          </el-form-item>
          <el-form-item label="是否必填">
            <el-switch v-model="form.isRequired" :active-value="1" :inactive-value="0" />
          </el-form-item>
          <el-form-item label="排序号">
            <el-input-number v-model="form.sortNo" :min="0" :controls="false" style="width: 120px" />
          </el-form-item>

          <!-- 描述（可选，最多 200 字，跨两列） -->
          <el-form-item label="描述" class="span-2">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="3"
              placeholder="请输入描述（可选）"
              maxlength="200"
              show-word-limit
            />
          </el-form-item>

          <!-- 下拉框选项配置（跨两列；存储值由系统按顺序自动生成） -->
          <el-form-item v-if="isSelectType" label="枚举选项" class="span-2">
            <div class="option-rows">
              <div v-for="(row, index) in optionRows" :key="index" class="option-row">
                <el-input v-model="row.label" placeholder="显示文本" style="flex: 1" />
                <el-button link type="danger" @click="removeOptionRow(index)">删除</el-button>
              </div>
              <el-button type="primary" link @click="addOptionRow">+ 添加选项</el-button>
            </div>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">
          {{ isEdit ? '保存修改' : '添加字段' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cf-layout {
  display: flex;
  gap: 16px;
  min-height: calc(100vh - 164px);
}

/* 左侧层级树面板 */
.cf-panel {
  width: 220px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 树节点 */
.cf-node {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 8px;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
  cursor: pointer;
  user-select: none;
}
.cf-node:hover {
  background: #f5f7fa;
}
.cf-node-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 根节点：当前项目 */
.cf-node-root {
  padding-left: 6px;
  font-weight: 600;
  color: #303133;
}
.cf-node-root.is-empty {
  color: #c0c4cc;
  cursor: default;
}
.cf-node-root.is-empty:hover {
  background: transparent;
}

/* 模块节点 */
.cf-node-module {
  padding-left: 24px;
}

/* 视图叶子节点 */
.cf-node-leaf {
  padding-left: 60px;
}
.cf-node-leaf.active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 500;
}

/* 展开箭头 */
.cf-arrow {
  font-size: 14px;
  color: #909399;
  flex-shrink: 0;
  transition: transform 0.15s;
}
.cf-arrow.expanded {
  transform: rotate(90deg);
}

/* 右侧内容 */
.cf-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.cf-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 16px;
}
.cf-form-context {
  font-size: 12px;
  color: #909399;
  margin-bottom: 12px;
}
.cf-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 24px;
}
.cf-form-grid .span-2 {
  grid-column: 1 / -1;
}
.cf-table-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.cf-table-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.cf-table-head-left {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.cf-table-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}
.cf-table-count {
  font-size: 12px;
  color: #909399;
}
.cf-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 描述列：超长内容单行省略（悬浮通过 tooltip 显示完整内容） */
.cf-desc-text {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.cf-desc-empty {
  color: #c0c4cc;
}

.option-rows {
  width: 100%;
}

.option-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

/* 拖拽手柄（按住拖动调整字段顺序） */
.cf-drag-handle {
  display: inline-flex;
  align-items: center;
  color: #c0c4cc;
  cursor: grab;
}
.cf-drag-handle:hover {
  color: #909399;
}
.cf-drag-handle:active {
  cursor: grabbing;
}

/* 拖拽中的占位行 */
:deep(.cf-drag-ghost) {
  opacity: 0.5;
}
</style>
