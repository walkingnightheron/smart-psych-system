<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import {
  getActivitiesApi,
  getActivityStatisticsApi,
  getAdminReportDetailApi,
  retryAdminReportApi
} from '@/api/admin'

const route = useRoute()
const router = useRouter()

const activitiesLoading = ref(false)
const statsLoading = ref(false)
const activities = ref([])
const selectedActivityId = ref(null)
const statsData = ref(null)

// Chart refs & modes
const levelChartRef = ref(null)
const dimChartRef = ref(null)
const levelChartMode = ref('bar')
const dimChartMode = ref('bar')

let levelChartInstance = null
let dimChartInstance = null
let resizeObserver = null

// Report detail modal
const reportDialogVisible = ref(false)
const reportLoading = ref(false)
const currentReport = ref(null)
const retryingId = ref(null)

const parsedReportDimensions = computed(() => {
  const r = currentReport.value
  if (!r) return []
  if (Array.isArray(r.dimensions)) {
    return r.dimensions.map((d) => ({
      code: d.dimensionCode || d.code,
      name: d.dimensionName || d.name || d.dimensionCode || d.code,
      score: Number(d.score ?? d.avgScore ?? 0).toFixed(2)
    }))
  }
  let rawMap = r.dimensionScores ?? r.dimensionScoresJson
  if (typeof rawMap === 'string') {
    try {
      rawMap = JSON.parse(rawMap)
    } catch {
      rawMap = null
    }
  }
  if (rawMap && typeof rawMap === 'object') {
    const dimNameLookup = {}
    ;(statsData.value?.dimensionAverages || []).forEach((d) => {
      dimNameLookup[d.dimensionCode] = d.dimensionName
    })
    return Object.entries(rawMap).map(([code, score]) => ({
      code,
      name: dimNameLookup[code] || code,
      score: Number(score ?? 0).toFixed(2)
    }))
  }
  return []
})

async function loadActivityOptions() {
  activitiesLoading.value = true
  try {
    const res = await getActivitiesApi()
    const list = res?.data ?? res ?? []
    activities.value = Array.isArray(list) ? list : []

    const queryId = route.query.activityId ? Number(route.query.activityId) : null
    if (queryId && !Number.isNaN(queryId)) {
      selectedActivityId.value = queryId
    } else if (!selectedActivityId.value && activities.value.length > 0) {
      selectedActivityId.value = activities.value[0].id
    }

    if (selectedActivityId.value) {
      await loadStatistics(selectedActivityId.value)
    }
  } finally {
    activitiesLoading.value = false
  }
}

async function loadStatistics(activityId) {
  if (!activityId) return
  statsLoading.value = true
  try {
    const res = await getActivityStatisticsApi(activityId)
    statsData.value = res?.data ?? res ?? null
    await nextTick()
    renderCharts()
  } catch {
    statsData.value = null
    disposeCharts()
  } finally {
    statsLoading.value = false
  }
}

function handleActivityChange(val) {
  router.replace({ path: '/statistics', query: val ? { activityId: val } : undefined })
  loadStatistics(val)
}

function renderLevelChart() {
  if (!levelChartRef.value || !statsData.value) return
  if (!levelChartInstance) {
    levelChartInstance = echarts.init(levelChartRef.value)
  }
  const dist = statsData.value.levelDistribution || []
  const palette = ['#2055A4', '#4B7BC4', '#D97706', '#DC2626']

  if (levelChartMode.value === 'pie') {
    levelChartInstance.setOption(
      {
        tooltip: { trigger: 'item', formatter: '{b}：{c} 人 ({d}%)' },
        legend: {
          bottom: 0,
          icon: 'rect',
          itemWidth: 10,
          itemHeight: 10,
          textStyle: { color: '#4B5563', fontSize: 12 }
        },
        color: palette,
        series: [
          {
            name: '结果区间人数',
            type: 'pie',
            radius: ['42%', '66%'],
            center: ['50%', '44%'],
            itemStyle: { borderColor: '#FFFFFF', borderWidth: 2 },
            label: { formatter: '{b}：{c}人', color: '#4B5563', fontSize: 12 },
            data: dist.map((item) => ({
              name: item.levelName || item.levelCode,
              value: Number(item.count || 0)
            }))
          }
        ]
      },
      true
    )
  } else {
    levelChartInstance.setOption(
      {
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        grid: { left: 42, right: 20, top: 28, bottom: 32 },
        xAxis: {
          type: 'category',
          data: dist.map((d) => d.levelName || d.levelCode),
          axisLine: { lineStyle: { color: '#E5E7EB' } },
          axisTick: { show: false },
          axisLabel: { color: '#4B5563', fontSize: 12 }
        },
        yAxis: {
          type: 'value',
          minInterval: 1,
          name: '人数',
          nameTextStyle: { color: '#6B7280', fontSize: 12 },
          axisLabel: { color: '#6B7280', fontSize: 12 },
          splitLine: { lineStyle: { type: 'solid', color: '#F3F4F6' } }
        },
        series: [
          {
            name: '人数',
            type: 'bar',
            barMaxWidth: 38,
            data: dist.map((d, idx) => ({
              value: Number(d.count || 0),
              itemStyle: {
                color: palette[idx % palette.length],
                borderRadius: [3, 3, 0, 0]
              }
            })),
            label: { show: true, position: 'top', formatter: '{c} 人', color: '#4B5563', fontSize: 12 }
          }
        ]
      },
      true
    )
  }
}

function renderDimensionChart() {
  if (!dimChartRef.value || !statsData.value) return
  if (!dimChartInstance) {
    dimChartInstance = echarts.init(dimChartRef.value)
  }
  const dims = statsData.value.dimensionAverages || []

  if (dimChartMode.value === 'radar' && dims.length >= 3) {
    const maxScore = Math.max(8, ...dims.map((d) => Math.ceil(Number(d.avgScore || 0) * 1.25)))
    dimChartInstance.setOption(
      {
        tooltip: {},
        radar: {
          radius: '62%',
          axisName: { color: '#4B5563', fontSize: 12 },
          splitLine: { lineStyle: { color: '#E5E7EB' } },
          splitArea: { areaStyle: { color: ['#FFFFFF', '#F9FAFB'] } },
          axisLine: { lineStyle: { color: '#E5E7EB' } },
          indicator: dims.map((d) => ({
            name: `${d.dimensionName}\n(${d.dimensionCode})`,
            max: maxScore
          }))
        },
        series: [
          {
            type: 'radar',
            data: [
              {
                value: dims.map((d) => Number(Number(d.avgScore || 0).toFixed(2))),
                name: '维度平均得分',
                areaStyle: { color: 'rgba(32, 85, 164, 0.14)' },
                lineStyle: { color: '#2055A4', width: 2 },
                itemStyle: { color: '#2055A4' }
              }
            ]
          }
        ]
      },
      true
    )
  } else {
    dimChartInstance.setOption(
      {
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        grid: { left: 46, right: 20, top: 28, bottom: 32 },
        xAxis: {
          type: 'category',
          data: dims.map((d) => `${d.dimensionName} (${d.dimensionCode})`),
          axisLine: { lineStyle: { color: '#E5E7EB' } },
          axisTick: { show: false },
          axisLabel: { color: '#4B5563', fontSize: 12 }
        },
        yAxis: {
          type: 'value',
          name: '平均分',
          nameTextStyle: { color: '#6B7280', fontSize: 12 },
          axisLabel: { color: '#6B7280', fontSize: 12 },
          splitLine: { lineStyle: { type: 'solid', color: '#F3F4F6' } }
        },
        series: [
          {
            name: '平均得分',
            type: 'bar',
            barMaxWidth: 38,
            data: dims.map((d) => Number(Number(d.avgScore || 0).toFixed(2))),
            itemStyle: {
              color: '#2055A4',
              borderRadius: [3, 3, 0, 0]
            },
            label: { show: true, position: 'top', formatter: '{c} 分', color: '#2055A4', fontSize: 12 }
          }
        ]
      },
      true
    )
  }
}

function renderCharts() {
  if (resizeObserver) {
    if (levelChartRef.value) resizeObserver.observe(levelChartRef.value)
    if (dimChartRef.value) resizeObserver.observe(dimChartRef.value)
  }
  renderLevelChart()
  renderDimensionChart()
}

function handleResize() {
  levelChartInstance?.resize()
  dimChartInstance?.resize()
}

function disposeCharts() {
  if (levelChartInstance) {
    levelChartInstance.dispose()
    levelChartInstance = null
  }
  if (dimChartInstance) {
    dimChartInstance.dispose()
    dimChartInstance = null
  }
}

async function openReportModal(row) {
  if (!row.reportId) return
  reportDialogVisible.value = true
  reportLoading.value = true
  currentReport.value = null
  try {
    const res = await getAdminReportDetailApi(row.reportId)
    currentReport.value = {
      studentName: row.realName,
      studentUsername: row.username,
      deptClass: row.deptClass,
      ...(res?.data ?? res ?? {})
    }
  } finally {
    reportLoading.value = false
  }
}

async function handleRetryReport(row) {
  if (!row.reportId) return
  retryingId.value = row.reportId
  try {
    await retryAdminReportApi(row.reportId, false, { silentError: true })
    ElMessage.success('已触发评估报告重算任务')
    await loadStatistics(selectedActivityId.value)
  } catch (err) {
    const msg = err?.response?.data?.message || '触发重试失败'
    try {
      await ElMessageBox.confirm(
        `${msg}。是否携带 reset=true 重置重试次数上限并重新生成报告？`,
        '重置重试次数确认',
        {
          type: 'warning',
          confirmButtonText: '重置并重试',
          cancelButtonText: '取消'
        }
      )
      await retryAdminReportApi(row.reportId, true)
      ElMessage.success('已重置计数并重新生成报告')
      await loadStatistics(selectedActivityId.value)
    } catch {
      // cancelled or error shown by interceptor
    }
  } finally {
    retryingId.value = null
  }
}

function getTaskStatusTag(status) {
  if (status === 'SUCCESS') return { label: '已生成', type: 'success' }
  if (status === 'PENDING') return { label: '待生成', type: 'warning' }
  if (status === 'FAILED') return { label: '生成失败', type: 'danger' }
  return { label: '未提交', type: 'info' }
}

watch(levelChartMode, () => renderLevelChart())
watch(dimChartMode, () => renderDimensionChart())
watch(
  () => route.query.activityId,
  (newId) => {
    if (newId && Number(newId) !== Number(selectedActivityId.value)) {
      selectedActivityId.value = Number(newId)
      loadStatistics(selectedActivityId.value)
    }
  }
)

onMounted(async () => {
  window.addEventListener('resize', handleResize)
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => handleResize())
  }
  await loadActivityOptions()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  resizeObserver?.disconnect()
  disposeCharts()
})
</script>

<template>
  <div class="page-container">
    <!-- 顶部紧凑活动选择器 -->
    <div class="page-toolbar">
      <div class="selector-group">
        <span class="selector-label">评估活动</span>
        <el-select
          v-model="selectedActivityId"
          placeholder="请选择负责或获授权的评估活动"
          :loading="activitiesLoading"
          style="width: 360px"
          @change="handleActivityChange"
        >
          <el-option
            v-for="act in activities"
            :key="act.id"
            :value="act.id"
            :label="`#${act.id} ${act.title}（${act.status}）`"
          />
        </el-select>
        <el-tag v-if="statsData" size="small" type="primary" effect="plain" class="mono-code">
          版本 v{{ statsData.versionNum }} · {{ statsData.ruleVersion }}
        </el-tag>
      </div>

      <div class="toolbar-right">
        <el-button
          :disabled="!selectedActivityId"
          :loading="statsLoading"
          @click="loadStatistics(selectedActivityId)"
        >
          刷新
        </el-button>
      </div>
    </div>

    <div v-if="!selectedActivityId && !activitiesLoading" class="panel">
      <div class="panel-body">
        <el-empty description="当前账号暂无授权可查看的评估活动统计数据" />
      </div>
    </div>

    <template v-else-if="statsData">
      <!-- 一行 4 列数据概览条 -->
      <div v-loading="statsLoading" class="kpi-strip">
        <div class="kpi-cell">
          <div class="kpi-cell-label">目标人数</div>
          <div class="kpi-cell-value tabular-nums">
            {{ statsData.participantCount ?? 0 }}
            <span class="kpi-unit">人</span>
          </div>
          <div class="kpi-cell-foot">活动名单总人数</div>
        </div>

        <div class="kpi-cell">
          <div class="kpi-cell-label">已完成</div>
          <div class="kpi-cell-value tabular-nums">
            {{ statsData.completedCount ?? 0 }}
            <span class="kpi-unit">人</span>
          </div>
          <div class="kpi-cell-foot">已提交有效答卷人数</div>
        </div>

        <div class="kpi-cell">
          <div class="kpi-cell-label">完成率</div>
          <div class="kpi-cell-value primary-val tabular-nums">
            {{ Number(statsData.completionRate ?? 0).toFixed(2) }}%
          </div>
          <div class="kpi-cell-foot">
            <el-progress
              :percentage="Math.min(100, Number(statsData.completionRate ?? 0))"
              :show-text="false"
              :stroke-width="6"
              color="#2055A4"
            />
          </div>
        </div>

        <div class="kpi-cell">
          <div class="kpi-cell-label">报告状态</div>
          <div class="report-status-line tabular-nums">
            <span>成功 <strong class="ok-num">{{ statsData.successReportCount ?? 0 }}</strong></span>
            <span class="sep">/</span>
            <span>待生成 <strong class="warn-num">{{ statsData.pendingReportCount ?? 0 }}</strong></span>
            <span class="sep">/</span>
            <span>失败 <strong class="err-num">{{ statsData.failedReportCount ?? 0 }}</strong></span>
          </div>
          <div class="kpi-cell-foot">待生成或失败记录不计入区间分布统计</div>
        </div>
      </div>

      <!-- 双列图表区 -->
      <div class="charts-grid">
        <div class="panel">
          <div class="panel-header">
            <div class="panel-title-wrap">
              <h3 class="panel-title">结果区间人数分布</h3>
              <span class="panel-subtitle">仅统计已生成报告的测评记录</span>
            </div>
            <el-radio-group v-model="levelChartMode" size="small">
              <el-radio-button value="bar">柱状图</el-radio-button>
              <el-radio-button value="pie">饼图</el-radio-button>
            </el-radio-group>
          </div>
          <div class="panel-body">
            <div ref="levelChartRef" class="chart-canvas"></div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-header">
            <div class="panel-title-wrap">
              <h3 class="panel-title">各心理维度平均得分</h3>
              <span class="panel-subtitle">按绑定版本计分规则计算均值</span>
            </div>
            <el-radio-group v-model="dimChartMode" size="small">
              <el-radio-button value="bar">柱状图</el-radio-button>
              <el-radio-button
                value="radar"
                :disabled="(statsData.dimensionAverages || []).length < 3"
              >
                雷达图
              </el-radio-button>
            </el-radio-group>
          </div>
          <div class="panel-body">
            <div ref="dimChartRef" class="chart-canvas"></div>
          </div>
        </div>
      </div>

      <!-- 底部授权参与名单与报告明细表 -->
      <div class="panel">
        <div class="panel-header">
          <div class="panel-title-wrap">
            <h3 class="panel-title">参与名单及评估报告明细</h3>
            <span class="panel-subtitle">可查阅单份评估报告，或对待生成/失败记录触发补偿重试</span>
          </div>
        </div>

        <el-table :data="statsData.participantDetails || []" border>
          <el-table-column prop="username" label="学号" width="120">
            <template #default="{ row }">
              <span class="mono-code">{{ row.username }}</span>
            </template>
          </el-table-column>

          <el-table-column prop="realName" label="姓名" min-width="140" />

          <el-table-column prop="deptClass" label="所属班级" width="135" />

          <el-table-column label="交卷状态" width="105" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.submitted" size="small" type="success" effect="plain">已提交</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">未提交</el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="submittedAt" label="提交时间" width="170" align="center">
            <template #default="{ row }">
              <span class="tabular-nums time-col">{{ row.submittedAt || '-' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="报告状态" min-width="180" align="center">
            <template #default="{ row }">
              <template v-if="row.submitted && row.taskStatus">
                <el-tag :type="getTaskStatusTag(row.taskStatus).type" size="small" effect="plain">
                  {{ getTaskStatusTag(row.taskStatus).label }}
                </el-tag>
                <div
                  v-if="row.retryCount > 0 || row.lastError"
                  class="retry-meta tabular-nums"
                >
                  <span>已重试 {{ row.retryCount ?? 0 }} 次</span>
                  <el-tooltip v-if="row.lastError" :content="row.lastError" placement="top">
                    <span class="err-reason"> · 失败原因</span>
                  </el-tooltip>
                </div>
              </template>
              <span v-else class="muted-dash">-</span>
            </template>
          </el-table-column>

          <el-table-column label="总分" width="95" align="center">
            <template #default="{ row }">
              <strong
                v-if="row.taskStatus === 'SUCCESS' && row.totalScore !== null"
                class="tabular-nums"
              >
                {{ Number(row.totalScore).toFixed(2) }}
              </strong>
              <span v-else class="muted-dash">-</span>
            </template>
          </el-table-column>

          <el-table-column label="结果区间" width="130" align="center">
            <template #default="{ row }">
              <el-tag
                v-if="row.taskStatus === 'SUCCESS' && row.levelName"
                size="small"
                effect="plain"
                :type="
                  row.levelCode === 'GOOD'
                    ? 'success'
                    : row.levelCode === 'MILD'
                      ? 'primary'
                      : row.levelCode === 'MODERATE'
                        ? 'warning'
                        : 'danger'
                "
              >
                {{ row.levelName }}
              </el-tag>
              <span v-else class="muted-dash">-</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="160" fixed="right" align="right">
            <template #default="{ row }">
              <el-button
                v-if="row.reportId"
                link
                type="primary"
                size="small"
                @click="openReportModal(row)"
              >
                查看报告
              </el-button>
              <el-button
                v-if="row.reportId && (row.taskStatus === 'PENDING' || row.taskStatus === 'FAILED')"
                link
                type="warning"
                size="small"
                :loading="retryingId === row.reportId"
                @click="handleRetryReport(row)"
              >
                重试生成
              </el-button>
              <span v-if="!row.reportId" class="muted-dash">-</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- 单份评估报告详情弹窗 -->
    <el-dialog
      v-model="reportDialogVisible"
      title="学生心理评估报告"
      width="600px"
      destroy-on-close
    >
      <div v-loading="reportLoading">
        <template v-if="currentReport">
          <div class="report-summary-box">
            <div>
              <div class="r-student">
                {{ currentReport.realName || currentReport.studentName }}
                <span class="mono-code r-sno">
                  ({{ currentReport.username || currentReport.studentUsername }})
                </span>
              </div>
              <div class="r-meta tabular-nums">
                {{ currentReport.deptClass || '-' }} · 规则版本 {{ currentReport.ruleVersion || '-' }} ·
                生成时间 {{ currentReport.generatedAt || '-' }}
              </div>
            </div>
            <div v-if="currentReport.taskStatus === 'SUCCESS'" class="r-score-box tabular-nums">
              <div class="r-score-num">{{ Number(currentReport.totalScore ?? 0).toFixed(2) }}</div>
              <div class="r-level">{{ currentReport.levelName }}</div>
            </div>
            <el-tag v-else :type="getTaskStatusTag(currentReport.taskStatus).type" effect="plain">
              {{ getTaskStatusTag(currentReport.taskStatus).label }}
            </el-tag>
          </div>

          <template v-if="currentReport.taskStatus === 'SUCCESS'">
            <div class="r-sec-title">各评估维度得分</div>
            <div class="dim-score-grid">
              <div
                v-for="dim in parsedReportDimensions"
                :key="dim.code"
                class="dim-score-card"
              >
                <div class="ds-name">{{ dim.name }}</div>
                <div class="ds-code mono-code">{{ dim.code }}</div>
                <div class="ds-val tabular-nums">{{ dim.score }} <small>分</small></div>
              </div>
            </div>

            <div class="r-sec-title">结果区间反馈与调适建议</div>
            <div class="advice-box">
              {{ currentReport.feedbackText || '暂无反馈建议文本' }}
            </div>
          </template>

          <el-alert
            v-else
            :title="`当前报告状态为 ${currentReport.taskStatus}`"
            :description="currentReport.lastError || '报告尚未生成完成，可在列表点击「重试生成」进行补偿。'"
            type="warning"
            :closable="false"
            show-icon
            style="margin-top: 14px"
          />

          <div class="medical-note">
            说明：本评估结果由问卷预设规则自动计算生成，仅供高校心理健康教育中心教务参考，不作为临床医学诊断依据。
          </div>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.selector-group {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.selector-label {
  font-size: 13.5px;
  font-weight: 600;
  color: #111827;
}

.kpi-unit {
  font-size: 13px;
  font-weight: 400;
  color: #6B7280;
  margin-left: 2px;
}

.primary-val {
  color: #2055A4;
}

.report-status-line {
  font-size: 15px;
  font-weight: 500;
  color: #111827;
  line-height: 1.5;
}

.report-status-line .sep {
  margin: 0 6px;
  color: #D1D5DB;
}

.ok-num {
  color: #16A34A;
}

.warn-num {
  color: #D97706;
}

.err-num {
  color: #DC2626;
}

.charts-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

@media (max-width: 960px) {
  .charts-grid {
    grid-template-columns: 1fr;
  }
}

.chart-canvas {
  width: 100%;
  height: 300px;
}

.time-col {
  font-size: 12px;
  color: #6B7280;
}

.retry-meta {
  margin-top: 4px;
  font-size: 11px;
  color: #6B7280;
}

.err-reason {
  color: #DC2626;
  cursor: pointer;
}

.muted-dash {
  color: #6B7280;
  font-size: 12px;
}

/* Report Detail Dialog */
.report-summary-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
}

.r-student {
  font-size: 16px;
  font-weight: 600;
  color: #111827;
}

.r-sno {
  font-weight: 400;
  color: #6B7280;
}

.r-meta {
  margin-top: 4px;
  font-size: 12px;
  color: #6B7280;
}

.r-score-box {
  text-align: right;
}

.r-score-num {
  font-size: 26px;
  font-weight: 600;
  color: #2055A4;
  line-height: 1.1;
}

.r-level {
  margin-top: 4px;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
  background-color: #EEF3FA;
  border: 1px solid #D0DDF0;
  color: #2055A4;
  display: inline-block;
}

.r-sec-title {
  margin: 16px 0 8px;
  font-size: 13.5px;
  font-weight: 600;
  color: #111827;
}

.dim-score-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.dim-score-card {
  padding: 12px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
  text-align: center;
}

.ds-name {
  font-size: 13px;
  font-weight: 500;
  color: #111827;
}

.ds-code {
  color: #6B7280;
  margin-top: 2px;
}

.ds-val {
  margin-top: 6px;
  font-size: 18px;
  font-weight: 600;
  color: #2055A4;
}

.ds-val small {
  font-size: 12px;
  font-weight: 400;
  color: #6B7280;
}

.advice-box {
  padding: 12px 14px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
  color: #111827;
  font-size: 13.5px;
  line-height: 1.65;
}

.medical-note {
  margin-top: 14px;
  font-size: 12px;
  color: #6B7280;
}
</style>
