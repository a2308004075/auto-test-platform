<!--
 @author HXN
 @date 2026-08-20 23:57
 @description 主内容区域组件
-->
<script setup lang="ts">
/**
 * 主内容区
 * 原生 main 标签 + router-view + transition
 */
import { useRoute } from 'vue-router'
import { useTagsViewStore } from '@/stores'

const route = useRoute()
const tagsViewStore = useTagsViewStore()
</script>

<template>
  <main class="app-main">
    <router-view v-slot="{ Component }">
      <transition name="fade-transform" mode="out-in">
        <keep-alive :include="tagsViewStore.cachedViews">
          <component
            :is="Component"
            :key="route.path + tagsViewStore.refreshKeys[route.path]"
          />
        </keep-alive>
      </transition>
    </router-view>
  </main>
</template>

<style scoped>
.app-main {
  /* flex 撑满再加 margin 会使 margin-box 超出容器（产生整页幽灵滚动条），
     故不伸缩、显式扣除上下 margin 的高度 */
  flex: 0 0 auto;
  height: calc(100% - 32px);
  margin: 16px;
  padding: 20px;
  background: #fff;
  border-radius: 4px;
  min-height: 0;
}
</style>
