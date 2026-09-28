<!--
 @author HXN
 @date 2026-09-28
 @description AI 服务提供商配置视图
-->
<script setup lang="ts">
/**
 * AI 服务页面（仅 ADMIN）
 * 当前启用提供商切换（百炼 / Qoder Cloud Agents）
 * 连接凭据均在后端 yml 配置（不入库、不回显），本页仅做切换
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader/index.vue'
import {
  getAiProviderStatus,
  switchAiProvider,
  type AiProviderStatus,
  type AiProviderInfo,
} from '@/api/aiProvider'

// ===== 状态 =====
const loading = ref(false)
const status = ref<AiProviderStatus | null>(null)
const selectedProvider = ref('bailian')
const switching = ref(false)

// ===== 计算属性 =====
const bailian = computed<AiProviderInfo | null>(() =>
  status.value?.providers.find(p => p.provider === 'bailian') || null)
const qoder = computed<AiProviderInfo | null>(() =>
  status.value?.providers.find(p => p.provider === 'qoder') || null)
/** 选中项是否有变更（与当前启用项比较） */
const dirty = computed(() =>
  status.value ? selectedProvider.value !== status.value.currentProvider : false)
/** 选中的目标提供商是否未完成配置 */
const targetNotConfigured = computed(() => {
  if (!dirty.value) return false
  const target = selectedProvider.value === 'bailian' ? bailian.value : qoder.value
  return target ? !target.configured : false
})

// ===== 加载状态 =====
async function fetchStatus() {
  loading.value = true
  try {
    const res: any = await getAiProviderStatus()
    status.value = res.data || res
    selectedProvider.value = status.value?.currentProvider || 'bailian'
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '加载 AI 服务状态失败')
  } finally {
    loading.value = false
  }
}

// ===== 切换提供商 =====
async function handleSwitch() {
  if (!dirty.value || switching.value) return
  if (targetNotConfigured.value) {
    ElMessage.warning('目标提供商连接配置不完整，请先在后端完成配置')
    return
  }
  switching.value = true
  try {
    const res: any = await switchAiProvider(selectedProvider.value)
    status.value = res.data || res
    ElMessage.success('AI 提供商已切换为 ' + (selectedProvider.value === 'qoder' ? 'Qoder Cloud Agents' : '阿里云百炼'))
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '切换失败')
  } finally {
    switching.value = false
  }
}

onMounted(() => { fetchStatus() })
</script>

<template>
  <div class="ai-provider-view" v-loading="loading">
    <PageHeader title="AI 服务" />

    <!-- 当前提供商切换 -->
    <div class="config-card">
      <div class="config-card-header">当前提供商</div>
      <div class="config-card-body">
        <el-radio-group v-model="selectedProvider">
          <el-radio value="bailian">
            阿里云百炼{{ bailian && !bailian.configured ? '（未配置）' : '' }}
          </el-radio>
          <el-radio value="qoder">
            Qoder Cloud Agents{{ qoder && !qoder.configured ? '（未配置）' : '' }}
          </el-radio>
        </el-radio-group>
        <div class="config-save-row">
          <el-button
            type="primary"
            :loading="switching"
            :disabled="!dirty || targetNotConfigured"
            @click="handleSwitch"
          >
            保存切换
          </el-button>
          <span v-if="targetNotConfigured" class="switch-warning">
            目标提供商连接配置不完整，无法切换
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-provider-view {
  width: 100%;
}

/* 卡片（对齐全局配置页样式） */
.config-card {
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03), 0 1px 6px -1px rgba(0, 0, 0, 0.02), 0 2px 4px rgba(0, 0, 0, 0.02);
  border: 1px solid #f0f0f0;
  margin-bottom: 16px;
}
.config-card-header {
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 15px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.88);
  display: flex;
  align-items: center;
}
.config-card-body {
  padding: 20px;
}

/* 切换校验警告 */
.switch-warning {
  margin-left: 12px;
  font-size: 13px;
  color: var(--el-color-danger);
}
.config-save-row {
  margin-top: 16px;
  display: flex;
  align-items: center;
}
</style>
