<script lang="ts" setup>
import {ref, computed} from 'vue'
import {useRouter, useRoute} from 'vue-router'
import type {RouteRecordRaw} from 'vue-router'
import {Document, UploadFilled, MagicStick} from '@element-plus/icons-vue'

/**
 * 主布局组件
 * - 企业后台布局：顶部 Header + 左侧 Sidebar + 主内容区
 * - 侧边栏菜单固定三项：表单管理 / 文件上传 / AI自动填报
 * - HealthController 不生成菜单
 */
const router = useRouter()
const route = useRoute()

// 侧边栏折叠状态
const isCollapse = ref(false)

// 图标映射（el-menu 需要组件引用而非字符串）
const iconMap: Record<string, any> = {
  Document,
  UploadFilled,
  MagicStick
}

// 菜单项配置（仅展示非 hidden 路由）
interface MenuItem {
  index: string
  title: string
  icon: string
}

const menuItems: MenuItem[] = [
  {index: '/form/list', title: '表单管理', icon: 'Document'},
  {index: '/file/upload', title: '文件上传', icon: 'UploadFilled'},
  {index: '/fill/index', title: 'AI自动填报', icon: 'MagicStick'}
]

// 当前激活菜单
const activeMenu = computed(() => {
  // 详情页激活表单管理
  if (route.path.startsWith('/form')) return '/form/list'
  if (route.path.startsWith('/file')) return '/file/upload'
  if (route.path.startsWith('/fill')) return '/fill/index'
  return route.path
})

// 菜单点击跳转
function handleMenuSelect(index: string) {
  router.push(index)
}
</script>

<template>
  <el-container class="main-layout">
    <!-- 顶部 Header -->
    <el-header class="app-header">
      <div class="header-left">
        <el-icon class="collapse-btn" @click="isCollapse = !isCollapse">
          <Fold v-if="!isCollapse"/>
          <Expand v-else/>
        </el-icon>
        <el-icon class="logo-icon">
          <MagicStick/>
        </el-icon>
        <span class="app-title">AI 文件自动填报系统</span>
      </div>
      <div class="header-right">
        <span class="version-tag">v0.1.0</span>
      </div>
    </el-header>

    <el-container class="body-container">
      <!-- 左侧 Sidebar -->
      <el-aside :width="isCollapse ? '64px' : '220px'" class="app-sidebar">
        <el-menu
            :collapse="isCollapse"
            :collapse-transition="false"
            :default-active="activeMenu"
            class="sidebar-menu"
            @select="handleMenuSelect"
        >
          <el-menu-item
              v-for="item in menuItems"
              :key="item.index"
              :index="item.index"
          >
            <el-icon>
              <component :is="iconMap[item.icon]"/>
            </el-icon>
            <template #title>{{ item.title }}</template>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- 主内容区 -->
      <el-main class="app-main">
        <RouterView v-slot="{ Component }">
          <transition mode="out-in" name="fade">
            <component :is="Component"/>
          </transition>
        </RouterView>
      </el-main>
    </el-container>
  </el-container>
</template>

<style lang="scss" scoped>
.main-layout {
  height: 100%;
}

.app-header {
  height: var(--app-header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(90deg, #1f2d3d 0%, #2c3e50 100%);
  color: #fff;
  padding: 0 20px;

  .header-left {
    display: flex;
    align-items: center;
    gap: 12px;

    .collapse-btn {
      cursor: pointer;
      font-size: 20px;
      color: #fff;

      &:hover {
        color: #409eff;
      }
    }

    .logo-icon {
      font-size: 24px;
      color: #409eff;
    }

    .app-title {
      font-size: 18px;
      font-weight: 600;
      letter-spacing: 1px;
    }
  }

  .header-right {
    .version-tag {
      font-size: 12px;
      opacity: 0.7;
    }
  }
}

.body-container {
  height: calc(100% - var(--app-header-height));
}

.app-sidebar {
  background-color: #304156;
  transition: width 0.28s;
  overflow: hidden;

  .sidebar-menu {
    border-right: none;
    background-color: transparent;

    :deep(.el-menu-item) {
      color: #bfcbd9;
      height: 50px;
      line-height: 50px;

      &:hover {
        background-color: #263445;
        color: #fff;
      }

      &.is-active {
        background-color: #1f2d3d;
        color: #409eff;
      }
    }
  }
}

.app-main {
  background-color: #f5f7fa;
  padding: 20px;
  overflow-y: auto;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
