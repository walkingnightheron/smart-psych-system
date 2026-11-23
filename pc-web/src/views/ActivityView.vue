<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  getActivitiesApi,
  getActivityDetailApi,
  getActivityStatisticsApi,
  createActivityApi,
  updateActivityApi,
  publishActivityApi,
  closeActivityApi,
  getAllPublishedVersionsApi,
  getUsersApi
} from '@/api/admin'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const activities = ref([])
const publishedVersions = ref([])
const studentOptions = ref([])
const adminOptions = ref([])

// ==================== 新建/编辑草稿活动弹窗 ====================
const formDialogVisible = ref(false)
const formLoading = ref(false)
const formSaving = ref(false)
const editingActivityId = ref(null)
const studentKeyword = ref('')

const activityForm = reactive({
  title: '',
  questionnaireVersionId: null,
  timeRange: [],
  participantUserIds: [],
  authorizedAdminIds: []
})

const coAdminCandidates = computed(() => {
  const myId = userStore.profile?.id
  return adminOptions.value.filter((a) => a.id !== myId)
})

const filteredStudents = computed(() => {
  const kw = studentKeyword.value.trim().toLowerCase()
  if (!kw) return studentOptions.value
  return studentOptions.value.filter(
    (s) =>
      String(s.username || '').toLowerCase().includes(kw) ||
      String(s.realName || '').toLowerCase().includes(kw) ||
      String(s.deptClass || '').toLowerCase().includes(kw)
  )
})

async function loadActivities() {
  loading.value = true
  try {
    const res = await getActivitiesApi()
    const list = res?.data ?? res ?? []
    activities.value = Array.isArray(list) ? list : []
  } finally {
    loading.value = false
  }
}

async function ensureReferenceData() {
  const [verRes, stuRes, admRes] = await Promise.all([
    getAllPublishedVersionsApi(),
    getUsersApi('STUDENT'),
    getUsersApi('ADMIN')
  ])
  publishedVersions.value = Array.isArray(verRes?.data ?? verRes) ? (verRes?.data ?? verRes) : []
  studentOptions.value = Array.isArray(stuRes?.data ?? stuRes) ? (stuRes?.data ?? stuRes) : []
  adminOptions.value = Array.isArray(admRes?.data ?? admRes) ? (admRes?.data ?? admRes) : []
}

function getStatusMeta(status) {
  if (status === 'PUBLISHED') return { label: '已发布', type: 'success' }
  if (status === 'CLOSED') return { label: '已关闭', type: 'info' }
  return { label: '草稿', type: 'warning' }
}

function isOwner(row) {
  if (row.isOwner !== undefined) return Boolean(row.isOwner)
  if (row.ownerId !== undefined && userStore.profile?.id !== undefined) {
    return Number(row.ownerId) === Number(userStore.profile.id)
  }
  return true
}

function formatVersionLabel(row) {
  if (row.versionNum && row.ruleVersion) {
    return `v${row.versionNum} · ${row.ruleVersion}`
  }
  if (row.versionNum) return `v${row.versionNum}`
  const found = publishedVersions.value.find((v) => v.id === row.questionnaireVersionId)
  if (found) {
    return `v${found.versionNum} · ${found.ruleVersion}`
  }
  return row.questionnaireVersionId ? `版本 #${row.questionnaireVersionId}` : '-'
}

async function openCreateDialog() {
  editingActivityId.value = null
  studentKeyword.value = ''
  activityForm.title = ''
  activityForm.questionnaireVersionId = null
  activityForm.timeRange = ['2026-11-23 00:00:00', '2026-12-31 23:59:59']
  activityForm.participantUserIds = []
  activityForm.authorizedAdminIds = []
  formDialogVisible.value = true
  formLoading.value = true
  try {
    await ensureReferenceData()
    if (publishedVersions.value.length > 0) {
      activityForm.questionnaireVersionId = publishedVersions.value[0].id
    }
  } finally {
    formLoading.value = false
  }
}

async function openEditDialog(row) {
  editingActivityId.value = row.id
  studentKeyword.value = ''
  formDialogVisible.value = true
  formLoading.value = true
  try {
    await ensureReferenceData()
    const res = await getActivityDetailApi(row.id)
    const detail = res?.data ?? res ?? {}

    activityForm.title = detail.title || row.title || ''
    activityForm.questionnaireVersionId =
      detail.questionnaireVersionId ?? row.questionnaireVersionId ?? null
    activityForm.timeRange =
      detail.startTime && detail.endTime ? [detail.startTime, detail.endTime] : []

    if (Array.isArray(detail.participantUserIds)) {
      activityForm.participantUserIds = detail.participantUserIds.map(Number)
    } else if (Array.isArray(detail.participants)) {
      activityForm.participantUserIds = detail.participants.map((p) => Number(p.userId ?? p.id))
    } else {
      activityForm.participantUserIds = []
    }

    let authIds = detail.authorizedAdminIds ?? []
    if (typeof authIds === 'string') {
      try {
        authIds = JSON.parse(authIds)
      } catch {
        authIds = []
      }
    }
    const myId = Number(userStore.profile?.id)
    activityForm.authorizedAdminIds = Array.isArray(authIds)
      ? authIds.map(Number).filter((id) => id !== myId)
      : []
  } finally {
    formLoading.value = false
  }
}

function selectSoftware2542() {
  const ids = studentOptions.value
    .filter((s) => String(s.deptClass || '').includes('软件2542'))
    .map((s) => s.id)
  if (!ids.length) {
    ElMessage.info('未找到软件2542班学生数据')
    return
  }
  const merged = new Set([...activityForm.participantUserIds, ...ids])
  activityForm.participantUserIds = Array.from(merged)
  ElMessage.success(`已选中软件2542班共 ${ids.length} 名学生`)
}

function selectAllStudents() {
  activityForm.participantUserIds = studentOptions.value.map((s) => s.id)
}

function clearStudents() {
  activityForm.participantUserIds = []
}

async function submitActivityForm() {
  if (!activityForm.title.trim()) {
    ElMessage.warning('请输入评估活动名称')
    return
  }
  if (!activityForm.questionnaireVersionId) {
    ElMessage.warning('请选择固定绑定的已发布问卷版本')
    return
  }
  if (!activityForm.timeRange || activityForm.timeRange.length !== 2) {
    ElMessage.warning('请设置活动开放起止时间窗')
    return
  }
  if (activityForm.timeRange[0] >= activityForm.timeRange[1]) {
    ElMessage.warning('开放开始时间必须早于结束时间')
    return
  }
  if (!activityForm.participantUserIds.length) {
    ElMessage.warning('请至少勾选 1 名目标参与学生')
    return
  }

  const payload = {
    title: activityForm.title.trim(),
    questionnaireVersionId: Number(activityForm.questionnaireVersionId),
    startTime: activityForm.timeRange[0],
    endTime: activityForm.timeRange[1],
    participantUserIds: activityForm.participantUserIds.map(Number),
    authorizedAdminIds: activityForm.authorizedAdminIds.map(Number)
  }

  formSaving.value = true
  try {
    if (editingActivityId.value) {
      await updateActivityApi(editingActivityId.value, payload)
      ElMessage.success('草稿活动已更新')
    } else {
      await createActivityApi(payload)
      ElMessage.success('评估活动草稿已创建')
    }
    formDialogVisible.value = false
    await loadActivities()
  } finally {
    formSaving.value = false
  }
}

async function handlePublish(row) {
  try {
    await ElMessageBox.confirm(
      `确定发布活动「${row.title}」吗？发布后将锁定所绑定的问卷版本，目标名单内的学生在开放时间窗内可登录 H5 端作答。`,
      '发布活动确认',
      {
        type: 'warning',
        confirmButtonText: '确认发布',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }
  await publishActivityApi(row.id)
  ElMessage.success('活动已发布')
  await loadActivities()
}

async function handleClose(row) {
  try {
    await ElMessageBox.confirm(
      `确定立即关闭活动「${row.title}」吗？关闭后将同步清理活动缓存并停止接收新答卷，已有答卷和评估报告将完整保留。`,
      '关闭活动确认',
      {
        type: 'warning',
        confirmButtonText: '确认关闭',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }
  await closeActivityApi(row.id)
  ElMessage.success('活动已关闭')
  await loadActivities()
}

// ==================== 活动详情与名单抽屉 ====================
const detailDrawerVisible = ref(false)
const detailLoading = ref(false)
const activityDetail = ref(null)
const activityStats = ref(null)

const detailParticipants = computed(() => {
  const d = activityDetail.value
  const statsList = Array.isArray(activityStats.value?.participantDetails)
    ? activityStats.value.participantDetails
    : []
  const statsById = new Map()
  const statsByUsername = new Map()
  for (const item of statsList) {
    const uid = item.userId ?? item.id
    if (uid !== undefined && uid !== null) {
      statsById.set(Number(uid), item)
    }
    if (item.username) {
      statsByUsername.set(String(item.username), item)
    }
  }

  const mergeWithStat = (raw) => {
    const uid = raw.userId ?? raw.id
    const stat =
      (uid !== undefined && uid !== null ? statsById.get(Number(uid)) : undefined) ||
      (raw.username ? statsByUsername.get(String(raw.username)) : undefined)
    const submitted =
      stat?.submitted !== undefined
        ? Boolean(stat.submitted)
        : Boolean(raw.submitted ?? raw.completed ?? raw.submittedAt)
    const submittedAt = stat?.submittedAt ?? raw.submittedAt ?? null
    return {
      ...raw,
      ...(stat || {}),
      userId: stat?.userId ?? uid,
      username: stat?.username ?? raw.username ?? uid,
      realName: stat?.realName ?? raw.realName ?? '-',
      deptClass: stat?.deptClass ?? raw.deptClass ?? '-',
      submitted,
      submittedAt
    }
  }

  if (!d) return statsList.map(mergeWithStat)
  if (Array.isArray(d.participants) && d.participants.length > 0) {
    return d.participants.map(mergeWithStat)
  }
  if (statsList.length > 0) {
    return statsList.map(mergeWithStat)
  }
  if (Array.isArray(d.participantList)) {
    return d.participantList.map(mergeWithStat)
  }
  if (Array.isArray(d.participantUserIds)) {
    return d.participantUserIds.map((uid) => {
      const stu = studentOptions.value.find((s) => Number(s.id) === Number(uid))
      return mergeWithStat({
        userId: uid,
        username: stu?.username || uid,
        realName: stu?.realName || '-',
        deptClass: stu?.deptClass || '-'
      })
    })
  }
  return []
})

const detailAuthorizedAdmins = computed(() => {
  const d = activityDetail.value
  if (!d) return []
  if (Array.isArray(d.authorizedAdmins)) return d.authorizedAdmins
  let ids = d.authorizedAdminIds ?? []
  if (typeof ids === 'string') {
    try {
      ids = JSON.parse(ids)
    } catch {
      ids = []
    }
  }
  if (!Array.isArray(ids)) return []
  return ids.map((aid) => {
    const adm = adminOptions.value.find((a) => Number(a.id) === Number(aid))
    return adm || { id: aid, username: `ID:${aid}`, realName: `管理员#${aid}`, deptClass: '-' }
  })
})

async function openDetailDrawer(row) {
  detailDrawerVisible.value = true
  detailLoading.value = true
  activityDetail.value = null
  activityStats.value = null
  try {
    if (!studentOptions.value.length || !adminOptions.value.length) {
      await ensureReferenceData()
    }
    const [detailRes, statsRes] = await Promise.all([
      getActivityDetailApi(row.id),
      getActivityStatisticsApi(row.id).catch(() => null)
    ])
    activityDetail.value = detailRes?.data ?? detailRes ?? null
    activityStats.value = statsRes?.data ?? statsRes ?? null
  } finally {
    detailLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadActivities(), ensureReferenceData()])
})
</script>

<template>
  <div class="page-container">
    <!-- 顶部工具栏 -->
    <div class="page-toolbar">
      <div class="toolbar-left">
        <h2 class="toolbar-title">评估活动管理</h2>
        <span class="toolbar-desc">
          创建评估活动并绑定已发布问卷版本，管理学生参与名单、协同管理员授权及活动状态流转
        </span>
      </div>
      <div class="toolbar-right">
        <el-button @click="loadActivities">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">
          新建活动
        </el-button>
      </div>
    </div>

    <!-- 活动列表表格 -->
    <div class="panel">
      <el-table
        :data="activities"
        v-loading="loading"
        empty-text="当前账号名下暂无负责或获授权的评估活动"
      >
        <el-table-column prop="id" label="编号" width="75" align="center">
          <template #default="{ row }">
            <span class="mono-code">#{{ row.id }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="title" label="评估活动名称" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="act-main-title">{{ row.title }}</div>
            <div v-if="row.questionnaireTitle" class="act-sub-q">
              问卷：{{ row.questionnaireTitle }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="绑定版本" width="145" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="primary" effect="plain" class="mono-code">
              {{ formatVersionLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="105" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusMeta(row.status).type" size="small" effect="plain">
              {{ getStatusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="权限归属" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="isOwner(row)" size="small" type="primary" effect="plain">
              本人负责
            </el-tag>
            <el-tag v-else size="small" type="info" effect="plain">
              协同授权
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="开放时间窗" min-width="190">
          <template #default="{ row }">
            <div class="window-cell tabular-nums">
              <div>{{ row.startTime || '-' }}</div>
              <div>{{ row.endTime || '-' }}</div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="完成进度" width="130" align="center">
          <template #default="{ row }">
            <span v-if="row.participantCount !== undefined" class="tabular-nums">
              <strong>{{ row.completedCount ?? 0 }}</strong> / {{ row.participantCount }} 人
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="240" fixed="right" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetailDrawer(row)">
              详情
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              link
              type="primary"
              size="small"
              @click="openEditDialog(row)"
            >
              编辑草稿
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              link
              type="primary"
              size="small"
              @click="handlePublish(row)"
            >
              发布
            </el-button>
            <el-button
              v-if="row.status === 'PUBLISHED'"
              link
              type="danger"
              size="small"
              @click="handleClose(row)"
            >
              关闭
            </el-button>
            <el-button
              link
              type="primary"
              size="small"
              @click="router.push({ path: '/statistics', query: { activityId: row.id } })"
            >
              统计
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建 / 编辑草稿活动弹窗 -->
    <el-dialog
      v-model="formDialogVisible"
      :title="editingActivityId ? '编辑评估活动草稿' : '新建评估活动'"
      width="720px"
      destroy-on-close
    >
      <div v-loading="formLoading">
        <el-form label-position="top">
          <el-form-item label="评估活动名称 (title)" required>
            <el-input
              v-model="activityForm.title"
              placeholder="例如：2026秋季学期软件2542班心理适应普查"
              maxlength="128"
              clearable
            />
          </el-form-item>

          <div class="two-col-form">
            <el-form-item label="绑定已发布问卷版本 (questionnaireVersionId)" required>
              <el-select
                v-model="activityForm.questionnaireVersionId"
                placeholder="请选择已发布问卷版本"
                style="width: 100%"
              >
                <el-option
                  v-for="ver in publishedVersions"
                  :key="ver.id"
                  :value="ver.id"
                  :label="`${ver.title}（v${ver.versionNum} · ${ver.ruleVersion}）`"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="活动开放时间窗 (startTime ~ endTime)" required>
              <el-date-picker
                v-model="activityForm.timeRange"
                type="datetimerange"
                range-separator="至"
                start-placeholder="开始时间"
                end-placeholder="结束时间"
                value-format="YYYY-MM-DD HH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>
          </div>

          <!-- 学生名单选择器 -->
          <el-form-item required>
            <template #label>
              <div class="roster-label-bar">
                <span class="tabular-nums">
                  目标参与学生名单 · 已选
                  <strong>{{ activityForm.participantUserIds.length }}</strong> 人
                </span>
                <div class="quick-roster-btns">
                  <el-button size="small" type="primary" plain @click="selectSoftware2542">
                    全选软件2542班
                  </el-button>
                  <el-button size="small" @click="selectAllStudents">全选全部学生</el-button>
                  <el-button size="small" @click="clearStudents">清空</el-button>
                </div>
              </div>
            </template>

            <div class="student-picker-box">
              <el-input
                v-model="studentKeyword"
                size="small"
                placeholder="按学号、姓名或班级筛选学生..."
                clearable
                style="margin-bottom: 10px"
              />
              <el-checkbox-group
                v-model="activityForm.participantUserIds"
                class="student-checkbox-grid"
              >
                <el-checkbox
                  v-for="stu in filteredStudents"
                  :key="stu.id"
                  :value="stu.id"
                  border
                  class="stu-check-item"
                >
                  <span class="mono-code stu-no">{{ stu.username }}</span>
                  <span class="stu-name">{{ stu.realName }}</span>
                  <el-tag size="small" type="info" effect="plain">{{ stu.deptClass }}</el-tag>
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </el-form-item>

          <!-- 协同管理员授权 -->
          <el-form-item label="协同授权管理员（可选，授权院系辅导员查看进度、统计与报告）">
            <el-select
              v-model="activityForm.authorizedAdminIds"
              multiple
              clearable
              placeholder="选择需要共享本活动查看权限的管理员"
              style="width: 100%"
            >
              <el-option
                v-for="adm in coAdminCandidates"
                :key="adm.id"
                :value="adm.id"
                :label="`${adm.realName}（@${adm.username} · ${adm.deptClass || '管理员'}）`"
              />
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="formSaving" @click="submitActivityForm">
          {{ editingActivityId ? '保存修改' : '创建活动' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 活动详情与名单完成状态抽屉 -->
    <el-drawer
      v-model="detailDrawerVisible"
      title="评估活动详情与参与名单"
      size="680px"
      destroy-on-close
    >
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="activityDetail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="活动编号">
              <span class="mono-code">#{{ activityDetail.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="活动状态">
              <el-tag :type="getStatusMeta(activityDetail.status).type" size="small" effect="plain">
                {{ getStatusMeta(activityDetail.status).label }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="活动名称" :span="2">
              <strong>{{ activityDetail.title }}</strong>
            </el-descriptions-item>
            <el-descriptions-item label="绑定问卷版本" :span="2">
              {{ activityDetail.questionnaireTitle || '已发布问卷版本' }}
              <el-tag size="small" type="primary" effect="plain" class="mono-code" style="margin-left: 8px">
                {{ formatVersionLabel(activityDetail) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="开放开始时间">
              <span class="tabular-nums">{{ activityDetail.startTime || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="开放结束时间">
              <span class="tabular-nums">{{ activityDetail.endTime || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="完成进度" :span="2">
              <span class="tabular-nums">
                已完成 <strong>{{ activityStats?.completedCount ?? activityDetail.completedCount ?? 0 }}</strong> /
                <strong>{{ activityStats?.totalParticipants ?? activityDetail.participantCount ?? detailParticipants.length }}</strong> 人
                <span
                  v-if="(activityStats?.completionRate ?? activityDetail.completionRate) !== undefined"
                  class="detail-rate"
                >
                  （完成率 {{ Number(activityStats?.completionRate ?? activityDetail.completionRate ?? 0).toFixed(2) }}%）
                </span>
              </span>
            </el-descriptions-item>
          </el-descriptions>

          <div class="drawer-sec-title">
            <span>协同授权管理员</span>
          </div>
          <div class="co-admin-box">
            <span v-if="!detailAuthorizedAdmins.length" class="muted-note">
              未额外授权其他管理员（仅活动创建负责人可管理与查看）
            </span>
            <el-tag
              v-for="adm in detailAuthorizedAdmins"
              :key="adm.id"
              type="primary"
              effect="plain"
              style="margin-right: 8px; margin-bottom: 6px"
            >
              {{ adm.realName }} (@{{ adm.username }} · {{ adm.deptClass || '-' }})
            </el-tag>
          </div>

          <div class="drawer-sec-title">
            <span class="tabular-nums">目标参与学生名单（共 {{ detailParticipants.length }} 人）</span>
            <el-button
              size="small"
              type="primary"
              plain
              @click="
                detailDrawerVisible = false;
                router.push({ path: '/statistics', query: { activityId: activityDetail.id } })
              "
            >
              查看统计与报告
            </el-button>
          </div>

          <el-table :data="detailParticipants" size="small" border>
            <el-table-column prop="username" label="学号" width="120">
              <template #default="{ row }">
                <span class="mono-code">{{ row.username }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="realName" label="姓名" min-width="140" />
            <el-table-column prop="deptClass" label="所属班级" min-width="130" />
            <el-table-column label="交卷状态" width="110" align="center">
              <template #default="{ row }">
                <el-tag
                  v-if="row.submitted === true || row.completed === true || row.submittedAt"
                  size="small"
                  type="success"
                  effect="plain"
                >
                  已交卷
                </el-tag>
                <el-tag v-else size="small" type="info" effect="plain">
                  待填写
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="submittedAt" label="提交时间" width="165" align="center">
              <template #default="{ row }">
                <span class="tabular-nums time-cell">{{ row.submittedAt || '-' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.act-main-title {
  font-weight: 500;
  color: #111827;
}

.act-sub-q {
  margin-top: 2px;
  font-size: 12px;
  color: #6B7280;
}

.window-cell {
  font-size: 12px;
  line-height: 1.45;
  color: #6B7280;
}

.two-col-form {
  display: grid;
  grid-template-columns: 1fr 1.15fr;
  gap: 14px;
}

.roster-label-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 12px;
  flex-wrap: wrap;
}

.quick-roster-btns {
  display: flex;
  gap: 6px;
}

.student-picker-box {
  width: 100%;
  padding: 12px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
}

.student-checkbox-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  max-height: 220px;
  overflow-y: auto;
}

.stu-check-item {
  margin-right: 0 !important;
  height: auto !important;
  padding: 8px 10px !important;
  background-color: #FFFFFF;
}

.stu-no {
  font-weight: 600;
  color: #2055A4;
  margin-right: 6px;
}

.stu-name {
  margin-right: 6px;
  color: #111827;
}

.detail-rate {
  margin-left: 8px;
  color: #2055A4;
  font-weight: 600;
}

.drawer-sec-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin: 18px 0 10px;
  font-size: 13.5px;
  font-weight: 600;
  color: #111827;
}

.co-admin-box {
  padding: 10px 12px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
}

.muted-note {
  font-size: 12px;
  color: #6B7280;
}

.time-cell {
  font-size: 12px;
  color: #6B7280;
}
</style>
