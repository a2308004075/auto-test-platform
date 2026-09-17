<!--
 @author HXN
 @date 2026-08-21 23:16
 @description 个人资料视图
-->
<script setup lang="ts">
/**
 * 个人资料页面
 * 三个 Tab：基本信息、修改密码、登录记录
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores'
import { getCurrentUser, updateProfile, changePassword, getLoginLogs } from '@/api/auth'
import { validatePassword, PASSWORD_RULE_HINT } from '@/utils/password'
import { useRouteTab } from '@/composables/useRouteTab'

const userStore = useUserStore()
// Tab 状态（与 URL ?tab= 参数同步，刷新后停留在当前选项卡）
const activeTab = useRouteTab(['basic', 'password', 'activity'], 'basic')

// ===== 基本信息 =====
const profileLoading = ref(false)
const userInfo = ref<any>({})
const profileForm = reactive({
  displayName: '',
  username: '',
  bio: '',
})

// superAdmin 账号保护
const isAdminAccount = computed(() => userInfo.value.username === 'superAdmin')

async function fetchCurrentUser() {
  profileLoading.value = true
  try {
    const res: any = await getCurrentUser()
    userInfo.value = res.data || {}
    profileForm.displayName = userInfo.value.displayName || ''
    profileForm.username = userInfo.value.username || ''
    profileForm.bio = userInfo.value.bio || ''
  } catch {
    ElMessage.error('获取用户信息失败')
  } finally {
    profileLoading.value = false
  }
}

async function handleSaveProfile() {
  if (!profileForm.displayName) {
    ElMessage.warning('用户名不能为空')
    return
  }
  // 非superAdmin用户校验保留字
  if (!isAdminAccount.value) {
    if (profileForm.displayName === '管理员') {
      ElMessage.warning('用户名不能为"管理员"，该名称为系统保留')
      return
    }
    if (profileForm.username && profileForm.username.toLowerCase() === 'superadmin') {
      ElMessage.warning('账号不能使用"superAdmin"，该账号为系统保留')
      return
    }
  }
  try {
    const res: any = await updateProfile({
      displayName: profileForm.displayName,
      username: !isAdminAccount.value && profileForm.username !== userInfo.value.username ? profileForm.username : undefined,
      bio: profileForm.bio,
    })
    userInfo.value = res.data
    // 同步更新 store
    userStore.setUserInfo({
      id: userInfo.value.id,
      username: userInfo.value.username,
      displayName: userInfo.value.displayName,
      role: userInfo.value.role,
      permissions: userInfo.value.permissions,
      permissionDetails: userInfo.value.permissionDetails,
    })
    ElMessage.success('个人资料保存成功')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  }
}

// ===== 修改密码 =====
const passwordForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})
const passwordLoading = ref(false)

// 密码强度
const passwordScore = computed(() => {
  const pwd = passwordForm.newPassword
  if (!pwd) return -1
  let score = 0
  if (pwd.length >= 6) score++
  if (pwd.length >= 12) score++
  if (/[a-z]/.test(pwd) && /[A-Z]/.test(pwd)) score++
  if (/\d/.test(pwd)) score++
  if (/[^a-zA-Z0-9]/.test(pwd)) score++
  return score
})

const strengthInfo = computed(() => {
  const levels = [
    { width: '20%', color: '#f56c6c', label: '弱' },
    { width: '40%', color: '#e6a23c', label: '较弱' },
    { width: '60%', color: '#e6a23c', label: '一般' },
    { width: '80%', color: '#67c23a', label: '较强' },
    { width: '100%', color: '#67c23a', label: '强' },
  ]
  if (passwordScore.value < 0) return null
  return levels[Math.min(passwordScore.value, levels.length) - 1] || levels[0]
})

async function handleChangePassword() {
  if (!passwordForm.currentPassword) {
    ElMessage.warning('请输入当前密码')
    return
  }
  const pwdError = validatePassword(passwordForm.newPassword)
  if (pwdError) {
    ElMessage.warning(pwdError)
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  passwordLoading.value = true
  try {
    await changePassword({
      currentPassword: passwordForm.currentPassword,
      newPassword: passwordForm.newPassword,
    })
    ElMessage.success('密码修改成功，请重新登录')
    // 清空表单
    passwordForm.currentPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    // 退出登录
    setTimeout(() => {
      userStore.logout()
      window.location.href = '/home'
    }, 1500)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '密码修改失败')
  } finally {
    passwordLoading.value = false
  }
}

// ===== 登录记录 =====
const logLoading = ref(false)
const logList = ref<any[]>([])
const logPagination = reactive({ current: 1, pageSize: 20, total: 0 })

async function fetchLoginLogs() {
  logLoading.value = true
  try {
    const res: any = await getLoginLogs({ page: logPagination.current, pageSize: logPagination.pageSize })
    logList.value = res.data?.items || []
    logPagination.total = res.data?.total || 0
  } catch {
    logList.value = []
  } finally {
    logLoading.value = false
  }
}

function handleLogPageChange(p: number) {
  logPagination.current = p
  fetchLoginLogs()
}

onMounted(() => {
  fetchCurrentUser()
  fetchLoginLogs()
})
</script>

<template>
  <div class="profile-view" v-loading="profileLoading">
    <!-- 标签页 -->
    <el-tabs v-model="activeTab" class="profile-tabs">
      <!-- 基本信息 -->
      <el-tab-pane label="基本信息" name="basic">
        <div class="profile-form-card">
          <div class="profile-section-title">基本信息</div>
          <el-form label-position="top" class="profile-form">
            <div class="profile-form-grid">
              <el-form-item label="用户名">
                <el-input
                  v-model="profileForm.displayName"
                  placeholder="请输入显示名称"
                />
              </el-form-item>
              <el-form-item label="账号">
                <el-input
                  v-model="profileForm.username"
                  :disabled="isAdminAccount"
                  placeholder="请输入账号"
                />
                <div v-if="isAdminAccount" class="form-hint">系统内置管理员账号，账号不可修改</div>
              </el-form-item>
              <el-form-item label="角色">
                <el-input :model-value="userInfo.roleName || userInfo.role" disabled />
              </el-form-item>
              <el-form-item label="注册时间">
                <el-input
                  :model-value="userInfo.createdAt ? userInfo.createdAt.replace('T', ' ').substring(0, 19) : ''"
                  disabled
                />
              </el-form-item>
            </div>
            <el-form-item label="个人简介" class="form-group-full">
              <el-input
                v-model="profileForm.bio"
                type="textarea"
                :rows="3"
                maxlength="500"
                show-word-limit
                placeholder="一句话介绍自己"
              />
            </el-form-item>
            <div class="profile-actions">
              <el-button type="primary" @click="handleSaveProfile">保存修改</el-button>
            </div>
          </el-form>
        </div>
      </el-tab-pane>

      <!-- 修改密码 -->
      <el-tab-pane label="修改密码" name="password">
        <div class="profile-form-card">
          <div class="profile-section-title">修改密码</div>
          <el-form label-position="top" class="profile-form">
            <el-form-item label="当前密码" required>
              <el-input v-model="passwordForm.currentPassword" type="password" show-password placeholder="请输入当前密码" />
            </el-form-item>
            <div class="profile-form-grid">
              <el-form-item label="新密码" required>
                <el-input v-model="passwordForm.newPassword" type="password" show-password :placeholder="PASSWORD_RULE_HINT" />
                <div v-if="strengthInfo" class="password-strength">
                  <div class="password-strength-bar">
                    <div class="password-strength-fill" :style="{ width: strengthInfo.width, background: strengthInfo.color }" />
                  </div>
                  <span class="password-strength-text" :style="{ color: strengthInfo.color }">密码强度：{{ strengthInfo.label }}</span>
                </div>
              </el-form-item>
              <el-form-item label="确认新密码" required>
                <el-input v-model="passwordForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
              </el-form-item>
            </div>
            <div class="profile-actions">
              <el-button type="primary" :loading="passwordLoading" @click="handleChangePassword">修改密码</el-button>
            </div>
          </el-form>
        </div>
      </el-tab-pane>

      <!-- 登录记录 -->
      <el-tab-pane label="登录记录" name="activity">
        <div class="profile-form-card">
          <div class="profile-section-title">最近登录记录</div>
          <div v-loading="logLoading">
            <div v-if="logList.length === 0 && !logLoading" class="empty-logs">
              暂无登录记录
            </div>
            <div v-for="item in logList" :key="item.id" class="activity-item">
              <div class="activity-dot" :style="{ background: item.status === 'SUCCESS' ? '#67c23a' : '#f56c6c' }" />
              <div class="activity-content">
                <div class="activity-desc" :style="{ color: item.status === 'FAILED' ? '#f56c6c' : '' }">
                  {{ item.status === 'SUCCESS' ? '登录成功' : '登录失败' }}
                  <span v-if="item.browser || item.os"> — {{ item.browser }} / {{ item.os }}</span>
                  <span v-if="item.message">（{{ item.message }}）</span>
                </div>
                <div class="activity-time">
                  {{ item.createdAt ? item.createdAt.replace('T', ' ').substring(0, 19) : '' }}
                  <span v-if="item.ip"> · IP {{ item.ip }}</span>
                </div>
              </div>
            </div>
            <div v-if="logList.length > 0" class="log-footer">
              <el-pagination
                background
                layout="total, prev, pager, next"
                :total="logPagination.total"
                :page-size="logPagination.pageSize"
                :current-page="logPagination.current"
                @current-change="handleLogPageChange"
              />
              <span class="log-hint">仅显示最近 30 天的登录记录</span>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
/* 表单卡片 */
.profile-form-card {
  background: #fff;
  border-radius: 0 0 6px 6px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03), 0 1px 6px -1px rgba(0, 0, 0, 0.02), 0 2px 4px rgba(0, 0, 0, 0.02);
  padding: 24px;
}
.profile-section-title {
  font-size: 15px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.88);
  margin-bottom: 20px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
}
.profile-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 32px;
  max-width: 640px;
}
.form-group-full {
  grid-column: 1 / -1;
  max-width: 640px;
}
.form-group-full :deep(.el-textarea__inner) {
  resize: vertical;
}
.form-hint {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
  margin-top: 4px;
}
.profile-actions {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #f0f0f0;
  display: flex;
  gap: 8px;
}

/* 密码强度 */
.password-strength {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.password-strength-bar {
  flex: 1;
  height: 4px;
  background: #f0f0f0;
  border-radius: 2px;
  overflow: hidden;
  max-width: 120px;
}
.password-strength-fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.3s, background 0.3s;
}
.password-strength-text {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

/* 登录记录 */
.activity-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}
.activity-item:last-child {
  border-bottom: none;
}
.activity-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 6px;
  flex-shrink: 0;
}
.activity-content {
  flex: 1;
}
.activity-desc {
  font-size: 13px;
  color: rgba(0, 0, 0, 0.88);
}
.activity-time {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
  margin-top: 2px;
}
.empty-logs {
  text-align: center;
  padding: 40px 0;
  color: rgba(0, 0, 0, 0.45);
  font-size: 14px;
}
.log-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 0 4px;
}
.log-hint {
  font-size: 13px;
  color: rgba(0, 0, 0, 0.45);
}
</style>
