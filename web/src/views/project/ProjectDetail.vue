<script lang="ts" setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  associateFile,
  bindProjectForm,
  deleteProject,
  dissociateFile,
  getProject,
  getProjectFiles,
  getProjectForms,
  updateProject
} from '@/api/project'
import {getFileList} from '@/api/file'
import {getFormList} from '@/api/form'
import {
  PROJECT_FORM_STATUS_LABELS,
  PROJECT_STATUS_LABELS,
  ProjectFormStatus,
  type ProjectFormVO,
  ProjectStatus,
  type ProjectVO
} from '@/types/project'
import {FILE_STATUS_LABELS, FILE_TYPE_LABELS, type FileRecordVO, FileStatus} from '@/types/file'
import type {FormVO} from '@/types/form'
import ProjectAssistantChat from '@/components/ProjectAssistantChat.vue'

/**
 * 项目详情页（Phase B）
 *
 * - Tab 容器：概览 / 文件 / 表单（activeTab 与 route.query.tab 双向同步，刷新可还原）
 * - 概览：基础信息 el-descriptions + 编辑（Dialog，projectNo 不可修改）+ 删除（跳回列表）
 * - 文件：项目下文件正常分页 + 关联已有文件（弹窗，前端过滤已归属他项目的文件，6003 仅作并发兜底）+ 去上传入口
 *   （去上传仅跳转 /file/upload?projectId=&projectName=，FileUpload 消费该 query 属 Phase C）
 * - 表单：绑定表单（弹窗，过滤已绑定的 formId，6005 兜底）+ 实例查看详情（行数据展示）
 */
const route = useRoute()
const router = useRouter()

// 项目 ID（保持字符串，避免大整数精度丢失）
const projectId = computed(() => route.params.id as string)

// 页面加载状态
const loading = ref(false)
// 项目详情数据
const projectDetail = ref<ProjectVO | null>(null)

// 当前 Tab（与 route.query.tab 同步）
const activeTab = computed<string>({
  get: () => (route.query.tab as string) || 'overview',
  set: (val) => {
    router.replace({query: {...route.query, tab: val}})
  }
})

// 格式化时间
function formatTime(time?: string): string {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

// 项目状态标签类型
function statusTagType(status: ProjectStatus): 'success' | 'info' {
  return status === ProjectStatus.ACTIVE ? 'success' : 'info'
}

// 表单实例状态标签类型
function formStatusTagType(status: ProjectFormStatus): 'success' | 'info' {
  return status === ProjectFormStatus.ACTIVE ? 'success' : 'info'
}

// 文件类型标签类型
function fileTypeTagType(type: FileRecordVO['fileType']): 'danger' | 'success' | 'primary' | 'info' {
  const map: Record<string, 'danger' | 'success' | 'primary' | 'info'> = {
    PDF: 'danger',
    EXCEL: 'success',
    WORD: 'primary',
    TXT: 'info',
    OTHER: 'info'
  }
  return map[type] ?? 'info'
}

// 文件状态标签类型
function fileStatusTagType(status: FileStatus): 'success' | 'danger' | 'warning' | 'info' {
  if (status === FileStatus.SUCCESS) return 'success'
  if (status === FileStatus.FAILED) return 'danger'
  if (status === FileStatus.UPLOADED) return 'info'
  return 'warning'
}

// ================ 项目详情加载 ================

// 加载项目详情
async function loadProject() {
  loading.value = true
  try {
    projectDetail.value = await getProject(projectId.value)
  } catch {
    // 错误已统一提示（6001 项目不存在时展示空态）
  } finally {
    loading.value = false
  }
}

// 返回列表
function handleBack() {
  router.push('/project/list')
}

// ================ 编辑项目（概览 Tab） ================

// 编辑弹窗显隐与数据
const editVisible = ref(false)
const editSaving = ref(false)
const editFormRef = ref<FormInstance>()
const editForm = reactive({
  projectName: '',
  description: '',
  status: ProjectStatus.ACTIVE as ProjectStatus
})

// 编辑校验规则（与 ProjectCreateDTO 一致：100/500）
const editRules: FormRules = {
  projectName: [
    {required: true, message: '请输入项目名称', trigger: 'blur'},
    {max: 100, message: '项目名称最长100字符', trigger: 'blur'}
  ],
  description: [{max: 500, message: '项目描述最长500字符', trigger: 'blur'}]
}

// 打开编辑弹窗（projectNo 仅只读展示，不可修改）
function openEdit() {
  if (!projectDetail.value) return
  editForm.projectName = projectDetail.value.projectName
  editForm.description = projectDetail.value.description ?? ''
  editForm.status = projectDetail.value.status
  editVisible.value = true
}

// 保存编辑（非空字段更新）
async function handleEditSave() {
  if (!editFormRef.value) return
  try {
    await editFormRef.value.validate()
  } catch {
    return
  }
  editSaving.value = true
  try {
    await updateProject(projectId.value, {
      projectName: editForm.projectName,
      description: editForm.description || undefined,
      status: editForm.status
    })
    ElMessage.success('项目信息已更新')
    editVisible.value = false
    await loadProject()
  } catch {
    // 错误已统一处理
  } finally {
    editSaving.value = false
  }
}

// 删除项目（跳回列表）
async function handleDeleteProject() {
  if (!projectDetail.value) return
  try {
    await ElMessageBox.confirm(
        `确定删除项目「${projectDetail.value.projectName}」吗？`,
        '提示',
        {type: 'warning'}
    )
    await deleteProject(projectId.value)
    ElMessage.success('已删除')
    router.replace('/project/list')
  } catch {
    // 取消或错误
  }
}

// ================ 文件 Tab ================

// 项目下文件列表与分页
const files = ref<FileRecordVO[]>([])
const filesTotal = ref(0)
const filesPage = ref(1)
const filesLoading = ref(false)

// 加载项目下文件列表
async function loadFiles() {
  filesLoading.value = true
  try {
    const data = await getProjectFiles(projectId.value, {pageNum: filesPage.value, pageSize: 10})
    files.value = data?.records ?? []
    filesTotal.value = data?.total ?? 0
  } catch {
    // 错误已统一处理
  } finally {
    filesLoading.value = false
  }
}

// 文件分页切换
function handleFilesPageChange(p: number) {
  filesPage.value = p
  loadFiles()
}

// 关联文件弹窗（候选来自 /file/page，前端过滤已归属他项目的文件）
const associateVisible = ref(false)
const candidates = ref<FileRecordVO[]>([])
const candidatesTotal = ref(0)
const candidatesPage = ref(1)
const candidatesLoading = ref(false)

// 打开关联弹窗
function openAssociate() {
  candidatesPage.value = 1
  associateVisible.value = true
  loadCandidates()
}

// 加载关联候选（过滤规则：未归属项目 或 已归属当前项目）
async function loadCandidates() {
  candidatesLoading.value = true
  try {
    const data = await getFileList({pageNum: candidatesPage.value, pageSize: 50})
    candidates.value = (data?.records ?? []).filter(
        (f) => !f.projectId || String(f.projectId) === projectId.value
    )
    candidatesTotal.value = data?.total ?? 0
  } catch {
    // 错误已统一处理
  } finally {
    candidatesLoading.value = false
  }
}

// 候选分页切换（翻页继续查找）
function handleCandidatesPageChange(p: number) {
  candidatesPage.value = p
  loadCandidates()
}

// 关联文件（成功后移出候选并刷新文件 Tab；6003 拦截器提示后刷新候选作并发兜底）
async function handleAssociate(row: FileRecordVO) {
  try {
    await associateFile(projectId.value, String(row.fileId))
    ElMessage.success(`文件「${row.fileName}」已关联`)
    candidates.value = candidates.value.filter((f) => f.fileId !== row.fileId)
    await loadFiles()
  } catch {
    await loadCandidates()
  }
}

// 解除文件关联
async function handleDissociate(row: FileRecordVO) {
  try {
    await ElMessageBox.confirm(`确定解除文件「${row.fileName}」与项目的关联吗？`, '提示', {
      type: 'warning'
    })
    await dissociateFile(projectId.value, String(row.fileId))
    ElMessage.success('已解除关联')
    // 防空页回退
    if (files.value.length === 1 && filesPage.value > 1) {
      filesPage.value -= 1
    }
    await loadFiles()
  } catch {
    // 取消或错误
  }
}

// 去上传（跳转文件上传页；FileUpload 消费 projectId/projectName 属 Phase C）
function goUpload() {
  router.push({
    path: '/file/upload',
    query: {
      projectId: projectId.value,
      projectName: projectDetail.value?.projectName ?? ''
    }
  })
}

// ================ 表单 Tab ================

// 表单实例列表与分页
const forms = ref<ProjectFormVO[]>([])
const formsTotal = ref(0)
const formsPage = ref(1)
const formsLoading = ref(false)

// 加载表单实例列表
async function loadForms() {
  formsLoading.value = true
  try {
    const data = await getProjectForms(projectId.value, {pageNum: formsPage.value, pageSize: 10})
    forms.value = data?.records ?? []
    formsTotal.value = data?.total ?? 0
  } catch {
    // 错误已统一处理
  } finally {
    formsLoading.value = false
  }
}

// 表单分页切换
function handleFormsPageChange(p: number) {
  formsPage.value = p
  loadForms()
}

// 绑定表单弹窗（候选来自 /form/page，前端过滤已绑定的表单）
const bindVisible = ref(false)
const bindCandidates = ref<FormVO[]>([])
const bindCandidatesTotal = ref(0)
const bindCandidatesPage = ref(1)
const bindCandidatesLoading = ref(false)

// 打开绑定弹窗
function openBind() {
  bindCandidatesPage.value = 1
  bindVisible.value = true
  loadBindCandidates()
}

// 加载绑定候选（过滤掉当前项目已绑定的 formId；6005 仅作并发兜底）
async function loadBindCandidates() {
  bindCandidatesLoading.value = true
  try {
    const data = await getFormList({pageNum: bindCandidatesPage.value, pageSize: 50})
    const boundFormIds = new Set(forms.value.map((f) => String(f.formId)))
    bindCandidates.value = (data?.records ?? []).filter(
        (f) => !boundFormIds.has(String(f.formId))
    )
    bindCandidatesTotal.value = data?.total ?? 0
  } catch {
    // 错误已统一处理
  } finally {
    bindCandidatesLoading.value = false
  }
}

// 绑定候选分页切换
function handleBindCandidatesPageChange(p: number) {
  bindCandidatesPage.value = p
  loadBindCandidates()
}

// 绑定表单（成功后移出候选并刷新表单 Tab）
async function handleBind(row: FormVO) {
  try {
    await bindProjectForm(projectId.value, {formId: String(row.formId)})
    ElMessage.success(`表单「${row.formName}」已绑定`)
    bindCandidates.value = bindCandidates.value.filter((f) => f.formId !== row.formId)
    await loadForms()
  } catch {
    await loadBindCandidates()
  }
}

// 查看实例详情（行数据展示）
const viewVisible = ref(false)
const viewForm = ref<ProjectFormVO | null>(null)

function handleViewForm(row: ProjectFormVO) {
  viewForm.value = row
  viewVisible.value = true
}

onMounted(() => {
  if (!projectId.value || !/^\d+$/.test(projectId.value)) {
    ElMessage.error('项目 ID 无效')
    router.replace('/project/list')
    return
  }
  loadProject()
  loadFiles()
  loadForms()
})
</script>

<template>
  <div v-loading="loading" class="project-detail-page">
    <!-- 项目不存在时 -->
    <el-empty v-if="!loading && !projectDetail" description="项目不存在或已删除">
      <el-button type="primary" @click="handleBack">返回列表</el-button>
    </el-empty>

    <template v-if="projectDetail">
      <!-- 顶部：项目名 + 返回 -->
      <el-card class="header-card" shadow="never">
        <div class="card-header">
          <span class="card-title">
            <el-icon><Folder/></el-icon>
            {{ projectDetail.projectName }}
          </span>
          <el-button @click="handleBack">
            <el-icon>
              <Back/>
            </el-icon>
            返回列表
          </el-button>
        </div>
      </el-card>

      <el-card class="tabs-card" shadow="never">
        <el-tabs v-model="activeTab">
          <!-- 概览 Tab -->
          <el-tab-pane label="概览" name="overview">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="项目 ID">{{ projectDetail.projectId }}</el-descriptions-item>
              <el-descriptions-item label="项目编号">
                <code class="project-no">{{ projectDetail.projectNo }}</code>
              </el-descriptions-item>
              <el-descriptions-item label="项目名称">{{ projectDetail.projectName }}</el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag :type="statusTagType(projectDetail.status)" size="small">
                  {{ PROJECT_STATUS_LABELS[projectDetail.status] }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ formatTime(projectDetail.createTime) }}</el-descriptions-item>
              <el-descriptions-item label="更新时间">{{ formatTime(projectDetail.updateTime) }}</el-descriptions-item>
              <el-descriptions-item :span="2" label="描述">
                {{ projectDetail.description || '-' }}
              </el-descriptions-item>
            </el-descriptions>

            <div class="overview-actions">
              <el-button type="primary" @click="openEdit">编辑</el-button>
              <el-button type="danger" @click="handleDeleteProject">删除项目</el-button>
            </div>
          </el-tab-pane>

          <!-- 文件 Tab -->
          <el-tab-pane label="文件" name="files">
            <div class="tab-actions">
              <el-button type="primary" @click="openAssociate">
                <el-icon>
                  <Link/>
                </el-icon>
                关联已有文件
              </el-button>
              <el-button type="success" @click="goUpload">
                <el-icon>
                  <UploadFilled/>
                </el-icon>
                去上传
              </el-button>
            </div>

            <el-empty v-if="!filesLoading && files.length === 0" description="项目下暂无文件，可关联已有文件或去上传"/>

            <el-table v-else v-loading="filesLoading" :data="files" stripe>
              <el-table-column label="文件 ID" prop="fileId" width="200"/>
              <el-table-column label="文件名" min-width="180" prop="fileName" show-overflow-tooltip/>
              <el-table-column align="center" label="类型" width="110">
                <template #default="{ row }">
                  <el-tag :type="fileTypeTagType(row.fileType)" size="small">
                    {{ FILE_TYPE_LABELS[row.fileType as keyof typeof FILE_TYPE_LABELS] }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column align="center" label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="fileStatusTagType(row.status as FileStatus)" size="small">
                    {{ FILE_STATUS_LABELS[row.status as FileStatus] }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="上传时间" width="180">
                <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
              </el-table-column>
              <el-table-column fixed="right" label="操作" width="120">
                <template #default="{ row }">
                  <el-button link size="small" type="danger" @click="handleDissociate(row as FileRecordVO)">
                    解除关联
                  </el-button>
                </template>
              </el-table-column>
            </el-table>

            <div class="pagination-bar">
              <el-pagination
                  :current-page="filesPage"
                  :page-size="10"
                  :total="filesTotal"
                  background
                  layout="total, prev, pager, next, jumper"
                  @current-change="handleFilesPageChange"
              />
            </div>
          </el-tab-pane>

          <!-- 表单 Tab -->
          <el-tab-pane label="表单" name="forms">
            <div class="tab-actions">
              <el-button type="primary" @click="openBind">
                <el-icon>
                  <Plus/>
                </el-icon>
                绑定表单
              </el-button>
            </div>

            <el-empty v-if="!formsLoading && forms.length === 0" description="项目下暂无表单，可绑定已有表单"/>

            <el-table v-else v-loading="formsLoading" :data="forms" stripe>
              <el-table-column label="实例 ID" prop="projectFormId" width="200"/>
              <el-table-column label="表单名称" min-width="160" prop="formName"/>
              <el-table-column align="center" label="版本" width="80">
                <template #default="{ row }">V{{ row.version }}</template>
              </el-table-column>
              <el-table-column align="center" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="formStatusTagType(row.status as ProjectFormStatus)" size="small">
                    {{ PROJECT_FORM_STATUS_LABELS[row.status as ProjectFormStatus] }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="绑定时间" width="180">
                <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
              </el-table-column>
              <el-table-column fixed="right" label="操作" width="120">
                <template #default="{ row }">
                  <el-button link size="small" type="primary" @click="handleViewForm(row as ProjectFormVO)">
                    查看详情
                  </el-button>
                </template>
              </el-table-column>
            </el-table>

            <div class="pagination-bar">
              <el-pagination
                  :current-page="formsPage"
                  :page-size="10"
                  :total="formsTotal"
                  background
                  layout="total, prev, pager, next, jumper"
                  @current-change="handleFormsPageChange"
              />
            </div>
          </el-tab-pane>
          <!-- 智能助手 Tab（lazy：首次激活才渲染） -->
          <el-tab-pane label="智能助手" lazy name="assistant">
            <ProjectAssistantChat :project-id="projectId"/>
          </el-tab-pane>
        </el-tabs>
      </el-card>

      <!-- 编辑项目弹窗 -->
      <el-dialog v-model="editVisible" title="编辑项目" width="520px">
        <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="90px">
          <el-form-item label="项目编号">
            <el-input :model-value="projectDetail.projectNo" disabled placeholder="项目编号不可修改"/>
          </el-form-item>
          <el-form-item label="项目名称" prop="projectName">
            <el-input v-model="editForm.projectName" maxlength="100" show-word-limit/>
          </el-form-item>
          <el-form-item label="项目描述" prop="description">
            <el-input
                v-model="editForm.description"
                :rows="2"
                maxlength="500"
                show-word-limit
                type="textarea"
            />
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="editForm.status" style="width: 100%">
              <el-option :value="ProjectStatus.ACTIVE" label="进行中"/>
              <el-option :value="ProjectStatus.ARCHIVED" label="已归档"/>
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="editVisible = false">取消</el-button>
          <el-button :loading="editSaving" type="primary" @click="handleEditSave">保存</el-button>
        </template>
      </el-dialog>

      <!-- 关联已有文件弹窗 -->
      <el-dialog v-model="associateVisible" title="关联已有文件" width="760px">
        <el-empty v-if="!candidatesLoading && candidates.length === 0"
                  description="无可关联文件（均已归属项目或列表为空）"/>
        <el-table v-else v-loading="candidatesLoading" :data="candidates" max-height="400">
          <el-table-column label="文件 ID" prop="fileId" width="200"/>
          <el-table-column label="文件名" min-width="180" prop="fileName" show-overflow-tooltip/>
          <el-table-column align="center" label="类型" width="110">
            <template #default="{ row }">
              <el-tag :type="fileTypeTagType(row.fileType)" size="small">
                {{ FILE_TYPE_LABELS[row.fileType as keyof typeof FILE_TYPE_LABELS] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="上传时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="100">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="handleAssociate(row as FileRecordVO)">
                关联
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination-bar">
          <el-pagination
              :current-page="candidatesPage"
              :page-size="50"
              :total="candidatesTotal"
              background
              layout="total, prev, pager, next"
              @current-change="handleCandidatesPageChange"
          />
        </div>
      </el-dialog>

      <!-- 绑定表单弹窗 -->
      <el-dialog v-model="bindVisible" title="绑定表单" width="760px">
        <el-empty v-if="!bindCandidatesLoading && bindCandidates.length === 0"
                  description="无可绑定的表单（均已绑定或暂无表单定义）"/>
        <el-table v-else v-loading="bindCandidatesLoading" :data="bindCandidates" max-height="400">
          <el-table-column label="表单 ID" prop="formId" width="200"/>
          <el-table-column label="表单名称" min-width="160" prop="formName"/>
          <el-table-column label="描述" min-width="180" prop="description" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.description || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="100">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="handleBind(row as FormVO)">
                绑定
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination-bar">
          <el-pagination
              :current-page="bindCandidatesPage"
              :page-size="50"
              :total="bindCandidatesTotal"
              background
              layout="total, prev, pager, next"
              @current-change="handleBindCandidatesPageChange"
          />
        </div>
      </el-dialog>

      <!-- 表单实例查看详情弹窗 -->
      <el-dialog v-model="viewVisible" title="表单实例详情" width="560px">
        <el-descriptions v-if="viewForm" :column="1" border>
          <el-descriptions-item label="实例 ID">{{ viewForm.projectFormId }}</el-descriptions-item>
          <el-descriptions-item label="项目 ID">{{ viewForm.projectId }}</el-descriptions-item>
          <el-descriptions-item label="表单 ID">{{ viewForm.formId }}</el-descriptions-item>
          <el-descriptions-item label="表单名称">{{ viewForm.formName }}</el-descriptions-item>
          <el-descriptions-item label="来源文件 ID">{{ viewForm.sourceFileId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="版本">V{{ viewForm.version }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="formStatusTagType(viewForm.status)" size="small">
              {{ PROJECT_FORM_STATUS_LABELS[viewForm.status] }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(viewForm.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatTime(viewForm.updateTime) }}</el-descriptions-item>
        </el-descriptions>
      </el-dialog>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.project-detail-page {
  max-width: 1200px;
  margin: 0 auto;

  .header-card {
    margin-bottom: 16px;

    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;

      .card-title {
        display: flex;
        align-items: center;
        gap: 6px;
        font-weight: 600;
        font-size: 16px;
      }
    }
  }

  .tabs-card {
    .tab-actions {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
      margin-bottom: 12px;
    }

    .overview-actions {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
      margin-top: 16px;
    }

    .pagination-bar {
      margin-top: 12px;
      text-align: right;
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
}
</style>
