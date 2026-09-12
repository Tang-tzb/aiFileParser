<script lang="ts" setup>
import {computed, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import {ChatDotRound} from '@element-plus/icons-vue'
import {getProjectPage} from '@/api/project'
import {PROJECT_STATUS_LABELS, ProjectStatus, type ProjectVO} from '@/types/project'
import ProjectAssistantChat from '@/components/ProjectAssistantChat.vue'

/**
 * 智能对话独立页（一级菜单直达入口）
 *
 * - 选择项目后渲染 ProjectAssistantChat（组件内部按 projectId 隔离会话/消息，
 *   watch 已支持切换项目重载，无需 key 重建）
 * - 仅 ACTIVE 项目可选（与 AiFill 页"选择项目"口径一致）
 * - 支持 ?projectId= 进入时自动预选（字符串校验纯数字）
 * - 项目详情 → 智能助手 Tab 为另一入口，双入口共存
 */

const route = useRoute()

// 项目候选（一次拉取前 50 条 + filterable 本地过滤，项目量级小不扩后端）
const projectOptions = ref<ProjectVO[]>([])
const projectsLoading = ref(false)

// 当前选中的项目（字符串 ID，避免大整数精度丢失）
const selectedProjectId = ref('')

// 已选中的项目对象（展示名称用）
const selectedProject = computed(() =>
    projectOptions.value.find((p) => String(p.projectId) === selectedProjectId.value)
)

/** 拉取项目候选列表 */
async function loadProjects() {
  projectsLoading.value = true
  try {
    const data = await getProjectPage({pageNum: 1, pageSize: 50})
    projectOptions.value = data?.records ?? []
  } catch {
    // 错误已由拦截器统一提示
  } finally {
    projectsLoading.value = false
  }
}

/** 选项展示文案（编号 - 名称 [状态]） */
function projectLabel(p: ProjectVO): string {
  return `${p.projectNo} - ${p.projectName}（${PROJECT_STATUS_LABELS[p.status]}）`
}

// 初始化：query 预选 + 拉取候选
onMounted(async () => {
  await loadProjects()
  const queryId = route.query.projectId as string
  if (queryId && /^\d+$/.test(queryId)) {
    selectedProjectId.value = queryId
  }
})
</script>

<template>
  <div class="assistant-console">
    <!-- 项目选择 -->
    <el-card class="selector-card" shadow="never">
      <div class="selector-bar">
        <span class="selector-label">
          <el-icon>
            <ChatDotRound/>
          </el-icon>
          选择项目
        </span>
        <el-select
            v-model="selectedProjectId"
            :loading="projectsLoading"
            class="selector-select"
            clearable
            filterable
            placeholder="请选择项目（仅启用中的项目）"
        >
          <el-option
              v-for="p in projectOptions"
              :key="p.projectId"
              :disabled="p.status !== ProjectStatus.ACTIVE"
              :label="projectLabel(p)"
              :value="String(p.projectId)"
          />
        </el-select>
        <span v-if="selectedProject" class="selector-hint">
          对话按项目隔离；也可从 项目详情 → 智能助手 Tab 进入
        </span>
      </div>
    </el-card>

    <!-- 聊天区 -->
    <ProjectAssistantChat v-if="selectedProjectId" :project-id="selectedProjectId"/>
    <el-empty v-else description="请先选择项目开始对话"/>
  </div>
</template>

<style lang="scss" scoped>
.assistant-console {
  .selector-card {
    margin-bottom: 16px;

    .selector-bar {
      display: flex;
      align-items: center;
      gap: 12px;

      .selector-label {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-weight: 600;
        color: #303133;
        white-space: nowrap;
      }

      .selector-select {
        width: 320px;
      }

      .selector-hint {
        font-size: 12px;
        color: #909399;
      }
    }
  }
}
</style>
