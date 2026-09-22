<!--
 @author HXN
 @date 2026-08-30
 @description 字段管理视图
-->
<script setup lang="ts">
/**
 * 字段管理页面（仅 ADMIN）
 * 左侧层级树：项目（当前项目）→ 模块（固定 缺陷/需求），模块下不再分视图，
 * 字段统一存于 edit 视图，用"显示位置"区分新建/详情差异化显示；
 * 右侧字段列表，新增/编辑通过弹窗填写
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
import { toScopeList } from '@/utils/customFieldScope'

const { hasPermission } = usePermission()
const projectStore = useProjectStore()

// ===== 当前项目（全局状态：从首页进入项目时设置） =====
const hasCurrentProject = computed(() => !!projectStore.currentProjectId)
const currentProjectId = computed(() => projectStore.currentProjectId)
const currentProjectName = computed(() => projectStore.currentProjectName)

// ===== 层级树：项目 → 模块（模块固定；字段统一存 edit 视图，由"显示位置"驱动新建/详情差异） =====
interface ModuleNode {
  label: string
  module: string
}

const moduleTree: ModuleNode[] = [
  { label: '缺陷字段', module: 'defect' },
  { label: '需求字段', module: 'requirement' },
]

// 树展开状态
const rootExpanded = ref(true)

function toggleRoot() {
  rootExpanded.value = !rootExpanded.value
}

// ===== 选中模块（模块节点即可选） =====
const selectedModule = ref('')

const selectedModuleLabel = computed(
  () => moduleTree.find((m) => m.module === selectedModule.value)?.label || ''
)

// 当前项目 + 模块选齐后右侧才可编辑（字段统一存 edit 视图）
const viewReady = computed(() => hasCurrentProject.value && !!selectedModule.value)

const placeholderText = computed(() => {
  if (!hasCurrentProject.value) return '请先从首页进入项目，再使用字段管理'
  return '请在左侧选择具体模块（如"缺陷字段"）'
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

// 显示位置：多选（create=新建显示 / detail=详情(编辑)显示），选项文案按当前模块动态生成（缺陷/需求）
const moduleShortName = computed(() => (selectedModule.value === 'defect' ? '缺陷' : '需求'))
const displayScopeOptions = computed(() => [
  { label: `新建${moduleShortName.value}`, value: 'create' },
  { label: `${moduleShortName.value}详情`, value: 'detail' },
])

/** 列表"显示位置"标签：双值=都显示；单值=仅新建X显示 / 仅X详情显示 */
function scopeLabel(scope: unknown): string {
  const list = toScopeList(scope)
  if (list.includes('create') && list.includes('detail')) return '都显示'
  return list.includes('create') ? `仅新建${moduleShortName.value}显示` : `仅${moduleShortName.value}详情显示`
}

/** 列表"显示位置"标签颜色：新建=warning 详情=success 都显示=info */
function scopeTagType(scope: unknown): 'warning' | 'success' | 'info' {
  const list = toScopeList(scope)
  if (list.includes('create') && list.includes('detail')) return 'info'
  return list.includes('create') ? 'warning' : 'success'
}

// ===== 列表数据 =====
const loading = ref(false)
const fieldList = ref<any[]>([])

// ===== 新增/编辑弹窗（共用表单，归属取当前项目与左侧选中视图，不可改） =====
const dialogVisible = ref(false)
const isEdit = ref(false)
const editingId = ref<number | null>(null)
const editingFieldKey = ref('')
const saving = ref(false)

const form = reactive({
  fieldLabel: '',
  fieldType: 'text',
  description: '',
  optionsJson: '',
  // 默认值 / 排序号已从弹窗表单移除（排序改由列表拖拽调整）：
  // 仅保留属性用于编辑时回填原值随提交带回，避免被覆盖
  defaultValue: '',
  isRequired: 0,
  displayScope: ['create', 'detail'],
  sortNo: 0,
})

// 下拉框选项动态编辑（仅填显示文本；存储值：普通字段保存时按顺序自动生成 1、2、3…，
// 状态字段保留各行原编码、新增行生成 CUSTOM_ 编码，避免改动 defect.status 存量值）
const optionRows = ref<{ label: string; value?: string }[]>([])
const isSelectType = computed(() => form.fieldType === 'select')
// 状态字段（fieldKey=defect_status）：流转状态下拉框的选项来源，编码不可重排、类型不可改、不可删除
const isStatusField = computed(() => isEdit.value && editingFieldKey.value === 'defect_status')

// 类型切换时清理互斥配置（枚举选项仅 select 用）
function handleFieldTypeChange() {
  if (form.fieldType !== 'select') {
    optionRows.value = []
    form.optionsJson = ''
  }
}

// ===== 加载字段列表（字段统一存于 edit 视图） =====
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
      viewType: 'edit',
    })
    fieldList.value = res.data || []
  } catch {
    fieldList.value = []
  } finally {
    loading.value = false
  }
  // 表格行渲染完成后再绑定拖拽排序（仅绑定一次，切换模块时 tbody 复用）
  await nextTick()
  ensureSortable()
}

// ===== 左侧选择模块节点 =====
function selectView(module: string) {
  if (selectedModule.value === module) return
  selectedModule.value = module
  resetForm()
  fetchList()
}

// 当前项目变化（重新进入其他项目后返回本页）：清空选择与表单
watch(
  () => projectStore.currentProjectId,
  () => {
    selectedModule.value = ''
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
  editingFieldKey.value = ''
  form.fieldLabel = ''
  form.fieldType = 'text'
  form.description = ''
  form.optionsJson = ''
  form.defaultValue = ''
  form.isRequired = 0
  form.displayScope = ['create', 'detail']
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
  editingFieldKey.value = row.fieldKey || ''
  form.fieldLabel = row.fieldLabel
  form.fieldType = row.fieldType
  form.description = row.description || ''
  form.optionsJson = row.optionsJson || ''
  form.defaultValue = row.defaultValue || ''
  form.isRequired = row.isRequired || 0
  form.displayScope = toScopeList(row.displayScope)
  form.sortNo = row.sortNo || 0

  // 解析已有选项（仅取显示文本；状态字段额外保留各选项原编码，改显示名不影响存储值）
  if (row.fieldType === 'select' && row.optionsJson) {
    try {
      const parsed = JSON.parse(row.optionsJson)
      optionRows.value = Array.isArray(parsed)
        ? parsed.map((o: any) => ({
            label: String(o?.label ?? ''),
            value: isStatusField.value ? String(o?.value ?? '') : undefined,
          }))
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
// 状态字段新增选项：立即生成编码（时间戳 + 自增序号防重复），行身份在编辑过程中保持稳定
let statusValueSeq = 0
function generateStatusValue() {
  return `CUSTOM_${Date.now().toString(36).toUpperCase()}${(++statusValueSeq).toString(36).toUpperCase()}`
}

function addOptionRow() {
  optionRows.value.push(isStatusField.value ? { label: '', value: generateStatusValue() } : { label: '' })
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
  if (!form.displayScope || form.displayScope.length === 0) {
    ElMessage.warning('请至少选择一项显示位置')
    return
  }

  // 构建 optionsJson（仅 select 类型）：普通字段存储值按选项顺序自动生成 1、2、3…（0 保留为默认/未设置）；
  // 状态字段保留各选项原编码（系统英文码 + CUSTOM_ 自定义码），避免改动 defect.status 存量值
  if (isSelectType.value) {
    const validRows = optionRows.value.filter((r) => r.label.trim())
    const validOptions = isStatusField.value
      ? validRows.map((r) => ({ label: r.label, value: r.value || generateStatusValue() }))
      : validRows.map((r, index) => ({ label: r.label, value: String(index + 1) }))
    form.optionsJson = JSON.stringify(validOptions)
  } else {
    form.optionsJson = ''
  }

  // 归属（项目/模块）由当前项目与左侧选中模块决定，不可在表单中修改；字段统一存 edit 视图
  const data = {
    projectId: currentProjectId.value,
    module: selectedModule.value,
    viewType: 'edit',
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

        <!-- 二级模块节点（可选叶子：字段统一存 edit 视图，由"显示位置"驱动新建/详情差异） -->
        <template v-if="hasCurrentProject && rootExpanded">
          <div
            v-for="m in moduleTree"
            :key="m.module"
            :class="['cf-node', 'cf-node-leaf', { active: selectedModule === m.module }]"
            @click="selectView(m.module)"
          >
            <span class="cf-node-label">{{ m.label }}</span>
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
                  <el-table-column label="显示位置" width="130" align="center">
                    <template #default="{ row }">
                      <el-tag size="small" :type="scopeTagType(row.displayScope)">
                        {{ scopeLabel(row.displayScope) }}
                      </el-tag>
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
                      <!-- 状态字段不可删除（后端同样校验）：新建字段的 fieldKey 为自动生成的 UUID，删除后无法重建 -->
                      <el-button
                        v-if="hasPermission('system:custom-field:delete') && row.fieldKey !== 'defect_status'"
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
        当前配置：{{ currentProjectName }} / {{ selectedModuleLabel }}
      </div>
      <el-form :model="form" label-width="90px">
        <div class="cf-form-grid">
          <el-form-item label="字段标签" required>
            <el-input v-model="form.fieldLabel" placeholder="如：严重程度" maxlength="50" />
          </el-form-item>
          <el-form-item label="字段类型" required>
            <!-- 状态字段类型固定为下拉框：改型会使流转状态配置失效 -->
            <el-select
              v-model="form.fieldType"
              placeholder="请选择类型"
              style="width: 100%"
              :disabled="isStatusField"
              @change="handleFieldTypeChange"
            >
              <el-option v-for="t in fieldTypeOptions" :key="t.value" :value="t.value" :label="t.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="是否必填">
            <el-switch v-model="form.isRequired" :active-value="1" :inactive-value="0" />
          </el-form-item>
          <el-form-item label="显示位置">
            <el-select v-model="form.displayScope" multiple placeholder="请选择显示位置" style="width: 100%">
              <el-option v-for="s in displayScopeOptions" :key="s.value" :value="s.value" :label="s.label" />
            </el-select>
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

          <!-- 下拉框选项配置（跨两列；普通字段存储值按顺序自动生成，状态字段保留原编码、新增行自动生成 CUSTOM_ 编码） -->
          <el-form-item v-if="isSelectType" label="枚举选项" class="span-2">
            <div v-if="isStatusField" class="status-field-tip">
              此字段为缺陷流转状态下拉框的枚举来源：可增删选项、修改显示名、调整顺序；删除选项后存量缺陷保留原状态值
            </div>
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

/* 模块叶子节点（可选） */
.cf-node-leaf {
  padding-left: 24px;
}
.cf-node-leaf.active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 500;
}

/* 展开箭头（仅根节点使用） */
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

/* 状态字段编辑提示：说明此字段为流转状态下拉框的枚举来源 */
.status-field-tip {
  width: 100%;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-bottom: 8px;
  padding: 6px 10px;
  background: #f5f7fa;
  border-radius: 4px;
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
