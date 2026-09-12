<script lang="ts" setup>
import {onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {deleteProject, getProjectPage} from '@/api/project'
import {PROJECT_STATUS_LABELS, ProjectStatus, type ProjectVO} from '@/types/project'

/**
 * 项目管理列表页
 *
 * - 对接后端 GET /project/page 分页接口，按创建时间倒序展示项目列表
 * - 顶部提供"新建项目"按钮；列表项支持"查看详情"与"删除"（DELETE /project/{id}，逻辑删除）
 */
const router = useRouter()

// 列表数据与分页状态
const list = ref<ProjectVO[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)

// 跳转新建项目
function handleCreate() {
  router.push('/project/create')
}

// 跳转项目详情
function handleDetail(row: ProjectVO) {
  router.push(`/project/${row.projectId}`)
}

// 加载项目列表
async function loadList() {
  loading.value = true
  try {
    const data = await getProjectPage({pageNum: pageNum.value, pageSize: pageSize.value})
    list.value = data?.records ?? []
    total.value = data?.total ?? 0
  } catch {
    // 错误已由 axios 拦截器统一提示
  } finally {
    loading.value = false
  }
}

// 分页切换
function handlePageChange(p: number) {
  pageNum.value = p
  loadList()
}

// 项目状态标签类型
function statusTagType(status: ProjectStatus): 'success' | 'info' {
  return status === ProjectStatus.ACTIVE ? 'success' : 'info'
}

// 删除项目
async function handleDelete(row: ProjectVO) {
  try {
    await ElMessageBox.confirm(
        `确定删除项目「${row.projectName}」吗？`,
        '提示',
        {type: 'warning'}
    )
    await deleteProject(row.projectId)
    ElMessage.success('已删除')
    // 删除后若当前页只剩 1 条且非首页，回退一页，避免空页
    if (list.value.length === 1 && pageNum.value > 1) {
      pageNum.value -= 1
    }
    await loadList()
  } catch {
    // 取消或错误（错误已由拦截器提示）
  }
}

onMounted(loadList)
</script>

<template>
  <div class="project-list-page">
    <!-- 操作区 -->
    <el-card class="action-card" shadow="never">
      <div class="action-bar">
        <div class="left-group">
          <span class="page-hint">管理项目及项目下的文件、表单与 AI 助手</span>
        </div>
        <div class="right-group">
          <el-button type="success" @click="handleCreate">
            <el-icon>
              <Plus/>
            </el-icon>
            新建项目
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 项目列表 -->
    <el-card class="list-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>
            <el-icon><Folder/></el-icon>
            项目列表
          </span>
        </div>
      </template>

      <el-empty v-if="!loading && list.length === 0" description="暂无项目，点击右上角新建项目">
        <template #image>
          <el-icon :size="56" color="#c0c4cc">
            <Folder/>
          </el-icon>
        </template>
      </el-empty>

      <el-table v-else v-loading="loading" :data="list" stripe>
        <el-table-column label="项目 ID" prop="projectId" width="200"/>
        <el-table-column label="项目编号" min-width="140" prop="projectNo">
          <template #default="{ row }">
            <code class="project-no">{{ row.projectNo }}</code>
          </template>
        </el-table-column>
        <el-table-column label="项目名称" min-width="160" prop="projectName"/>
        <el-table-column align="center" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status as ProjectStatus)" size="small">
              {{ PROJECT_STATUS_LABELS[row.status as ProjectStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="200" prop="description" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.description || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">
            {{ row.createTime ? new Date(row.createTime).toLocaleString('zh-CN') : '-' }}
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="160">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="handleDetail(row as ProjectVO)">
              查看详情
            </el-button>
            <el-button link size="small" type="danger" @click="handleDelete(row as ProjectVO)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
            :current-page="pageNum"
            :page-size="pageSize"
            :total="total"
            background
            layout="total, prev, pager, next, jumper"
            @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.project-list-page {
  max-width: 1200px;
  margin: 0 auto;

  .action-card {
    margin-bottom: 16px;

    .action-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 12px;

      .left-group {
        .page-hint {
          color: #909399;
          font-size: 13px;
        }
      }
    }
  }

  .list-card {
    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;

      span {
        display: flex;
        align-items: center;
        gap: 6px;
        font-weight: 600;
      }
    }

    .project-no {
      background: #f5f7fa;
      padding: 2px 6px;
      border-radius: 4px;
      font-family: 'Courier New', monospace;
      font-size: 12px;
      color: #409eff;
    }

    .pagination-bar {
      margin-top: 12px;
      text-align: right;
    }
  }
}
</style>
