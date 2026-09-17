<!--
 @author HXN
 @date 2026-08-21 23:16
 @description 用户管理视图
-->
<script setup lang="ts">
/**
 * 用户管理页面（仅 ADMIN）
 * 对齐原型 docs/ui/settings/user-management.html
 * 包含：独立搜索 + 表格 + 多弹窗（新建/编辑/分配角色/重置密码/确认禁用启用/确认删除）
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader/index.vue'
import { getUsers, createUser, updateUser, deleteUser, toggleUserStatus, resetPassword, getRoles, checkAccount } from '@/api/user'
import { User as UserIcon, CircleCheck, CircleClose, Loading as LoadingIcon, InfoFilled } from '@element-plus/icons-vue'
import { validatePassword, PASSWORD_RULE_HINT } from '@/utils/password'
import { usePermission } from '@/composables/usePermission'

const { hasPermission } = usePermission()

// ===== 列表数据 =====
const loading = ref(false)
const userList = ref<any[]>([])
const roleList = ref<any[]>([])
const pagination = reactive({ current: 1, pageSize: 20, total: 0 })

// 搜索条件
const searchAccount = ref('')
const searchDisplayName = ref('')
const filterRoleId = ref<number | undefined>(undefined)

// ===== 新建用户弹窗 =====
const createVisible = ref(false)
const createForm = reactive({
  username: '',
  displayName: '',
  password: '',
  roleId: undefined as number | undefined,
})
const createErrors = reactive({
  username: '',
  displayName: '',
  password: '',
})

// 账号实时校验状态：null=未校验, true=可用, false=已占用
const accountChecking = ref(false)
const accountAvailable = ref<boolean | null>(null)

// ===== 编辑用户弹窗 =====
const editVisible = ref(false)
const editingUser = ref<any>(null)
const editForm = reactive({
  displayName: '',
  roleId: undefined as number | undefined,
})
const editError = ref('')

// ===== 分配角色弹窗 =====
const roleAssignVisible = ref(false)
const roleAssignUser = ref<any>(null)
const roleAssignForm = reactive({
  roleId: undefined as number | undefined,
})

// ===== 重置密码弹窗 =====
const resetVisible = ref(false)
const resetUser = ref<any>(null)
const resetForm = reactive({
  newPassword: '',
  confirmPassword: '',
})
const resetErrors = reactive({
  newPassword: '',
  confirmPassword: '',
})

// ===== 确认禁用/启用弹窗 =====
const toggleVisible = ref(false)
const toggleUser = ref<any>(null)
const toggleAction = ref<'disable' | 'enable'>('disable')

// ===== 确认删除弹窗 =====
const deleteVisible = ref(false)
const deleteUserRef = ref<any>(null)

// 保留字
const RESERVED_DISPLAY_NAMES = ['管理员', '超级管理员']
const RESERVED_ACCOUNTS_LOWER = ['superadmin']

// ===== 获取用户列表 =====
async function fetchUsers() {
  loading.value = true
  try {
    const res: any = await getUsers({
      account: searchAccount.value || undefined,
      displayName: searchDisplayName.value || undefined,
      roleId: filterRoleId.value || undefined,
      page: pagination.current,
      pageSize: pagination.pageSize,
    })
    userList.value = res.data?.items || []
    pagination.total = res.data?.total || 0
  } catch {
    userList.value = []
  } finally {
    loading.value = false
  }
}

// ===== 获取角色列表 =====
async function fetchRoles() {
  try {
    const res: any = await getRoles()
    roleList.value = res.data || []
  } catch {
    roleList.value = []
  }
}

// ===== 搜索处理 =====
function handleSearch() {
  pagination.current = 1
  fetchUsers()
}

function handlePageChange(p: number) {
  pagination.current = p
  fetchUsers()
}

// ===== 新建用户 =====
function openCreateUser() {
  createForm.username = ''
  createForm.displayName = ''
  createForm.password = ''
  createForm.roleId = roleList.value.find((r: any) => r.roleCode === 'TESTER')?.id
  createErrors.username = ''
  createErrors.displayName = ''
  createErrors.password = ''
  accountAvailable.value = null
  accountChecking.value = false
  createVisible.value = true
}

function clearCreateError(field: keyof typeof createErrors) {
  createErrors[field] = ''
  if (field === 'username') {
    accountAvailable.value = null
  }
}

// ===== 账号实时校验 =====
async function checkAccountAvailable() {
  const username = createForm.username.trim()
  // 本地校验未通过时不请求后端
  if (!username || username.length < 6 || RESERVED_ACCOUNTS_LOWER.includes(username.toLowerCase())) {
    accountAvailable.value = null
    return
  }
  accountChecking.value = true
  try {
    const res: any = await checkAccount(username)
    if (res.data?.available) {
      accountAvailable.value = true
      createErrors.username = ''
    } else {
      accountAvailable.value = false
      createErrors.username = res.data?.message || '账号不可用'
    }
  } catch {
    accountAvailable.value = null
  } finally {
    accountChecking.value = false
  }
}

async function handleCreateUser() {
  let valid = true
  createErrors.username = ''
  createErrors.displayName = ''
  createErrors.password = ''

  if (!createForm.username.trim()) {
    createErrors.username = '请输入账号'
    valid = false
  } else if (createForm.username.trim().length < 6) {
    createErrors.username = '账号长度不能少于6位'
    valid = false
  } else if (RESERVED_ACCOUNTS_LOWER.includes(createForm.username.toLowerCase())) {
    createErrors.username = '账号不能使用"superAdmin"，该账号为系统保留'
    valid = false
  }
  if (!createForm.displayName.trim()) {
    createErrors.displayName = '请输入用户名'
    valid = false
  } else if (RESERVED_DISPLAY_NAMES.includes(createForm.displayName)) {
    createErrors.displayName = '用户名不能为"' + createForm.displayName + '"，该名称为系统保留'
    valid = false
  }
  if (!createForm.password) {
    createErrors.password = '请输入密码'
    valid = false
  } else {
    const pwdError = validatePassword(createForm.password)
    if (pwdError) {
      createErrors.password = pwdError
      valid = false
    }
  }
  if (!valid) return

  // 账号唯一性校验（若尚未实时校验，提交前补校验）
  if (accountAvailable.value !== true) {
    await checkAccountAvailable()
    if (!accountAvailable.value) {
      return
    }
  }

  try {
    await createUser({
      username: createForm.username,
      displayName: createForm.displayName,
      password: createForm.password,
      roleId: createForm.roleId,
    })
    ElMessage.success('用户 ' + createForm.username + ' 创建成功')
    createVisible.value = false
    fetchUsers()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '创建失败')
  }
}

// ===== 编辑用户 =====
function openEditUser(row: any) {
  editingUser.value = row
  editForm.displayName = row.displayName || ''
  editForm.roleId = row.roleId
  editError.value = ''
  editVisible.value = true
}

function clearEditError() {
  editError.value = ''
}

async function handleEditUser() {
  if (!editingUser.value) return
  const displayName = editForm.displayName.trim()
  editError.value = ''

  if (!displayName) {
    editError.value = '请输入用户名'
    return
  }
  if (RESERVED_DISPLAY_NAMES.includes(displayName) && displayName !== editingUser.value.displayName) {
    editError.value = '用户名不能为"' + displayName + '"，该名称为系统保留'
    return
  }

  try {
    await updateUser(editingUser.value.id, {
      displayName: displayName,
      roleId: editForm.roleId,
    })
    ElMessage.success('用户 ' + (editingUser.value.username) + ' 信息已更新')
    editVisible.value = false
    fetchUsers()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '更新失败')
  }
}

// ===== 分配角色 =====
function openRoleAssign(row: any) {
  roleAssignUser.value = row
  roleAssignForm.roleId = row.roleId
  roleAssignVisible.value = true
}

async function handleRoleAssign() {
  if (!roleAssignUser.value) return
  try {
    await updateUser(roleAssignUser.value.id, { roleId: roleAssignForm.roleId })
    const role = roleList.value.find((r: any) => r.id === roleAssignForm.roleId)
    ElMessage.success('用户 ' + roleAssignUser.value.displayName + ' 角色已分配为 ' + (role?.roleName || ''))
    roleAssignVisible.value = false
    fetchUsers()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '分配失败')
  }
}

// ===== 重置密码 =====
function openResetPassword(row: any) {
  resetUser.value = row
  resetForm.newPassword = ''
  resetForm.confirmPassword = ''
  resetErrors.newPassword = ''
  resetErrors.confirmPassword = ''
  resetVisible.value = true
}

function clearResetError(field: keyof typeof resetErrors) {
  resetErrors[field] = ''
}

async function handleResetPassword() {
  if (!resetUser.value) return
  let valid = true
  resetErrors.newPassword = ''
  resetErrors.confirmPassword = ''

  const pwdError = validatePassword(resetForm.newPassword)
  if (pwdError) {
    resetErrors.newPassword = pwdError
    valid = false
  }
  if (!resetForm.confirmPassword) {
    resetErrors.confirmPassword = '请再次输入新密码'
    valid = false
  } else if (resetForm.newPassword && resetForm.newPassword !== resetForm.confirmPassword) {
    resetErrors.confirmPassword = '两次输入的密码不一致'
    valid = false
  }
  if (!valid) return

  try {
    await resetPassword(resetUser.value.id, { newPassword: resetForm.newPassword })
    ElMessage.success('用户 ' + resetUser.value.displayName + ' 密码已重置')
    resetVisible.value = false
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '重置失败')
  }
}

// ===== 禁用/启用 =====
function openToggleStatus(row: any, action: 'disable' | 'enable') {
  toggleUser.value = row
  toggleAction.value = action
  toggleVisible.value = true
}

async function handleToggleStatus() {
  if (!toggleUser.value) return
  const row = toggleUser.value
  const isActive = toggleAction.value === 'disable' ? 0 : 1
  try {
    await toggleUserStatus(row.id, { isActive })
    ElMessage.success('用户 ' + row.displayName + (toggleAction.value === 'disable' ? ' 已禁用' : ' 已启用'))
    toggleVisible.value = false
    fetchUsers()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

// ===== 删除用户 =====
function openDeleteUser(row: any) {
  if (RESERVED_ACCOUNTS_LOWER.includes(row.username?.toLowerCase())) {
    ElMessage.error('系统内置管理员账号不允许删除')
    return
  }
  deleteUserRef.value = row
  deleteVisible.value = true
}

async function handleDeleteUser() {
  if (!deleteUserRef.value) return
  const row = deleteUserRef.value
  try {
    await deleteUser(row.id)
    ElMessage.success('用户 ' + row.displayName + ' 已删除')
    deleteVisible.value = false
    fetchUsers()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

// ===== 辅助函数 =====
function isAdminRow(row: any): boolean {
  return row.username?.toLowerCase() === 'superadmin'
}

function formatDateTime(dt?: string): string {
  if (!dt) return '-'
  return dt.replace('T', ' ').substring(0, 19)
}

function getRoleTagClass(role: string): string {
  const upper = role?.toUpperCase()
  if (upper === 'SUPER_ADMIN') return 'role-tag-super-admin'
  return upper === 'ADMIN' ? 'role-tag-admin' : 'role-tag-tester'
}

onMounted(() => { fetchUsers(); fetchRoles() })
</script>

<template>
  <div class="user-mgmt-view">
    <PageHeader title="用户列表">
      <el-button v-if="hasPermission('system:user:add')" type="primary" @click="openCreateUser">+ 新建用户</el-button>
    </PageHeader>

    <!-- 搜索工具栏 -->
    <div class="um-toolbar">
      <el-input v-model="searchAccount" placeholder="搜索账号" style="width: 160px;" clearable @input="handleSearch" @clear="handleSearch" />
      <el-input v-model="searchDisplayName" placeholder="搜索用户名" style="width: 160px;" clearable @input="handleSearch" @clear="handleSearch" />
      <el-select v-model="filterRoleId" placeholder="全部角色" style="width: 120px;" clearable @change="handleSearch">
        <el-option v-for="role in roleList" :key="role.id" :value="role.id" :label="role.roleName" />
      </el-select>
    </div>

    <!-- 用户表格 -->
    <div class="um-card">
      <div class="um-table-wrapper">
        <el-table v-loading="loading" :data="userList" row-key="id" style="width: 100%;" :header-cell-style="{ background: '#fafafa' }">
          <el-table-column prop="username" width="140">
            <template #header>
              <el-tooltip content="账号为系统唯一标识，不可重复" placement="top">
                <span>账号 <el-icon style="vertical-align: middle; color: #909399;"><InfoFilled /></el-icon></span>
              </el-tooltip>
            </template>
            <template #default="{ row }">
              <el-tooltip content="账号为系统唯一标识" placement="top">
                <span class="um-account-cell">
                  <el-icon class="um-account-icon"><UserIcon /></el-icon>
                  <b>{{ row.username }}</b>
                </span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="displayName" label="用户名" min-width="120" />
          <el-table-column label="角色" width="100">
            <template #default="{ row }">
              <span class="um-role-tag" :class="getRoleTagClass(row.role)">{{ row.roleName || row.role }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <span class="um-status-tag" :class="row.isActive === 1 ? 'status-active' : 'status-disabled'">
                {{ row.isActive === 1 ? '启用' : '禁用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="170">
            <template #default="{ row }">
              <span class="um-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最近登录" min-width="170">
            <template #default="{ row }">
              <span class="um-datetime">{{ formatDateTime(row.lastLoginAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <div class="um-actions">
                <template v-if="isAdminRow(row)">
                  <el-button v-if="hasPermission('system:user:edit')" type="primary" link size="small" @click="openEditUser(row)">编辑</el-button>
                  <el-button type="primary" link size="small" @click="openResetPassword(row)">重置密码</el-button>
                </template>
                <template v-else>
                  <el-button v-if="hasPermission('system:user:edit')" type="primary" link size="small" @click="openEditUser(row)">编辑</el-button>
                  <el-button v-if="hasPermission('system:user:edit')" type="primary" link size="small" @click="openRoleAssign(row)">分配角色</el-button>
                  <el-button v-if="hasPermission('system:user:toggle') && row.isActive === 1" type="warning" link size="small" @click="openToggleStatus(row, 'disable')">禁用</el-button>
                  <el-button v-if="hasPermission('system:user:toggle') && row.isActive !== 1" type="success" link size="small" @click="openToggleStatus(row, 'enable')">启用</el-button>
                  <el-button v-if="hasPermission('system:user:reset-password')" type="primary" link size="small" @click="openResetPassword(row)">重置密码</el-button>
                  <el-button v-if="hasPermission('system:user:delete')" type="danger" link size="small" @click="openDeleteUser(row)">删除</el-button>
                </template>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-if="pagination.total > 0" class="um-card-footer">
        <el-pagination
          background
          layout="total, prev, pager, next, jumper"
          :total="pagination.total"
          :page-size="pagination.pageSize"
          :current-page="pagination.current"
          @current-change="handlePageChange"
        />
      </div>
    </div>

    <!-- 新建用户弹窗 -->
    <el-dialog v-model="createVisible" title="新建用户" width="440px">
      <el-form label-position="top">
        <el-form-item label="账号" required>
          <el-input v-model="createForm.username" placeholder="请输入账号（至少6位，登录用）" @input="clearCreateError('username')" @blur="checkAccountAvailable">
            <template #suffix>
              <el-icon v-if="accountChecking" class="is-loading"><LoadingIcon /></el-icon>
              <el-icon v-else-if="accountAvailable === true" style="color: #67c23a;"><CircleCheck /></el-icon>
              <el-icon v-else-if="accountAvailable === false" style="color: #f56c6c;"><CircleClose /></el-icon>
            </template>
          </el-input>
          <div v-if="createErrors.username" class="um-error-msg">{{ createErrors.username }}</div>
        </el-form-item>
        <el-form-item label="用户名" required>
          <el-input v-model="createForm.displayName" placeholder="请输入用户名（显示名称）" @input="clearCreateError('displayName')" />
          <div v-if="createErrors.displayName" class="um-error-msg">{{ createErrors.displayName }}</div>
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="createForm.password" type="password" show-password :placeholder="PASSWORD_RULE_HINT" @input="clearCreateError('password')" />
          <div v-if="createErrors.password" class="um-error-msg">{{ createErrors.password }}</div>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createForm.roleId" placeholder="请选择角色" style="width: 100%;">
            <el-option v-for="role in roleList" :key="role.id" :value="role.id" :label="`${role.roleName}（${role.roleCode}）`" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :disabled="accountChecking || accountAvailable === false" @click="handleCreateUser">确定</el-button>
      </template>
    </el-dialog>

    <!-- 编辑用户弹窗 -->
    <el-dialog v-model="editVisible" title="编辑用户" width="440px">
      <el-form label-position="top">
        <el-form-item label="账号">
          <el-input :model-value="editingUser?.username" disabled />
        </el-form-item>
        <el-form-item label="用户名" required>
          <el-input v-model="editForm.displayName" placeholder="请输入用户名（显示名称）" @input="clearEditError" />
          <div v-if="editError" class="um-error-msg">{{ editError }}</div>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="editForm.roleId" style="width: 100%;" :disabled="isAdminRow(editingUser)">
            <el-option v-for="role in roleList" :key="role.id" :value="role.id" :label="`${role.roleName}（${role.roleCode}）`" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="handleEditUser">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色弹窗 -->
    <el-dialog v-model="roleAssignVisible" title="分配角色" width="400px">
      <el-form label-position="top">
        <el-form-item label="当前用户">
          <el-input :model-value="roleAssignUser?.displayName" readonly />
        </el-form-item>
        <el-form-item label="分配角色">
          <el-select v-model="roleAssignForm.roleId" style="width: 100%;">
            <el-option v-for="role in roleList" :key="role.id" :value="role.id" :label="`${role.roleName}（${role.roleCode}）`" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleAssignVisible = false">取消</el-button>
        <el-button type="primary" @click="handleRoleAssign">确定</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码弹窗 -->
    <el-dialog v-model="resetVisible" title="重置密码" width="400px">
      <div class="um-reset-warn">
        <span class="um-warn-icon">⚠</span> 即将重置用户 <b>{{ resetUser?.displayName }}</b> 的登录密码
      </div>
      <el-form label-position="top">
        <el-form-item label="新密码" required>
          <el-input v-model="resetForm.newPassword" type="password" show-password :placeholder="PASSWORD_RULE_HINT" @input="clearResetError('newPassword')" />
          <div v-if="resetErrors.newPassword" class="um-error-msg">{{ resetErrors.newPassword }}</div>
        </el-form-item>
        <el-form-item label="确认密码" required>
          <el-input v-model="resetForm.confirmPassword" type="password" show-password placeholder="请再次输入新密码" @input="clearResetError('confirmPassword')" />
          <div v-if="resetErrors.confirmPassword" class="um-error-msg">{{ resetErrors.confirmPassword }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" @click="handleResetPassword">确定</el-button>
      </template>
    </el-dialog>

    <!-- 确认禁用/启用弹窗 -->
    <el-dialog v-model="toggleVisible" :title="toggleAction === 'disable' ? '确认禁用' : '确认启用'" width="400px">
      <div class="um-confirm-message">
        确定{{ toggleAction === 'disable' ? '禁用' : '启用' }}用户 <b>{{ toggleUser?.displayName }}</b>（{{ toggleUser?.username }}）吗？
        <br />
        <span class="um-confirm-hint">{{ toggleAction === 'disable' ? '禁用后该用户将无法登录系统。' : '启用后该用户将可以正常登录系统。' }}</span>
      </div>
      <template #footer>
        <el-button @click="toggleVisible = false">取消</el-button>
        <el-button :type="toggleAction === 'disable' ? 'danger' : 'primary'" @click="handleToggleStatus">
          确认{{ toggleAction === 'disable' ? '禁用' : '启用' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 确认删除弹窗 -->
    <el-dialog v-model="deleteVisible" title="确认删除" width="400px">
      <div class="um-confirm-message">
        确定删除用户 <b>{{ deleteUserRef?.displayName }}</b>（{{ deleteUserRef?.username }}）吗？
        <br />
        <span class="um-confirm-hint um-confirm-danger">删除后该用户数据将无法恢复，请谨慎操作。</span>
      </div>
      <template #footer>
        <el-button @click="deleteVisible = false">取消</el-button>
        <el-button type="danger" @click="handleDeleteUser">确认删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.user-mgmt-view {
  width: 100%;
}

/* 工具栏 */
.um-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

/* 卡片 */
.um-card {
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03), 0 1px 6px -1px rgba(0, 0, 0, 0.02), 0 2px 4px rgba(0, 0, 0, 0.02);
  border: 1px solid #f0f0f0;
}
.um-table-wrapper {
  overflow-x: auto;
}
.um-card-footer {
  padding: 12px 20px;
  border-top: 1px solid #f0f0f0;
  display: flex;
  justify-content: flex-end;
}

/* 账号单元格（唯一标识） */
.um-account-cell {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.um-account-icon {
  color: #409eff;
  font-size: 14px;
}

/* 角色标签 */
.um-role-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 4px;
  font-size: 12px;
  border: 1px solid transparent;
  white-space: nowrap;
}
.role-tag-super-admin {
  background: #fef0f0;
  color: #cf1322;
  border-color: #fab6b6;
}
.role-tag-admin {
  background: #f4ecff;
  color: #722ed1;
  border-color: #d3adf7;
}
.role-tag-tester {
  background: #ecf5ff;
  color: #409eff;
  border-color: #a0cfff;
}

/* 状态标签 */
.um-status-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 4px;
  font-size: 12px;
  border: 1px solid transparent;
  white-space: nowrap;
}
.status-active {
  background: #f0f9eb;
  color: #67c23a;
  border-color: #b3e19d;
}
.status-disabled {
  background: #fafafa;
  color: rgba(0, 0, 0, 0.45);
  border-color: #dcdfe6;
}

/* 时间 */
.um-datetime {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

/* 操作按钮 */
.um-actions {
  white-space: nowrap;
  display: flex;
  justify-content: flex-start;
  gap: 4px;
}

/* 错误提示 */
.um-error-msg {
  color: #f56c6c;
  font-size: 12px;
  margin-top: 4px;
}

/* 重置密码警告框 */
.um-reset-warn {
  margin-bottom: 16px;
  padding: 10px 14px;
  background: #fdf6ec;
  border: 1px solid #f5dab1;
  border-radius: 4px;
  font-size: 13px;
  color: #8c6e00;
}
.um-warn-icon {
  margin-right: 4px;
}

/* 确认消息 */
.um-confirm-message {
  font-size: 14px;
  color: rgba(0, 0, 0, 0.88);
  line-height: 1.6;
}
.um-confirm-hint {
  color: rgba(0, 0, 0, 0.45);
  font-size: 13px;
}
.um-confirm-danger {
  color: #f56c6c;
}
</style>
