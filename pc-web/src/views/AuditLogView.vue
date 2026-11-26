<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Search, RefreshLeft, Refresh } from '@element-plus/icons-vue'
import { getAuditLogsApi } from '@/api/admin'

const loading = ref(false)
const errorMessage = ref('')

const filterForm = reactive({
  keyword: '',
  actionType: '',
  resourceType: '',
  timeRange: null
})

// ==================== 分页（兼容服务端分页与纯数组两种返回） ====================
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const serverPaged = ref(false)
const rows = ref([])
const allRows = ref([])

const ACTION_LABELS = {
  CREATE: '新增',
  UPDATE: '修改',
  DELETE: '删除',
  PUBLISH: '发布',
  CLOSE: '关闭',
  IMPORT: '导入',
  EXPORT: '导出',
  LOGIN: '登录',
  LOGOUT: '退出登录',
  RESET_PASSWORD: '重置密码',
  RETRY: '重试',
  EVICT: '缓存清理',
  SUBMIT: '提交',
  GENERATE: '生成'
}

const RESOURCE_LABELS = {
  QUESTIONNAIRE: '问卷',
  QUESTIONNAIRE_VERSION: '问卷版本',
  ACTIVITY: '评估活动',
  STUDENT: '学生账号',
  REPORT: '评估报告',
  LLM_CONFIG: 'AI 配置',
  LLM_PROVIDER: '模型供应商',
  USER: '用户账号',
  CACHE: '缓存',
  TEMPLATE: '导入模板'
}

const seenActionTypes = ref([])
const seenResourceTypes = ref([])

const actionTypeOptions = computed(() => {
  const base = Object.keys(ACTION_LABELS)
  const extra = seenActionTypes.value.filter((v) => !base.includes(v))
  return [...base, ...extra]
})

const resourceTypeOptions = computed(() => {
  const base = Object.keys(RESOURCE_LABELS)
  const extra = seenResourceTypes.value.filter((v) => !base.includes(v))
  return [...base, ...extra]
})

const hasActiveFilter = computed(() => {
  return Boolean(
    filterForm.keyword.trim() ||
      filterForm.actionType ||
      filterForm.resourceType ||
      (filterForm.timeRange && filterForm.timeRange.length === 2)
  )
})

const emptyDescription = computed(() =>
  hasActiveFilter.value
    ? '未找到符合筛选条件的操作日志，可调整关键词、类型或时间范围后重试'
    : '暂无操作日志记录'
)

function actionLabel(code) {
  return ACTION_LABELS[code] || code || '-'
}

function resourceLabel(code) {
  return RESOURCE_LABELS[code] || code || ''
}

function actionTagType(code) {
  if (code === 'CREATE' || code === 'IMPORT' || code === 'PUBLISH') return 'success'
  if (code === 'DELETE' || code === 'CLOSE') return 'danger'
  if (code === 'UPDATE' || code === 'RESET_PASSWORD' || code === 'EVICT') return 'warning'
  return 'info'
}

function formatTime(val) {
  if (val === null || val === undefined || val === '') return '-'
  const d =
    val instanceof Date
      ? val
      : new Date(typeof val === 'number' ? val : String(val).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return String(val)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes()
  )}:${p(d.getSeconds())}`
}

function normalizeLog(row) {
  const operator = row?.operator
  const operatorObj = operator && typeof operator === 'object' ? operator : {}
  return {
    id: row?.id ?? row?.logId ?? null,
    operatorName:
      row?.operatorName ?? row?.realName ?? operatorObj.realName ?? operatorObj.name ?? '',
    operatorUsername:
      row?.username ??
      row?.operatorUsername ??
      operatorObj.username ??
      (typeof operator === 'string' ? operator : null) ??
      row?.actor ??
      '',
    actionType: String(row?.actionType ?? row?.action ?? row?.operationType ?? '').toUpperCase(),
    resourceType: String(row?.resourceType ?? row?.resource ?? row?.targetType ?? '').toUpperCase(),
    resourceId: row?.resourceId ?? row?.targetId ?? '',
    summary: row?.summary ?? row?.detail ?? row?.description ?? row?.message ?? row?.remark ?? '',
    time: row?.createdAt ?? row?.occurredAt ?? row?.operationTime ?? row?.time ?? row?.timestamp ?? ''
  }
}

function collectSeenTypes(list) {
  for (const item of list) {
    if (item.actionType && !seenActionTypes.value.includes(item.actionType)) {
      seenActionTypes.value.push(item.actionType)
    }
    if (item.resourceType && !seenResourceTypes.value.includes(item.resourceType)) {
      seenResourceTypes.value.push(item.resourceType)
    }
  }
}

function applyClientPage() {
  const start = (page.value - 1) * pageSize.value
  rows.value = allRows.value.slice(start, start + pageSize.value)
}

async function loadLogs() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [startTime, endTime] =
      filterForm.timeRange && filterForm.timeRange.length === 2 ? filterForm.timeRange : ['', '']
    const res = await getAuditLogsApi(
      {
        keyword: filterForm.keyword,
        actionType: filterForm.actionType,
        resourceType: filterForm.resourceType,
        startTime,
        endTime,
        page: page.value,
        pageSize: pageSize.value
      },
      { silentError: true }
    )
    const body = res?.data ?? res

    if (Array.isArray(body)) {
      // 后端直接返回数组：前端本地分页
      serverPaged.value = false
      allRows.value = body.map(normalizeLog)
      collectSeenTypes(allRows.value)
      total.value = allRows.value.length
      applyClientPage()
    } else if (body && typeof body === 'object') {
      const items = body.items ?? body.list ?? body.records ?? body.rows
      if (Array.isArray(items)) {
        const normalized = items.map(normalizeLog)
        collectSeenTypes(normalized)
        if (items.length > pageSize.value) {
          // 后端未实际分页（一次性返回全量）：退化为前端本地分页
          serverPaged.value = false
          allRows.value = normalized
          total.value = Number(body.total ?? normalized.length) || normalized.length
          applyClientPage()
        } else {
          serverPaged.value = true
          rows.value = normalized
          total.value = Number(body.total ?? normalized.length) || 0
        }
      } else {
        serverPaged.value = false
        allRows.value = []
        rows.value = []
        total.value = 0
      }
    } else {
      serverPaged.value = false
      allRows.value = []
      rows.value = []
      total.value = 0
    }
  } catch (err) {
    errorMessage.value =
      err?.response?.data?.message || err?.message || '操作日志加载失败，请稍后重试'
    rows.value = []
    allRows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadLogs()
}

function handleReset() {
  filterForm.keyword = ''
  filterForm.actionType = ''
  filterForm.resourceType = ''
  filterForm.timeRange = null
  page.value = 1
  loadLogs()
}

function handlePageChange(nextPage) {
  page.value = nextPage
  if (serverPaged.value) {
    loadLogs()
  } else {
    applyClientPage()
  }
}

function handleSizeChange(nextSize) {
  pageSize.value = nextSize
  page.value = 1
  if (serverPaged.value) {
    loadLogs()
  } else {
    applyClientPage()
  }
}

onMounted(() => {
  loadLogs()
})
</script>

<template>
  <div class="page-container">
    <!-- 顶部工具栏 -->
    <div class="page-toolbar">
      <div class="toolbar-left">
        <h2 class="toolbar-title">操作日志</h2>
        <span class="toolbar-desc">
          记录管理端关键操作的审计轨迹，支持按操作人、动作类型、资源与时间范围检索
        </span>
      </div>
      <div class="toolbar-right">
        <el-button :icon="Refresh" :loading="loading" @click="loadLogs">刷新</el-button>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="panel filter-panel">
      <div class="filter-bar">
        <div class="filter-left">
          <el-input
            v-model="filterForm.keyword"
            placeholder="搜索操作人 / 摘要关键词"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <el-select
            v-model="filterForm.actionType"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="操作类型"
            style="width: 170px"
            @change="handleSearch"
          >
            <el-option
              v-for="code in actionTypeOptions"
              :key="code"
              :label="ACTION_LABELS[code] ? `${code} · ${ACTION_LABELS[code]}` : code"
              :value="code"
            />
          </el-select>
          <el-select
            v-model="filterForm.resourceType"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="资源类型"
            style="width: 190px"
            @change="handleSearch"
          >
            <el-option
              v-for="code in resourceTypeOptions"
              :key="code"
              :label="RESOURCE_LABELS[code] ? `${code} · ${RESOURCE_LABELS[code]}` : code"
              :value="code"
            />
          </el-select>
          <el-date-picker
            v-model="filterForm.timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 360px"
            @change="handleSearch"
          />
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="RefreshLeft" @click="handleReset">重置</el-button>
        </div>
        <div class="filter-right tabular-nums">
          共 <strong>{{ total }}</strong> 条记录
        </div>
      </div>
    </div>

    <!-- 加载失败提示 -->
    <div v-if="errorMessage" class="panel error-panel">
      <span class="error-text">{{ errorMessage }}</span>
      <el-button size="small" type="primary" plain @click="loadLogs">重试</el-button>
    </div>

    <!-- 日志表格 -->
    <div class="panel">
      <el-table :data="rows" v-loading="loading">
        <template #empty>
          <el-empty :description="emptyDescription" :image-size="80" />
        </template>

        <el-table-column label="操作人" width="160">
          <template #default="{ row }">
            <div class="operator-name">{{ row.operatorName || row.operatorUsername || '-' }}</div>
            <div v-if="row.operatorName && row.operatorUsername" class="operator-username mono-code">
              {{ row.operatorUsername }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="动作" width="130" align="center">
          <template #default="{ row }">
            <el-tag :type="actionTagType(row.actionType)" size="small" effect="plain">
              {{ actionLabel(row.actionType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="资源" min-width="190">
          <template #default="{ row }">
            <template v-if="row.resourceType || row.resourceId">
              <span class="resource-type">{{ resourceLabel(row.resourceType) || '-' }}</span>
              <span v-if="row.resourceId" class="mono-code resource-id">#{{ row.resourceId }}</span>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column label="摘要" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="summary-text">{{ row.summary || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="时间" width="175" align="center">
          <template #default="{ row }">
            <span class="tabular-nums time-text">{{ formatTime(row.time) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          :current-page="page"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </div>
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

.error-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 20px;
  background-color: #FEF2F2;
  border-color: #FECACA;
}

.error-text {
  font-size: 12.5px;
  color: #DC2626;
}

.operator-name {
  font-weight: 500;
  color: #111827;
}

.operator-username {
  margin-top: 2px;
  color: #6B7280;
}

.resource-type {
  color: #111827;
}

.resource-id {
  margin-left: 6px;
  color: #2055A4;
}

.summary-text {
  color: #4B5563;
}

.time-text {
  font-size: 12px;
  color: #6B7280;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: 12px 20px;
  border-top: 1px solid #F3F4F6;
}
</style>