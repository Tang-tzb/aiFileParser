<script lang="ts" setup>
import {reactive, ref} from 'vue'
import {useRouter} from 'vue-router'
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage} from 'element-plus'
import {createProject} from '@/api/project'
import type {ProjectCreateDTO} from '@/types/project'

/**
 * 新建项目页（Phase A）
 *
 * - 表单项：项目编号（全库唯一，≤64 字符）/ 项目名称（必填，≤100 字符）/ 项目描述（≤500 字符）
 * - 一次性提交：POST /project，返回 Result<IdVO>（ID 字符串化）
 * - 错误码 6002（项目编号已存在）时 projectNo 字段标红提示，可修改后重新提交
 * - 创建成功跳转项目详情页（便于继续关联文件与绑定表单）
 */
const router = useRouter()

// 表单引用
const formRef = ref<FormInstance>()
// 表单数据
const formData = reactive({
  projectNo: '',
  projectName: '',
  description: ''
})

// 项目编号重复错误（el-form-item error 属性，非空时字段标红）
const projectNoError = ref('')

// 校验规则（与后端 ProjectCreateDTO 一致：64/100/500 字符）
const rules: FormRules = {
  projectNo: [
    {required: true, message: '请输入项目编号', trigger: 'blur'},
    {max: 64, message: '项目编号最长64字符', trigger: 'blur'},
    {
      pattern: /^[a-zA-Z0-9_-]+$/,
      message: '仅支持字母、数字、下划线与中划线',
      trigger: 'blur'
    }
  ],
  projectName: [
    {required: true, message: '请输入项目名称', trigger: 'blur'},
    {max: 100, message: '项目名称最长100字符', trigger: 'blur'}
  ],
  description: [{max: 500, message: '项目描述最长500字符', trigger: 'blur'}]
}

// 编号输入时清空重复错误，允许用户修正后重新提交
function handleProjectNoInput() {
  if (projectNoError.value) {
    projectNoError.value = ''
  }
}

// 提交保存项目
const submitting = ref(false)

async function handleSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    ElMessage.warning('请完善项目基本信息')
    return
  }

  const payload: ProjectCreateDTO = {
    projectNo: formData.projectNo,
    projectName: formData.projectName,
    description: formData.description || undefined
  }

  submitting.value = true
  try {
    const projectId = await createProject(payload)
    ElMessage.success(`项目创建成功，ID：${projectId}`)
    // 跳转项目详情，便于继续关联文件与绑定表单
    router.replace(`/project/${projectId}`)
  } catch (e) {
    // 6002：项目编号已存在 → 编号字段标红（错误消息已由拦截器提示）
    if ((e as Error & { code?: number }).code === 6002) {
      projectNoError.value = '项目编号已存在，请更换'
    }
  } finally {
    submitting.value = false
  }
}

// 返回列表
function handleBack() {
  router.push('/project/list')
}
</script>

<template>
  <div class="project-create-page">
    <el-card class="info-card" shadow="never">
      <template #header>
        <span class="card-title">
          <el-icon><Folder/></el-icon>
          项目基本信息
        </span>
      </template>
      <el-form
          ref="formRef"
          :model="formData"
          :rules="rules"
          label-position="right"
          label-width="90px"
      >
        <el-form-item :error="projectNoError" label="项目编号" prop="projectNo">
          <el-input
              v-model="formData.projectNo"
              maxlength="64"
              placeholder="如：PRJ-2026-001（全库唯一）"
              show-word-limit
              @input="handleProjectNoInput"
          />
        </el-form-item>
        <el-form-item label="项目名称" prop="projectName">
          <el-input v-model="formData.projectName" maxlength="100" placeholder="如：职业教育园一期" show-word-limit/>
        </el-form-item>
        <el-form-item label="项目描述" prop="description">
          <el-input
              v-model="formData.description"
              :rows="2"
              maxlength="500"
              placeholder="项目用途说明（可选）"
              show-word-limit
              type="textarea"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 底部操作 -->
    <div class="footer-actions">
      <el-button @click="handleBack">取消</el-button>
      <el-button :loading="submitting" type="primary" @click="handleSubmit">
        创建项目
      </el-button>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.project-create-page {
  max-width: 1200px;
  margin: 0 auto;

  .info-card {
    margin-bottom: 16px;
  }

  .card-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-weight: 600;
  }

  .footer-actions {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    padding: 16px 0;
  }
}
</style>
