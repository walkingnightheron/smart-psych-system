<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDashboardSummaryApi,
  getCacheMetricsApi,
  evictCacheApi
} from '@/api/admin'

const router = useRouter()

const summaryLoading = ref(false)
const cacheLoading = ref(false)
const evicting = ref(false)

const summary = ref({
  questionnaireCount: 0,
  publishedVersionCount: 0,
  activeActivityCount: 0,
  totalActivityCount: 0,
  validSheetCount: 0,
  pendingOrFailedReportCount: 0,
  recentActivities: []
})

const cacheMetrics = ref({
  cacheMode: '加载中',
  activeBackend: '',
  configuredMode: '',
  hitCount: 0,
  missCount: 0,
  evictCount: 0,
  hitRate: null,
  recentLogs: []
})

function formatCacheMode(data) {
  const activeBackend = data?.activeBackend
  const configuredMode = data?.configuredMode
  if (activeBackend) {
    const backendLabel =
      activeBackend === 'MEMORY_FALLBACK'
        ? '内存降级缓存'
        : activeBackend
    return configuredMode ? `${backendLabel} (${configuredMode})` : backendLabel
  }
  return (
    data?.cacheMode ||
    data?.mode ||
    data?.provider ||
    (configuredMode ? `REDIS (${configuredMode})` : 'Redis 热点缓存')
  )
}

function getEventActionText(action) {
  if (action === 'HIT') return '缓存命中 (HIT)'
  if (action === 'MISS_DB') return '未命中回源查库 (MISS_DB)'
  if (action === 'PUT') return '写入回填缓存 (PUT)'
  if (action === 'EVICT') return '缓存主动失效清理 (EVICT)'
  return action
}

function normalizeEvents(data) {
  const rawList =
    data?.recentLogs ??
    data?.recentEvents ??
    data?.events ??
    data?.logs ??
    data?.operations ??
    []
  if (!Array.isArray(rawList)) return []
  return rawList.map((item, idx) => {
    if (typeof item === 'string') {
      const upper = item.toUpperCase()
      let action = 'INFO'
      if (upper.includes('EVICT')) action = 'EVICT'
      else if (upper.includes('MISS')) action = 'MISS_DB'
      else if (upper.includes('PUT')) action = 'PUT'
      else if (upper.includes('HIT')) action = 'HIT'
      return {
        id: idx,
        eventType: action,
        action,
        key: '',
        time: '',
        detail: item
      }
    }
    const action = (
      item.eventType ||
      item.action ||
      item.type ||
      item.event ||
      item.source ||
      'INFO'
    ).toUpperCase()
    return {
      id: item.id ?? idx,
      eventType: action,
      action,
      key: item.key || item.cacheKey || '',
      time: item.time || item.timestamp || item.createdAt || '',
      detail: item.detail || item.message || item.description || getEventActionText(action)
    }
  })
}

const computedHitRate = computed(() => {
  const m = cacheMetrics.value
  if (m.hitRate !== undefined && m.hitRate !== null && m.hitRate !== '') {
    const num = Number(m.hitRate)
    if (!Number.isNaN(num)) {
      return `${num.toFixed(2)}%`
    }
    return String(m.hitRate)
  }
  const hits = Number(m.hitCount || 0)
  const misses = Number(m.missCount || 0)
  const total = hits + misses
  if (total === 0) return '0.00%'
  return `${((hits / total) * 100).toFixed(2)}%`
})

async function loadSummary() {
  summaryLoading.value = true
  try {
    const res = await getDashboardSummaryApi()
    const data = res?.data ?? res ?? {}
    summary.value = {
      questionnaireCount: data.questionnaireCount ?? 0,
      publishedVersionCount: data.publishedVersionCount ?? 0,
      activeActivityCount: data.activeActivityCount ?? 0,
      totalActivityCount: data.totalActivityCount ?? 0,
      validSheetCount: data.validSheetCount ?? 0,
      pendingOrFailedReportCount: data.pendingOrFailedReportCount ?? 0,
      recentActivities: Array.isArray(data.recentActivities) ? data.recentActivities : []
    }
  } finally {
    summaryLoading.value = false
  }
}

async function loadCacheMetrics(showSuccess = false) {
  cacheLoading.value = true
  try {
    const res = await getCacheMetricsApi()
    const data = res?.data ?? res ?? {}
    cacheMetrics.value = {
      cacheMode: formatCacheMode(data),
      activeBackend: data.activeBackend || '',
      configuredMode: data.configuredMode || '',
      hitCount: data.hitCount ?? 0,
      missCount: data.missCount ?? 0,
      evictCount: data.evictCount ?? 0,
      hitRate: data.hitRate ?? null,
      recentLogs: normalizeEvents(data)
    }
    if (showSuccess) {
      ElMessage.success('缓存观测数据已刷新')
    }
  } finally {
    cacheLoading.value = false
  }
}

async function handleEvictAll() {
  try {
    await ElMessageBox.confirm(
      '将清理全部问卷版本快照与活动详情缓存（psych:qver:* / psych:act:*），后续首次访问将触发回源查询并重新建立缓存。是否继续？',
      '清空缓存确认',
      {
        type: 'warning',
        confirmButtonText: '确认清空',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  evicting.value = true
  try {
    await evictCacheApi()
    ElMessage.success('热点缓存已清空')
    await loadCacheMetrics()
  } finally {
    evicting.value = false
  }
}

function getStatusMeta(status) {
  if (status === 'PUBLISHED') return { label: '进行中', type: 'success' }
  if (status === 'CLOSED') return { label: '已关闭', type: 'info' }
  return { label: '草稿', type: 'warning' }
}

function getEventTagType(action) {
  if (action.includes('HIT')) return 'success'
  if (action.includes('MISS')) return 'warning'
  if (action.includes('PUT')) return 'primary'
  if (action.includes('EVICT')) return 'danger'
  return 'info'
}

function formatRate(val) {
  const num = Number(val ?? 0)
  return Number.isNaN(num) ? 0 : Number(num.toFixed(2))
}

onMounted(() => {
  loadSummary()
  loadCacheMetrics()
})
</script>

<template>
  <div class="page-container">
    <!-- 顶部水平四等分 KPI 概览条 -->
    <div v-loading="summaryLoading" class="kpi-strip">
      <div class="kpi-cell" @click="router.push('/questionnaires')">
        <div class="kpi-cell-label">问卷数 / 已发布版本</div>
        <div class="kpi-cell-value tabular-nums">
          {{ summary.questionnaireCount }}
          <span class="kpi-divider">/</span>
          <span class="kpi-sub-val">{{ summary.publishedVersionCount }}</span>
        </div>
        <div class="kpi-cell-foot">草稿问卷与已冻结版本数</div>
      </div>

      <div class="kpi-cell" @click="router.push('/activities')">
        <div class="kpi-cell-label">进行中活动 / 总活动</div>
        <div class="kpi-cell-value tabular-nums">
          {{ summary.activeActivityCount }}
          <span class="kpi-divider">/</span>
          <span class="kpi-sub-val">{{ summary.totalActivityCount }}</span>
        </div>
        <div class="kpi-cell-foot">当前管理员授权可见范围</div>
      </div>

      <div class="kpi-cell" @click="router.push('/statistics')">
        <div class="kpi-cell-label">有效答卷</div>
        <div class="kpi-cell-value tabular-nums">
          {{ summary.validSheetCount }}
        </div>
        <div class="kpi-cell-foot">已提交落库的测评答卷总数</div>
      </div>

      <div class="kpi-cell" @click="router.push('/statistics')">
        <div class="kpi-cell-label">待处理 / 失败报告</div>
        <div
          class="kpi-cell-value tabular-nums"
          :class="{ 'text-warn': summary.pendingOrFailedReportCount > 0 }"
        >
          {{ summary.pendingOrFailedReportCount }}
        </div>
        <div class="kpi-cell-foot">
          {{ summary.pendingOrFailedReportCount > 0 ? '可在统计页触发补偿重试' : '全部评估报告已正常生成' }}
        </div>
      </div>
    </div>

    <!-- 中部：授权活动进度表 -->
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title-wrap">
          <h2 class="panel-title">授权评估活动进度</h2>
          <span class="panel-subtitle">仅展示当前账号负责或获授权查看的活动</span>
        </div>
        <div class="header-actions">
          <el-button size="small" @click="loadSummary">刷新</el-button>
          <el-button size="small" type="primary" plain @click="router.push('/activities')">
            全部活动
          </el-button>
        </div>
      </div>

      <div class="panel-body table-body">
        <el-table
          :data="summary.recentActivities"
          v-loading="summaryLoading"
          empty-text="当前授权范围内暂无评估活动"
        >
          <el-table-column prop="id" label="编号" width="80">
            <template #default="{ row }">
              <span class="mono-code">#{{ row.id }}</span>
            </template>
          </el-table-column>

          <el-table-column prop="title" label="活动名称" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="act-name">{{ row.title }}</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="getStatusMeta(row.status).type" size="small" effect="plain">
                {{ getStatusMeta(row.status).label }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="完成进度" min-width="210">
            <template #default="{ row }">
              <div class="progress-wrap">
                <div class="progress-meta tabular-nums">
                  <span>{{ row.completedCount ?? 0 }} / {{ row.participantCount ?? 0 }} 人</span>
                  <span class="rate-num">{{ formatRate(row.completionRate).toFixed(2) }}%</span>
                </div>
                <el-progress
                  :percentage="Math.min(100, formatRate(row.completionRate))"
                  :show-text="false"
                  :stroke-width="6"
                  color="#2055A4"
                />
              </div>
            </template>
          </el-table-column>

          <el-table-column label="开放时间窗" min-width="190">
            <template #default="{ row }">
              <div class="time-window tabular-nums">
                <div>{{ row.startTime || '-' }}</div>
                <div>{{ row.endTime || '-' }}</div>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="140" fixed="right" align="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                size="small"
                @click="router.push({ path: '/statistics', query: { activityId: row.id } })"
              >
                统计
              </el-button>
              <el-button
                link
                size="small"
                @click="router.push('/activities')"
              >
                活动管理
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 底部：热点缓存运行观测面板 -->
    <div v-loading="cacheLoading" class="panel">
      <div class="panel-header">
        <div class="panel-title-wrap">
          <h2 class="panel-title">热点缓存运行观测</h2>
          <span class="panel-subtitle">问卷版本快照与活动详情缓存指标及事件流水</span>
        </div>
        <div class="header-actions">
          <el-button size="small" @click="loadCacheMetrics(true)">刷新</el-button>
          <el-button
            size="small"
            type="danger"
            plain
            :loading="evicting"
            @click="handleEvictAll"
          >
            清空缓存
          </el-button>
        </div>
      </div>

      <div class="panel-body">
        <!-- 运维监测条 -->
        <div class="cache-metrics-bar">
          <div class="cm-item">
            <span class="cm-label">运行后端</span>
            <span class="cm-val">{{ cacheMetrics.cacheMode }}</span>
          </div>
          <div class="cm-item">
            <span class="cm-label">缓存命中率</span>
            <span class="cm-val primary tabular-nums">{{ computedHitRate }}</span>
          </div>
          <div class="cm-item">
            <span class="cm-label">命中次数 (HIT)</span>
            <span class="cm-val tabular-nums">{{ cacheMetrics.hitCount }}</span>
          </div>
          <div class="cm-item">
            <span class="cm-label">未命中回源 (MISS_DB)</span>
            <span class="cm-val tabular-nums">{{ cacheMetrics.missCount }}</span>
          </div>
          <div class="cm-item">
            <span class="cm-label">主动失效 (EVICT)</span>
            <span class="cm-val tabular-nums">{{ cacheMetrics.evictCount }}</span>
          </div>
        </div>

        <!-- 紧凑日志表 -->
        <el-table
          :data="cacheMetrics.recentLogs"
          size="small"
          border
          max-height="280"
          empty-text="暂无缓存访问流水记录"
        >
          <el-table-column label="事件类型" width="120" align="center">
            <template #default="{ row }">
              <el-tag
                size="small"
                :type="getEventTagType(row.action)"
                effect="plain"
                class="mono-code"
              >
                {{ row.eventType || row.action }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="key" label="缓存键 (Key)" width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono-code">{{ row.key || '-' }}</span>
            </template>
          </el-table-column>

          <el-table-column prop="detail" label="事件说明" min-width="260" show-overflow-tooltip />

          <el-table-column prop="time" label="记录时间" width="175" align="center">
            <template #default="{ row }">
              <span class="tabular-nums time-col">{{ row.time || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kpi-cell {
  cursor: pointer;
}

.kpi-cell:hover {
  background-color: #F9FAFB;
}

.text-warn {
  color: #D97706;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.table-body {
  padding: 0;
}

.act-name {
  font-weight: 500;
  color: #111827;
}

.progress-wrap {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 240px;
}

.progress-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #4B5563;
}

.rate-num {
  font-weight: 600;
  color: #2055A4;
}

.time-window {
  font-size: 12px;
  line-height: 1.45;
  color: #6B7280;
}

.cache-metrics-bar {
  display: grid;
  grid-template-columns: 1.4fr repeat(4, 1fr);
  border: 1px solid #E5E7EB;
  border-radius: 6px;
  background-color: #F9FAFB;
  margin-bottom: 14px;
}

.cm-item {
  padding: 10px 14px;
  border-right: 1px solid #E5E7EB;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cm-item:last-child {
  border-right: none;
}

.cm-label {
  font-size: 12px;
  color: #6B7280;
}

.cm-val {
  font-size: 15px;
  font-weight: 600;
  color: #111827;
}

.cm-val.primary {
  color: #2055A4;
}

.time-col {
  font-size: 12px;
  color: #6B7280;
}

@media (max-width: 960px) {
  .cache-metrics-bar {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
