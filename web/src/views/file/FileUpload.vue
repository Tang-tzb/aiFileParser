<script lang="ts" setup>
import {ref} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import type {UploadFile} from 'element-plus'
import {UploadFilled} from '@element-plus/icons-vue'
import {uploadFile} from '@/api/file'
import {
  FILE_TYPE_LABELS,
  FILE_STATUS_LABELS,
  ACCEPT_EXT,
  type FileUploadVO
} from '@/types/file'
import {setLastUpload} from '@/utils/lastUpload'

/**
 * 文件上传页（Phase 3）
 *
 * - 单文件逐个上传，手动控制 FormData 调用 POST /file/upload
 * - 不依赖 el-upload 默认上传行为（:auto-upload=false）
 * - 上传进度通过 axios onUploadProgress 实时展示
 * - 上传成功后保存 fileId 到 localStorage，供后续 AI 填报阶段使用
 */

const router = useRouter()

// 文件大小上限：100MB（与后端 spring.servlet.multipart.max-file-size 一致）
const MAX_SIZE = 100 * 1024 * 1024

// 当前选中的文件
const currentFile = ref<File | null>(null)
// 上传中状态
const uploading = ref(false)
// 上传进度（0-100）
const progress = ref(0)
// 上传结果
const result = ref<FileUploadVO | null>(null)

/**
 * el-upload on-change 钩子：选中文件时触发
 * - 单文件模式：每次选择覆盖 currentFile，清空旧结果
 * - 大小预校验：超过 100MB 直接拒收
 */
function handleFileChange(uploadFile: UploadFile) {
  const file = uploadFile.raw
  result.value = null
  if (!file) {
    currentFile.value = null
    return
  }
  if (file.size > MAX_SIZE) {
    ElMessage.warning(`文件大小 ${formatSize(file.size)} 超过 100MB 限制`)
    currentFile.value = null
    return
  }
  currentFile.value = file
}

/** 移除已选文件 */
function handleRemoveFile() {
  currentFile.value = null
}

/**
 * 执行上传
 * - 调 uploadFile，进度回调更新 progress
 * - 成功：写入 result、持久化 fileId、成功提示
 * - 失败：错误由 axios 拦截器统一提示
 */
async function handleUpload() {
  if (!currentFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  progress.value = 0
  try {
    const data = await uploadFile(currentFile.value, (p) => {
      progress.value = p
    })
    result.value = data
    // 持久化最近上传 fileId，供 AI 填报页使用
    setLastUpload(data)
    ElMessage.success('上传成功')
  } catch {
    // 错误已由 axios 拦截器统一提示
  } finally {
    uploading.value = false
    progress.value = 0
  }
}

/** 重置状态，继续上传新文件 */
function handleReset() {
  currentFile.value = null
  result.value = null
}

/** 跳转 AI 填报页 */
function goAiFill() {
  router.push('/fill/index')
}

/** 格式化文件大小 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

/** 格式化时间 */
function formatTime(time: string): string {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}
</script>

<template>
  <div class="file-upload-page">
    <!-- 标题说明 -->
    <el-card class="header-card" shadow="never">
      <div class="header">
        <h2 class="title">AI 文件自动解析</h2>
        <p class="desc">支持 PDF / Excel / Word / TXT，单个文件 ≤ 100MB</p>
      </div>
    </el-card>

    <!-- 上传区 -->
    <el-card class="upload-card" shadow="never">
      <el-upload
          :accept="ACCEPT_EXT"
          :auto-upload="false"
          :disabled="uploading"
          :limit="1"
          :on-change="handleFileChange"
          :show-file-list="false"
          class="upload-trigger"
          drag
      >
        <el-icon class="upload-icon">
          <UploadFilled/>
        </el-icon>
        <div class="upload-text">将文件拖拽到此处，或<em>点击上传</em></div>
        <template #tip>
          <div class="upload-tip">仅支持 PDF / Excel(xlsx,xls) / Word(docx,doc) / TXT 文件</div>
        </template>
      </el-upload>

      <!-- 已选文件信息 -->
      <div v-if="currentFile" class="file-info">
        <el-icon class="file-icon">
          <UploadFilled/>
        </el-icon>
        <div class="file-meta">
          <span :title="currentFile.name" class="file-name">{{ currentFile.name }}</span>
          <span class="file-size">{{ formatSize(currentFile.size) }}</span>
        </div>
        <el-button
            v-if="!uploading && !result"
            link
            size="small"
            type="danger"
            @click="handleRemoveFile"
        >
          移除
        </el-button>
      </div>

      <!-- 上传按钮 -->
      <div v-if="currentFile && !result" class="action-bar">
        <el-button
            :disabled="uploading"
            :loading="uploading"
            size="large"
            type="primary"
            @click="handleUpload"
        >
          {{ uploading ? '上传中...' : '开始上传' }}
        </el-button>
      </div>

      <!-- 进度条 -->
      <div v-if="uploading" class="progress-bar">
        <el-progress
            :percentage="progress"
            :stroke-width="10"
            :text-inside="true"
            status="success"
        />
      </div>
    </el-card>

    <!-- 上传结果 -->
    <el-card v-if="result" class="result-card" shadow="never">
      <template #header>
        <div class="result-header">
          <el-icon color="#67c23a">
            <CircleCheck/>
          </el-icon>
          <span>上传成功</span>
        </div>
      </template>

      <el-descriptions :column="2" border>
        <el-descriptions-item label="File ID">
          <span class="mono">{{ result.fileId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="文件名">{{ result.fileName }}</el-descriptions-item>
        <el-descriptions-item label="文件类型">
          <el-tag size="small">{{ FILE_TYPE_LABELS[result.fileType] || result.fileType }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" type="success">
            {{ FILE_STATUS_LABELS[result.status] || result.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="上传时间">{{ formatTime(result.createTime) }}</el-descriptions-item>
        <el-descriptions-item :span="2" label="存储路径">
          <span :title="result.filePath" class="mono">{{ result.filePath }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div class="result-actions">
        <el-button type="primary" @click="goAiFill">
          <el-icon>
            <MagicStick/>
          </el-icon>
          前往 AI 填报
        </el-button>
        <el-button @click="handleReset">继续上传</el-button>
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.file-upload-page {
  max-width: 800px;
  margin: 0 auto;

  .header-card {
    margin-bottom: 16px;
    text-align: center;

    .header {
      .title {
        margin: 0 0 8px 0;
        font-size: 22px;
        font-weight: 600;
        color: #303133;
      }

      .desc {
        margin: 0;
        font-size: 13px;
        color: #909399;
      }
    }
  }

  .upload-card {
    margin-bottom: 16px;

    .upload-trigger {
      width: 100%;

      :deep(.el-upload-dragger) {
        width: 100%;
        padding: 40px 20px;
      }

      .upload-icon {
        font-size: 56px;
        color: #c0c4cc;
        margin-bottom: 8px;
      }

      .upload-text {
        color: #606266;
        font-size: 14px;

        em {
          color: #409eff;
          font-style: normal;
        }
      }

      .upload-tip {
        margin-top: 8px;
        font-size: 12px;
        color: #909399;
        text-align: center;
      }
    }

    .file-info {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-top: 16px;
      padding: 12px 16px;
      background-color: #f5f7fa;
      border-radius: 4px;

      .file-icon {
        font-size: 24px;
        color: #409eff;
      }

      .file-meta {
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 4px;
        min-width: 0;

        .file-name {
          font-size: 14px;
          color: #303133;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .file-size {
          font-size: 12px;
          color: #909399;
        }
      }
    }

    .action-bar {
      margin-top: 16px;
      text-align: center;
    }

    .progress-bar {
      margin-top: 16px;
    }
  }

  .result-card {
    .result-header {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: 600;
      color: #67c23a;
    }

    .mono {
      font-family: 'Consolas', 'Monaco', monospace;
      color: #303133;
    }

    .result-actions {
      margin-top: 16px;
      display: flex;
      justify-content: center;
      gap: 12px;
    }
  }
}
</style>
