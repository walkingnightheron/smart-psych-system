<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Upload, Search, RefreshLeft, Plus } from '@element-plus/icons-vue'
import {
  getStudentsApi,
  createStudentApi,
  updateStudentApi,
  updateStudentStatusApi,
  resetStudentPasswordApi,
  deleteStudentApi,
  downloadStudentsTemplateApi,
  importStudentsXlsxApi
} from '@/api/admin'
import { extractImportErrors } from '@/api/request'

const loading = ref(false)
const templateDownloading = ref(false)
const students = ref([])
const allDeptClasses = ref([])

const filterForm = reactive({
  keyword: '',
  deptClass: ''
})

const deptClassOptions = computed(() => {
  const set = new Set(allDeptClasses.value)
  for (const s of students.value) {
    if (s.deptClass && String(s.deptClass).trim()) {
      set.add(String(s.deptClass).trim())
    }
  }
  return Array.from(set)
})

async function loadStudents() {
  loading.value = true
  try {
    const res = await getStudentsApi({
      keyword: filterForm.keyword,
      deptClass: filterForm.deptClass
    })
    const list = res?.data ?? res ?? []
    students.value = Array.isArray(list) ? list : []
    for (const s of students.value) {
      if (s.deptClass && String(s.deptClass).trim()) {
        const cls = String(s.deptClass).trim()
        if (!allDeptClasses.value.includes(cls)) {
          allDeptClasses.value.push(cls)
        }
      }
    }
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  loadStudents()
}

function handleReset() {
  filterForm.keyword = ''
  filterForm.deptClass = ''
  loadStudents()
}

async function handleDownloadTemplate() {
  templateDownloading.value = true
  try {
    await downloadStudentsTemplateApi()
    ElMessage.success('学生名单导入模板已开始下载')
  } finally {
    templateDownloading.value = false
  }
}

// ==================== 导入学生 XLSX 弹窗 ====================
const importDialogVisible = ref(false)
const importing = ref(false)
const uploadRef = ref(null)
const selectedFile = ref(null)
const updateExisting = ref(false)
const importResult = ref(null)
const importErrorMessage = ref('')
const importErrors = ref([])

function openImportDialog() {
  selectedFile.value = null
  updateExisting.value = false
  importResult.value = null
  importErrorMessage.value = ''
  importErrors.value = []
  importDialogVisible.value = true
}

function handleFileChange(uploadFile) {
  selectedFile.value = uploadFile?.raw || null
  importResult.value = null
  importErrorMessage.value = ''
  importErrors.value = []
}

function handleFileRemove() {
  selectedFile.value = null
}

function handleFileExceed(files) {
  if (!uploadRef.value || !files?.length) return
  uploadRef.value.clearFiles()
  const rawFile = files[0]
  uploadRef.value.handleStart(rawFile)
  selectedFile.value = rawFile
  importResult.value = null
  importErrorMessage.value = ''
  importErrors.value = []
}

async function submitImportStudents() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择要导入的 .xlsx 文件')
    return
  }
  const fileName = String(selectedFile.value.name || '').toLowerCase()
  if (!fileName.endsWith('.xlsx')) {
    ElMessage.warning('仅支持上传 .xlsx 格式文件')
    return
  }

  importing.value = true
  importResult.value = null
  importErrorMessage.value = ''
  importErrors.value = []
  try {
    const res = await importStudentsXlsxApi(selectedFile.value, updateExisting.value)
    const data = res?.data ?? res ?? {}
    importResult.value = {
      totalRows: data.totalRows ?? 0,
      createdCount: data.createdCount ?? 0,
      updatedCount: data.updatedCount ?? 0,
      skippedCount: data.skippedCount ?? 0,
      passwordRuleHint:
        data.passwordRuleHint || '新建学生初始密码为学号后6位（不足6位取完整学号）'
    }
    ElMessage.success('学生名册导入完成')
    await loadStudents()
  } catch (err) {
    importErrorMessage.value =
      err?.response?.data?.message || err?.message || 'XLSX 校验未通过，请核对后重新上传'
    importErrors.value = extractImportErrors(err)
  } finally {
    importing.value = false
  }
}

// ==================== 新增学生弹窗 ====================
const createDialogVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  username: '',
  realName: '',
  deptClass: '',
  phone: '',
  password: ''
})

function openCreateDialog() {
  createForm.username = ''
  createForm.realName = ''
  createForm.deptClass = ''
  createForm.phone = ''
  createForm.password = ''
  createDialogVisible.value = true
}

async function submitCreateStudent() {
  const username = String(createForm.username || '').trim()
  const realName = String(createForm.realName || '').trim()
  const deptClass = String(createForm.deptClass || '').trim()
  const phone = String(createForm.phone || '').trim()
  const password = String(createForm.password || '').trim()

  if (!username) {
    ElMessage.warning('请填写学生学号')
    return
  }
  if (!realName) {
    ElMessage.warning('请填写学生姓名')
    return
  }
  if (password && (password.length < 4 || password.length > 64)) {
    ElMessage.warning('自定义初始密码长度需在 4~64 位之间')
    return
  }

  creating.value = true
  try {
    await createStudentApi({
      username,
      realName,
      deptClass,
      phone,
      password
    })
    ElMessage.success('新增学生账号成功')
    createDialogVisible.value = false
    await loadStudents()
  } finally {
    creating.value = false
  }
}

// ==================== 编辑学生弹窗 ====================
const editDialogVisible = ref(false)
const updating = ref(false)
const editingStudentId = ref(null)
const editForm = reactive({
  username: '',
  realName: '',
  deptClass: '',
  phone: '',
  status: 1
})

function openEditDialog(row) {
  editingStudentId.value = row.id
  editForm.username = row.username || ''
  editForm.realName = row.realName || ''
  editForm.deptClass = row.deptClass || ''
  editForm.phone = row.phone || ''
  editForm.status = row.status === 0 ? 0 : 1
  editDialogVisible.value = true
}

async function submitEditStudent() {
  if (!editingStudentId.value) return
  const realName = String(editForm.realName || '').trim()
  const deptClass = String(editForm.deptClass || '').trim()
  const phone = String(editForm.phone || '').trim()

  if (!realName) {
    ElMessage.warning('学生姓名不能为空')
    return
  }

  updating.value = true
  try {
    await updateStudentApi(editingStudentId.value, {
      realName,
      deptClass,
      phone,
      status: editForm.status === 0 ? 0 : 1
    })
    ElMessage.success('学生信息已更新')
    editDialogVisible.value = false
    await loadStudents()
  } finally {
    updating.value = false
  }
}

// ==================== 重置密码弹窗 ====================
const resetPwdDialogVisible = ref(false)
const resettingPwd = ref(false)
const resetTarget = ref(null)
const resetPwdInput = ref('')

function openResetPasswordDialog(row) {
  resetTarget.value = row
  resetPwdInput.value = ''
  resetPwdDialogVisible.value = true
}

async function submitResetPassword() {
  if (!resetTarget.value?.id) return
  const pwd = String(resetPwdInput.value || '').trim()
  if (pwd && (pwd.length < 4 || pwd.length > 64)) {
    ElMessage.warning('自定义新密码长度需在 4~64 位之间')
    return
  }

  resettingPwd.value = true
  try {
    const res = await resetStudentPasswordApi(resetTarget.value.id, pwd)
    const data = res?.data ?? res ?? {}
    const hint =
      data.passwordRuleHint || '密码已重置为学号后6位（不足6位取完整学号）'
    ElMessage.success(hint)
    resetPwdDialogVisible.value = false
  } finally {
    resettingPwd.value = false
  }
}

// ==================== 启用 / 禁用状态切换 ====================
function isStudentEnabled(row) {
  return row.status === 1 || row.status === undefined
}

async function handleToggleStatus(row) {
  const currentlyEnabled = isStudentEnabled(row)
  const nextStatus = currentlyEnabled ? 0 : 1
  const studentLabel = `${row.realName || '-'}（${row.username}）`
  const confirmMessage = currentlyEnabled
    ? `确定禁用学生「${studentLabel}」吗？禁用后该学生将无法登录 H5 端，历史答卷和报告不受影响。`
    : `确定启用学生「${studentLabel}」吗？启用后该学生可正常登录 H5 端参与评估。`
  const confirmTitle = currentlyEnabled ? '确认禁用学生账号' : '确认启用学生账号'

  try {
    await ElMessageBox.confirm(confirmMessage, confirmTitle, {
      type: 'warning',
      confirmButtonText: currentlyEnabled ? '确认禁用' : '确认启用',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }

  await updateStudentStatusApi(row.id, nextStatus)
  ElMessage.success(currentlyEnabled ? '学生账号已禁用' : '学生账号已启用')
  await loadStudents()
}

// ==================== 删除学生 ====================
async function handleDeleteStudent(row) {
  const studentLabel = `${row.realName || '-'}（${row.username}）`
  try {
    await ElMessageBox.confirm(
      `确定删除学生账号「${studentLabel}」吗？仅未交卷且不在已发布/已关闭活动名单中的学生允许物理删除。`,
      '确认删除学生账号',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }

  try {
    await deleteStudentApi(row.id, { silentError: true })
    ElMessage.success('学生账号已删除')
    await loadStudents()
  } catch (err) {
    const serverMsg =
      err?.response?.data?.message ||
      err?.message ||
      '该学生已有历史答卷或处于已发布/已关闭活动名单中，无法删除'
    const fullTip = serverMsg.includes('禁用')
      ? serverMsg
      : `${serverMsg}（请改用「禁用」功能以保障历史数据完整）`
    ElMessageBox.alert(fullTip, '无法删除（数据完整性保护）', {
      type: 'warning',
      confirmButtonText: '知道了'
    })
  }
}

onMounted(() => {
  loadStudents()
})
</script>

<template>
  <div class="page-container">
    <!-- 顶部工具栏 -->
    <div class="page-toolbar">
      <div class="toolbar-left">
        <h2 class="toolbar-title">学生名册</h2>
        <span class="toolbar-desc">
          统一维护参与评估的学生账号，导入新增账号默认口令为学号后 6 位
        </span>
      </div>
      <div class="toolbar-right">
        <el-button
          :icon="Download"
          :loading="templateDownloading"
          @click="handleDownloadTemplate"
        >
          下载导入模板
        </el-button>
        <el-button :icon="Upload" @click="openImportDialog">
          导入学生 XLSX
        </el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">
          新增学生
        </el-button>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="panel filter-panel">
      <div class="filter-bar">
        <div class="filter-left">
          <el-input
            v-model="filterForm.keyword"
            placeholder="搜索学号 / 姓名 / 学院班级"
            clearable
            style="width: 240px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <el-select
            v-model="filterForm.deptClass"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="筛选或输入学院班级"
            style="width: 210px"
            @change="handleSearch"
          >
            <el-option
              v-for="cls in deptClassOptions"
              :key="cls"
              :label="cls"
              :value="cls"
            />
          </el-select>
          <el-button type="primary" :icon="Search" @click="handleSearch">
            查询
          </el-button>
          <el-button :icon="RefreshLeft" @click="handleReset">
            重置
          </el-button>
        </div>
        <div class="filter-right tabular-nums">
          共 <strong>{{ students.length }}</strong> 名学生
        </div>
      </div>
    </div>

    <!-- 学生表格 -->
    <div class="panel">
      <el-table
        :data="students"
        v-loading="loading"
        empty-text="暂无符合条件的学生记录，可点击右上角「导入学生 XLSX」批量建号"
      >
        <el-table-column prop="username" label="学号" width="145">
          <template #default="{ row }">
            <span class="mono-code sno-text tabular-nums">{{ row.username }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="realName" label="姓名" min-width="140">
          <template #default="{ row }">
            <span class="name-text">{{ row.realName || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="deptClass" label="学院班级" min-width="165">
          <template #default="{ row }">
            <span>{{ row.deptClass || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="phone" label="手机号" width="140">
          <template #default="{ row }">
            <span class="tabular-nums">{{ row.phone || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column
          prop="participatedActivityCount"
          label="参与活动数"
          width="120"
          align="center"
        >
          <template #default="{ row }">
            <span class="tabular-nums">{{ row.participatedActivityCount ?? 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column
          prop="submittedSheetCount"
          label="已提交答卷数"
          width="130"
          align="center"
        >
          <template #default="{ row }">
            <strong class="tabular-nums submitted-num">{{ row.submittedSheetCount ?? 0 }}</strong>
          </template>
        </el-table-column>

        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="row.status === 1 || row.status === undefined"
              type="success"
              size="small"
              effect="plain"
            >
              正常
            </el-tag>
            <el-tag v-else type="info" size="small" effect="plain">
              已禁用
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="createdAt" label="创建时间" width="175" align="center">
          <template #default="{ row }">
            <span class="tabular-nums time-text">{{ row.createdAt || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="225" fixed="right" align="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" size="small" @click="openEditDialog(row)">
                编辑
              </el-button>
              <el-button link type="primary" size="small" @click="openResetPasswordDialog(row)">
                重置密码
              </el-button>
              <el-button link type="primary" size="small" @click="handleToggleStatus(row)">
                {{ isStudentEnabled(row) ? '禁用' : '启用' }}
              </el-button>
              <el-button link type="danger" size="small" @click="handleDeleteStudent(row)">
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 导入学生 XLSX 弹窗 -->
    <el-dialog
      v-model="importDialogVisible"
      title="导入学生 XLSX"
      width="620px"
      destroy-on-close
    >
      <div class="import-tip-box">
        <div>
          导入文件需为 <span class="mono-code">.xlsx</span>，列包含学号、姓名、学院班级、手机号；系统将自动为新学号建号，初始密码为<strong>学号后 6 位</strong>（不足 6 位取完整学号）。
        </div>
        <el-button
          link
          type="primary"
          size="small"
          :loading="templateDownloading"
          @click="handleDownloadTemplate"
        >
          下载标准模板
        </el-button>
      </div>

      <el-form label-position="top">
        <el-form-item label="选择学生名册文件 (.xlsx)" required>
          <el-upload
            ref="uploadRef"
            class="xlsx-uploader"
            action="#"
            accept=".xlsx"
            :auto-upload="false"
            :limit="1"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
            :on-exceed="handleFileExceed"
          >
            <el-button :icon="Upload">选择 .xlsx 文件</el-button>
            <template #tip>
              <div class="upload-sub-tip">
                支持单次上传 1 个 .xlsx 文件，校验不通过时不会写入任何数据
              </div>
            </template>
          </el-upload>
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="updateExisting">
            已存在学生同步更新姓名/班级/手机号（不重置密码）
          </el-checkbox>
        </el-form-item>
      </el-form>

      <!-- 导入成功汇总卡片 -->
      <div v-if="importResult" class="import-result-card">
        <div class="res-card-title">导入完成汇总</div>
        <div class="res-metrics-grid tabular-nums">
          <div class="res-metric-item">
            <span class="res-label">总行数</span>
            <strong class="res-val">{{ importResult.totalRows }}</strong>
          </div>
          <div class="res-metric-item">
            <span class="res-label">新建账号</span>
            <strong class="res-val primary">{{ importResult.createdCount }}</strong>
          </div>
          <div class="res-metric-item">
            <span class="res-label">更新资料</span>
            <strong class="res-val">{{ importResult.updatedCount }}</strong>
          </div>
          <div class="res-metric-item">
            <span class="res-label">跳过已有</span>
            <strong class="res-val muted">{{ importResult.skippedCount }}</strong>
          </div>
        </div>
        <div class="res-pwd-hint">
          口令提示：{{ importResult.passwordRuleHint }}
        </div>
      </div>

      <!-- 400 结构化行级错误表 -->
      <div v-if="importErrorMessage || importErrors.length" class="import-error-panel">
        <div class="err-panel-head">
          {{ importErrorMessage }}
        </div>
        <el-table
          v-if="importErrors.length"
          :data="importErrors"
          size="small"
          border
          max-height="240"
        >
          <el-table-column prop="sheet" label="Sheet" width="110" />
          <el-table-column prop="row" label="行号" width="70" align="center">
            <template #default="{ row }">
              <span class="tabular-nums">{{ row.row ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="column" label="列名" width="120" />
          <el-table-column prop="message" label="错误原因" min-width="200" />
        </el-table>
      </div>

      <template #footer>
        <el-button @click="importDialogVisible = false">
          {{ importResult ? '关闭' : '取消' }}
        </el-button>
        <el-button
          type="primary"
          :loading="importing"
          :disabled="!selectedFile"
          @click="submitImportStudents"
        >
          开始导入
        </el-button>
      </template>
    </el-dialog>

    <!-- 新增学生弹窗 -->
    <el-dialog
      v-model="createDialogVisible"
      title="新增学生"
      width="520px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="学号" required>
          <el-input
            v-model="createForm.username"
            placeholder="登录账号，全表唯一，例如 20260199"
            maxlength="64"
            clearable
          />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input
            v-model="createForm.realName"
            placeholder="学生真实姓名"
            maxlength="64"
            clearable
          />
        </el-form-item>
        <el-form-item label="学院班级（选填）">
          <el-input
            v-model="createForm.deptClass"
            placeholder="例如：软件2542班"
            maxlength="128"
            clearable
          />
        </el-form-item>
        <el-form-item label="手机号（选填）">
          <el-input
            v-model="createForm.phone"
            placeholder="11 位手机号"
            maxlength="20"
            clearable
          />
        </el-form-item>
        <el-form-item label="初始密码（选填）">
          <el-input
            v-model="createForm.password"
            type="password"
            show-password
            placeholder="留空默认取学号后 6 位"
            maxlength="64"
            clearable
          />
          <div class="field-sub-tip">
            自定义密码长度需在 4~64 位之间；留空则取学号后 6 位（不足 6 位取完整学号）
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreateStudent">
          确认新增
        </el-button>
      </template>
    </el-dialog>

    <!-- 编辑学生弹窗 -->
    <el-dialog
      v-model="editDialogVisible"
      title="编辑学生"
      width="520px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="学号（不可修改）">
          <span class="mono-code sno-text tabular-nums readonly-sno">{{ editForm.username }}</span>
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input
            v-model="editForm.realName"
            placeholder="学生真实姓名"
            maxlength="64"
            clearable
          />
        </el-form-item>
        <el-form-item label="学院班级（选填）">
          <el-input
            v-model="editForm.deptClass"
            placeholder="例如：软件2542班"
            maxlength="128"
            clearable
          />
        </el-form-item>
        <el-form-item label="手机号（选填）">
          <el-input
            v-model="editForm.phone"
            placeholder="11 位手机号"
            maxlength="20"
            clearable
          />
        </el-form-item>
        <el-form-item label="账号状态">
          <el-radio-group v-model="editForm.status">
            <el-radio :value="1">正常</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
          <div class="field-sub-tip">
            禁用后该学生将无法登录 H5 端，历史答卷和报告不受影响
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="updating" @click="submitEditStudent">
          保存修改
        </el-button>
      </template>
    </el-dialog>

    <!-- 重置密码弹窗 -->
    <el-dialog
      v-model="resetPwdDialogVisible"
      title="重置学生密码"
      width="460px"
      destroy-on-close
    >
      <div class="reset-target-line">
        重置对象：
        <span class="name-text">{{ resetTarget?.realName || '-' }}</span>
        <span class="mono-code sno-text tabular-nums">（{{ resetTarget?.username }}）</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="新密码（选填）">
          <el-input
            v-model="resetPwdInput"
            type="password"
            show-password
            placeholder="留空则重置为学号后 6 位"
            maxlength="64"
            clearable
          />
          <div class="field-sub-tip">
            留空直接重置为学号后 6 位（不足 6 位取完整学号）；自定义密码长度需在 4~64 位之间
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetPwdDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="resettingPwd" @click="submitResetPassword">
          确认重置
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-panel {
  padding: 12px 20px;
}

.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.filter-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.filter-right {
  font-size: 13px;
  color: #4B5563;
}

.filter-right strong {
  color: #2055A4;
  font-weight: 600;
}

.sno-text {
  color: #2055A4;
  font-weight: 600;
}

.name-text {
  font-weight: 500;
  color: #111827;
}

.submitted-num {
  color: #2055A4;
}

.time-text {
  font-size: 12px;
  color: #6B7280;
}

.import-tip-box {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  margin-bottom: 16px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
  font-size: 12.5px;
  line-height: 1.6;
  color: #4B5563;
}

.xlsx-uploader {
  width: 100%;
}

.upload-sub-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #6B7280;
}

.import-result-card {
  margin-top: 8px;
  padding: 14px 16px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #D0DDF0;
}

.res-card-title {
  font-size: 13px;
  font-weight: 600;
  color: #2055A4;
  margin-bottom: 10px;
}

.res-metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}

.res-metric-item {
  padding: 8px 10px;
  border-radius: 4px;
  background-color: #FFFFFF;
  border: 1px solid #E5E7EB;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.res-label {
  font-size: 12px;
  color: #6B7280;
}

.res-val {
  font-size: 18px;
  font-weight: 600;
  color: #111827;
}

.res-val.primary {
  color: #2055A4;
}

.res-val.muted {
  color: #6B7280;
}

.res-pwd-hint {
  margin-top: 10px;
  font-size: 12px;
  color: #4B5563;
}

.import-error-panel {
  margin-top: 10px;
  padding: 12px;
  border-radius: 6px;
  background-color: #FEF2F2;
  border: 1px solid #FECACA;
}

.err-panel-head {
  font-size: 12.5px;
  font-weight: 600;
  color: #DC2626;
  margin-bottom: 8px;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  white-space: nowrap;
}

.readonly-sno {
  display: inline-block;
  padding: 0 10px;
  line-height: 30px;
  border-radius: 4px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
}

.field-sub-tip {
  width: 100%;
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: #6B7280;
}

.reset-target-line {
  margin-bottom: 14px;
  padding: 10px 14px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
  font-size: 13px;
  color: #4B5563;
}
</style>
