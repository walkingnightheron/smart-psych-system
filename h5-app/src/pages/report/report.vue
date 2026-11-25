<script setup>
import { computed, ref, watch } from 'vue'
import { onHide, onLoad, onUnload } from '@dcloudio/uni-app'
import { getMyRecordsApi, getMyReportApi, retryMyReportApi, getReportAiInterpretationApi } from '../../api/h5'

const POLL_INTERVAL_MS = 1500
const MAX_POLL_ATTEMPTS = 15

const reportId = ref('')
const sheetIdHint = ref('')
const loading = ref(true)
const refreshing = ref(false)
const retrying = ref(false)
const loadError = ref(null)

const report = ref(null)
const candidateRecords = ref([])

const aiInterpretation = ref(null)
const aiLoading = ref(false)
const aiError = ref(null)

const pollCount = ref(0)
const pollingStoppedByLimit = ref(false)
let pollTimer = null

const DIMENSION_NAME_FALLBACK = {
  STRESS: '学习压力',
  MOOD: '情绪状态',
  SLEEP: '睡眠与作息'
}

onLoad((query) => {
  const token = uni.getStorageSync('token')
  if (!token) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    setTimeout(() => {
      uni.reLaunch({ url: '/pages/login/login' })
    }, 120)
    return
  }

  const incomingReportId = query?.reportId || query?.id || ''
  sheetIdHint.value = query?.sheetId ? String(query.sheetId) : ''

  if (incomingReportId) {
    reportId.value = String(incomingReportId).trim()
    fetchReport(true)
  } else {
    loadLatestFromRecords()
  }
})

onHide(() => {
  stopPolling()
})

onUnload(() => {
  stopPolling()
})

watch(
  () => report.value?.taskStatus,
  (taskStatus) => {
    if (taskStatus === 'SUCCESS') {
      fetchAiInterpretation()
    }
  }
)

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function scheduleNextPoll() {
  stopPolling()
  if (pollCount.value >= MAX_POLL_ATTEMPTS) {
    pollingStoppedByLimit.value = true
    return
  }
  pollTimer = setTimeout(async () => {
    pollCount.value += 1
    await fetchReport(false)
  }, POLL_INTERVAL_MS)
}

async function loadLatestFromRecords() {
  loading.value = true
  loadError.value = null
  try {
    const res = await getMyRecordsApi()
    const list = Array.isArray(res?.data) ? res.data : []
    candidateRecords.value = list.filter((item) => item?.reportId)

    if (candidateRecords.value.length > 0) {
      const latest = candidateRecords.value[0]
      reportId.value = String(latest.reportId)
      sheetIdHint.value = String(latest.sheetId || '')
      await fetchReport(true)
    } else {
      loading.value = false
    }
  } catch (err) {
    loading.value = false
    loadError.value = {
      statusCode: err?.statusCode || 400,
      message: err?.message || '无法加载历史评估报告列表'
    }
  }
}

async function selectCandidateReport(item) {
  if (!item?.reportId) return
  stopPolling()
  pollCount.value = 0
  pollingStoppedByLimit.value = false
  reportId.value = String(item.reportId)
  sheetIdHint.value = String(item.sheetId || '')
  await fetchReport(true)
}

async function fetchReport(showSkeleton = false) {
  if (!reportId.value) return
  if (showSkeleton) {
    loading.value = true
  } else {
    refreshing.value = true
  }
  loadError.value = null

  try {
    const res = await getMyReportApi(reportId.value, {
      skipErrorToast: !showSkeleton
    })
    const data = res?.data || null
    report.value = data

    if (data?.taskStatus === 'PENDING') {
      scheduleNextPoll()
    } else {
      stopPolling()
    }
  } catch (err) {
    stopPolling()
    loadError.value = {
      statusCode: err?.statusCode || 400,
      message: err?.message || '读取评估报告失败'
    }
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

async function handleManualRefresh() {
  stopPolling()
  pollingStoppedByLimit.value = false
  if (pollCount.value >= MAX_POLL_ATTEMPTS) {
    pollCount.value = 0
  }
  await fetchReport(false)
  if (report.value?.taskStatus === 'SUCCESS') {
    uni.showToast({ title: '评估报告已生成完毕', icon: 'success' })
  } else {
    uni.showToast({ title: '已刷新报告状态', icon: 'none' })
  }
}

async function handleRetryReport() {
  if (!reportId.value || retrying.value) return
  retrying.value = true
  try {
    await retryMyReportApi(reportId.value)
    uni.showToast({ title: '已重新申请生成，正在同步结果', icon: 'none' })
    pollCount.value = 0
    pollingStoppedByLimit.value = false
    await fetchReport(false)
  } catch (err) {
    // Error toast displayed by request layer
  } finally {
    retrying.value = false
  }
}

async function fetchAiInterpretation(force = false) {
  if (!reportId.value) return
  if (!force && aiInterpretation.value) return

  aiLoading.value = true
  aiError.value = null
  try {
    const res = await getReportAiInterpretationApi(reportId.value, {
      skipErrorToast: true
    })
    aiInterpretation.value = res?.data || null
  } catch (err) {
    aiInterpretation.value = {
      available: false,
      content: null,
      model: null,
      generatedAt: null,
      disclaimer: null,
      reason: err?.message || 'AI 解读加载失败'
    }
  } finally {
    aiLoading.value = false
  }
}

const levelTheme = computed(() => {
  const code = String(report.value?.levelCode || '').toUpperCase()
  const name = String(report.value?.levelName || '')

  if (code === 'GOOD' || name.includes('良好')) {
    return {
      cls: 'theme-good',
      badge: '状态良好',
      accent: '#1E7E4E',
      topBar: '#1E7E4E'
    }
  }
  if (code === 'MILD' || name.includes('轻度')) {
    return {
      cls: 'theme-mild',
      badge: '轻度提示',
      accent: '#2055A4',
      topBar: '#2055A4'
    }
  }
  if (code === 'MODERATE' || name.includes('中度')) {
    return {
      cls: 'theme-moderate',
      badge: '建议调适',
      accent: '#B45309',
      topBar: '#B45309'
    }
  }
  if (code === 'HIGH' || name.includes('重点') || name.includes('较高')) {
    return {
      cls: 'theme-high',
      badge: '建议关注',
      accent: '#C0392B',
      topBar: '#C0392B'
    }
  }
  return {
    cls: 'theme-mild',
    badge: '评估结论',
    accent: '#2055A4',
    topBar: '#2055A4'
  }
})

const normalizedDimensions = computed(() => {
  const r = report.value || {}
  let list = []

  if (Array.isArray(r.dimensions)) {
    list = r.dimensions.map((d) => {
      const code = d.dimensionCode || d.code || ''
      return {
        dimensionCode: code,
        dimensionName: d.dimensionName || d.name || DIMENSION_NAME_FALLBACK[code] || code || '评估维度',
        score: Number(d.score ?? d.avgScore ?? 0),
        maxScore: d.maxScore ? Number(d.maxScore) : null
      }
    })
  } else if (r.dimensionScores && typeof r.dimensionScores === 'object') {
    list = Object.entries(r.dimensionScores).map(([code, val]) => ({
      dimensionCode: code,
      dimensionName: DIMENSION_NAME_FALLBACK[code] || code,
      score: Number(val ?? 0),
      maxScore: null
    }))
  }

  const maxObserved = list.reduce((m, item) => Math.max(m, item.maxScore || item.score || 0), 8)
  const scaleMax = Math.max(8, maxObserved)

  return list.map((item) => {
    const denom = item.maxScore && item.maxScore > 0 ? item.maxScore : scaleMax
    const pct = Math.min(100, Math.max(10, Math.round((item.score / denom) * 100)))
    return {
      ...item,
      displayScore: Number.isInteger(item.score)
        ? String(item.score)
        : item.score.toFixed(2).replace(/\.00$/, ''),
      barPercent: pct
    }
  })
})

const formattedTotalScore = computed(() => {
  const val = report.value?.totalScore
  if (val === null || val === undefined || val === '') return '--'
  const num = Number(val)
  if (Number.isNaN(num)) return String(val)
  return Number.isInteger(num) ? String(num) : num.toFixed(2).replace(/\.00$/, '')
})

const retryStats = computed(() => {
  const retryCount = Number(report.value?.retryCount ?? 0)
  const maxRetries = Number(report.value?.maxRetries ?? 3)
  return {
    retryCount,
    maxRetries,
    canRetry: retryCount < maxRetries
  }
})

function goRecords() {
  const sid = report.value?.sheetId || sheetIdHint.value || ''
  if (sid) {
    uni.navigateTo({ url: `/pages/record/list?sheetId=${sid}` })
  } else {
    uni.navigateTo({ url: '/pages/record/list' })
  }
}

function goActivities() {
  uni.reLaunch({ url: '/pages/activity/list' })
}
</script>

<template>
  <view class="report-page">
    <!-- Record Selector -->
    <scroll-view
      v-if="candidateRecords.length > 1"
      scroll-x
      class="selector-bar"
    >
      <view class="selector-inner">
        <view
          v-for="rec in candidateRecords"
          :key="rec.reportId"
          class="selector-chip"
          :class="{ 'is-active': String(rec.reportId) === String(reportId) }"
          @tap="selectCandidateReport(rec)"
        >
          {{ rec.activityTitle || `报告 #${rec.reportId}` }}
        </view>
      </view>
    </scroll-view>

    <!-- Initial Loading -->
    <view v-if="loading" class="state-card">
      <view class="spinner" />
      <view class="state-title">正在读取评估报告...</view>
    </view>

    <!-- Error State -->
    <view v-else-if="loadError" class="state-card">
      <view class="error-code">HTTP {{ loadError.statusCode }}</view>
      <view class="state-title">
        {{ loadError.statusCode === 403 ? '无权访问他人或未授权报告' : '无法读取该评估报告' }}
      </view>
      <view class="state-desc">{{ loadError.message }}</view>
      <view class="btn-group">
        <button class="btn-plain" @tap="goRecords">查看提交记录</button>
        <button class="btn-main" @tap="goActivities">返回活动列表</button>
      </view>
    </view>

    <!-- No Report Available -->
    <view v-else-if="!reportId || !report" class="state-card">
      <view class="state-title">当前暂无可查看的评估报告</view>
      <view class="state-desc">
        请先在「我的评估活动」中完成并提交问卷，系统将自动为您生成个人报告。
      </view>
      <button class="btn-main single-btn" @tap="goActivities">前往我的评估活动</button>
    </view>

    <!-- PENDING State -->
    <view v-else-if="report.taskStatus === 'PENDING'" class="pending-container">
      <view class="pending-card">
        <view class="spinner-large" />
        <view class="pending-title">评估报告生成中</view>
        <view class="pending-desc">
          答卷已保存，系统正在异步计算结果。
        </view>

        <view class="poll-status">
          <text v-if="!pollingStoppedByLimit">
            自动同步中（{{ pollCount }} / {{ MAX_POLL_ATTEMPTS }}）
          </text>
          <text v-else class="is-paused">
            已停止自动同步，请手动刷新
          </text>
        </view>

        <button
          class="btn-main"
          :loading="refreshing"
          :disabled="refreshing"
          @tap="handleManualRefresh"
        >
          {{ refreshing ? '同步中...' : '刷新状态' }}
        </button>

        <view class="pending-meta">
          答卷 #{{ report.sheetId || sheetIdHint || '--' }} · 报告 #{{ reportId }}
        </view>
      </view>

      <view class="btn-group">
        <button class="btn-plain" @tap="goRecords">查看答卷记录</button>
        <button class="btn-plain" @tap="goActivities">返回活动列表</button>
      </view>
    </view>

    <!-- SUCCESS State -->
    <view v-else-if="report.taskStatus === 'SUCCESS'" class="success-container">
      <view class="report-card">
        <view class="report-topbar" :style="{ background: levelTheme.topBar }" />

        <view class="report-header">
          <view class="report-title">心理健康评估报告单</view>
          <view class="report-subtitle">
            {{ report.activityTitle || report.questionnaireTitle || '高校心理健康教育中心' }}
          </view>
        </view>

        <view class="score-section">
          <view class="score-block">
            <text class="score-num">{{ formattedTotalScore }}</text>
            <text class="score-unit">分</text>
          </view>
          <view class="level-block">
            <text class="level-badge" :style="{ background: levelTheme.accent }">{{ levelTheme.badge }}</text>
            <text class="level-name">{{ report.levelName || '已完成评估' }}</text>
          </view>
        </view>

        <view class="section-divider" />

        <view class="dimensions-section">
          <view class="section-title">维度得分</view>
          <view v-if="normalizedDimensions.length > 0" class="dim-list">
            <view
              v-for="dim in normalizedDimensions"
              :key="dim.dimensionCode || dim.dimensionName"
              class="dim-item"
            >
              <view class="dim-row">
                <text class="dim-name">{{ dim.dimensionName }}</text>
                <text class="dim-score">{{ dim.displayScore }} 分</text>
              </view>
              <view class="dim-bar-track">
                <view
                  class="dim-bar-fill"
                  :style="{ width: `${dim.barPercent}%`, background: levelTheme.accent }"
                />
              </view>
            </view>
          </view>
          <view v-else class="dim-empty">
            当前版本计分规则未划分独立子维度
          </view>
        </view>

        <view class="section-divider" />

        <view class="guidance-section">
          <view class="section-title">参考建议</view>
          <view class="guidance-text">
            {{ report.feedbackText || '建议保持规律作息、适度运动与积极倾诉。如有进一步困扰，欢迎联系学校心理健康教育中心。' }}
          </view>
        </view>

        <view class="trace-section">
          答卷 #{{ report.sheetId || sheetIdHint || '--' }} · 问卷 v{{ report.versionNum || 1 }} · 规则 {{ report.ruleVersion || '--' }} · 生成于 {{ report.generatedAt || '--' }}
        </view>
      </view>

      <view class="ai-card">
        <view class="ai-header">
          <view class="ai-title-row">
            <text class="ai-title">AI 个性化解读</text>
            <text
              v-if="!aiLoading"
              class="ai-refresh"
              @tap="fetchAiInterpretation(true)"
            >
              刷新
            </text>
          </view>
        </view>

        <view v-if="aiLoading" class="ai-loading">
          <view class="spinner-small" />
          <text class="ai-loading-text">AI 解读生成中…</text>
        </view>

        <view v-else-if="aiInterpretation?.available" class="ai-body">
          <view class="ai-content">{{ aiInterpretation.content }}</view>
          <view class="ai-disclaimer">{{ aiInterpretation.disclaimer }}</view>
          <view class="ai-meta">
            <text v-if="aiInterpretation.model" class="ai-meta-item">{{ aiInterpretation.model }}</text>
            <text v-if="aiInterpretation.generatedAt" class="ai-meta-item">生成于 {{ aiInterpretation.generatedAt }}</text>
          </view>
        </view>

        <view v-else-if="aiInterpretation" class="ai-unavailable">
          {{ aiInterpretation.reason || 'AI 解读暂不可用' }}
        </view>
      </view>

      <view class="disclaimer">
        本评估结果仅用于教学软件功能验证，不作为医学或临床诊断依据。
      </view>

      <view class="btn-group">
        <button class="btn-plain" @tap="goRecords">查看作答明细</button>
        <button class="btn-main" @tap="goActivities">返回活动列表</button>
      </view>
    </view>

    <!-- FAILED State -->
    <view v-else-if="report.taskStatus === 'FAILED'" class="failed-container">
      <view class="failed-card">
        <view class="failed-title">报告生成未完成</view>
        <view class="failed-desc">
          您的原始答卷（#{{ report.sheetId || sheetIdHint || '--' }}）已完整保存，无需重新填写。
        </view>

        <view class="retry-box">
          <view class="retry-row">
            <text class="retry-label">已重试</text>
            <text class="retry-val">{{ retryStats.retryCount }} / {{ retryStats.maxRetries }} 次</text>
          </view>
          <view class="retry-reason">
            <text class="reason-label">原因摘要：</text>
            <text class="reason-text">
              {{ report.lastError || '异步计分服务暂时繁忙，可尝试重新申请生成。' }}
            </text>
          </view>
        </view>

        <button
          v-if="retryStats.canRetry"
          class="btn-main"
          :loading="retrying"
          :disabled="retrying"
          @tap="handleRetryReport"
        >
          {{ retrying ? '申请中...' : '重新生成报告' }}
        </button>

        <view v-else class="limit-box">
          已达到自助重试次数上限，请联系心理健康教育中心管理员或辅导员处理。
        </view>
      </view>

      <view class="btn-group">
        <button class="btn-plain" @tap="goRecords">查看我的答卷</button>
        <button class="btn-plain" @tap="goActivities">返回活动列表</button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.report-page {
  min-height: 100vh;
  padding: 24rpx 30rpx 64rpx;
  background: #F5F6F8;
}

.selector-bar {
  white-space: nowrap;
  margin-bottom: 20rpx;
}

.selector-inner {
  display: inline-flex;
  gap: 16rpx;
}

.selector-chip {
  padding: 12rpx 22rpx;
  border-radius: 8rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  color: #525B6B;
  font-size: 24rpx;
}

.selector-chip.is-active {
  background: #2055A4;
  border-color: #2055A4;
  color: #FFFFFF;
  font-weight: 600;
}

/* State Card */
.state-card {
  margin-top: 24rpx;
  padding: 80rpx 40rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  text-align: center;
}

.spinner {
  width: 56rpx;
  height: 56rpx;
  margin: 0 auto 28rpx;
  border-radius: 50%;
  border: 4rpx solid #E8EAEE;
  border-top-color: #2055A4;
  animation: spin 0.9s linear infinite;
}

.spinner-large {
  width: 72rpx;
  height: 72rpx;
  margin: 0 auto 32rpx;
  border-radius: 50%;
  border: 6rpx solid #E8EAEE;
  border-top-color: #2055A4;
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.error-code {
  display: inline-block;
  margin-bottom: 18rpx;
  padding: 6rpx 18rpx;
  border-radius: 8rpx;
  background: #FDF2F2;
  color: #C0392B;
  font-size: 22rpx;
  font-weight: 700;
}

.state-title {
  color: #1A1F2C;
  font-size: 32rpx;
  font-weight: 700;
}

.state-desc {
  margin-top: 16rpx;
  color: #525B6B;
  font-size: 25rpx;
  line-height: 1.65;
}

.btn-group {
  display: flex;
  gap: 20rpx;
  margin-top: 32rpx;
}

.btn-main {
  flex: 1;
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 10rpx;
  background: #2055A4;
  color: #FFFFFF;
  font-size: 27rpx;
  font-weight: 600;
  text-align: center;
}

.btn-main:active {
  background: #184282;
}

.btn-plain {
  flex: 1;
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 10rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  color: #525B6B;
  font-size: 27rpx;
  text-align: center;
}

.single-btn {
  margin-top: 36rpx;
}

/* PENDING State */
.pending-container {
  margin-top: 24rpx;
}

.pending-card {
  padding: 72rpx 38rpx 52rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  text-align: center;
}

.pending-title {
  margin-top: 8rpx;
  color: #1A1F2C;
  font-size: 34rpx;
  font-weight: 700;
}

.pending-desc {
  margin-top: 14rpx;
  color: #525B6B;
  font-size: 26rpx;
  line-height: 1.6;
}

.poll-status {
  margin-top: 28rpx;
  padding: 18rpx 22rpx;
  border-radius: 10rpx;
  background: #FAFBFC;
  color: #2055A4;
  font-size: 24rpx;
  font-variant-numeric: tabular-nums;
}

.poll-status .is-paused {
  color: #B45309;
}

.pending-meta {
  margin-top: 22rpx;
  color: #8A93A2;
  font-size: 22rpx;
  font-variant-numeric: tabular-nums;
}

/* SUCCESS State */
.success-container {
  margin-top: 24rpx;
}

.report-card {
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  overflow: hidden;
}

.report-topbar {
  height: 6rpx;
  width: 100%;
}

.report-header {
  padding: 32rpx 30rpx 24rpx;
  text-align: center;
  border-bottom: 1rpx solid #E8EAEE;
}

.report-title {
  color: #1A1F2C;
  font-size: 34rpx;
  font-weight: 700;
}

.report-subtitle {
  margin-top: 8rpx;
  color: #525B6B;
  font-size: 24rpx;
}

.score-section {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 32rpx;
  padding: 40rpx 30rpx;
}

.score-block {
  display: flex;
  align-items: baseline;
}

.score-num {
  color: #1A1F2C;
  font-size: 72rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.score-unit {
  margin-left: 6rpx;
  color: #525B6B;
  font-size: 26rpx;
}

.level-block {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10rpx;
}

.level-badge {
  padding: 6rpx 18rpx;
  border-radius: 8rpx;
  color: #FFFFFF;
  font-size: 24rpx;
  font-weight: 600;
}

.level-name {
  color: #1A1F2C;
  font-size: 28rpx;
  font-weight: 700;
}

.section-divider {
  height: 1rpx;
  background: #E8EAEE;
  margin: 0 30rpx;
}

.dimensions-section,
.guidance-section {
  padding: 28rpx 30rpx;
}

.section-title {
  color: #1A1F2C;
  font-size: 28rpx;
  font-weight: 700;
  margin-bottom: 20rpx;
}

.dim-list {
  display: flex;
  flex-direction: column;
  gap: 22rpx;
}

.dim-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10rpx;
}

.dim-name {
  color: #525B6B;
  font-size: 26rpx;
}

.dim-score {
  color: #1A1F2C;
  font-size: 26rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.dim-bar-track {
  height: 10rpx;
  border-radius: 5rpx;
  background: #F5F6F8;
  overflow: hidden;
}

.dim-bar-fill {
  height: 100%;
  border-radius: 5rpx;
  transition: width 0.3s ease;
}

.dim-empty {
  color: #8A93A2;
  font-size: 25rpx;
  text-align: center;
  padding: 16rpx 0;
}

.guidance-text {
  color: #525B6B;
  font-size: 27rpx;
  line-height: 1.75;
}

.trace-section {
  padding: 22rpx 30rpx;
  background: #FAFBFC;
  color: #8A93A2;
  font-size: 21rpx;
  line-height: 1.55;
  font-variant-numeric: tabular-nums;
}

.disclaimer {
  margin-top: 24rpx;
  padding: 18rpx 24rpx;
  border-radius: 10rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  color: #8A93A2;
  font-size: 23rpx;
  text-align: center;
  line-height: 1.5;
}

/* AI Interpretation */
.ai-card {
  margin-top: 24rpx;
  padding: 28rpx 30rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
}

.ai-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ai-title {
  color: #1A1F2C;
  font-size: 28rpx;
  font-weight: 700;
}

.ai-refresh {
  color: #2055A4;
  font-size: 24rpx;
}

.ai-loading {
  display: flex;
  align-items: center;
  gap: 14rpx;
  margin-top: 24rpx;
  color: #525B6B;
  font-size: 26rpx;
}

.ai-loading-text {
  color: #525B6B;
}

.spinner-small {
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  border: 4rpx solid #E8EAEE;
  border-top-color: #2055A4;
  animation: spin 0.9s linear infinite;
}

.ai-body {
  margin-top: 20rpx;
}

.ai-content {
  color: #525B6B;
  font-size: 27rpx;
  line-height: 1.75;
  white-space: pre-wrap;
}

.ai-disclaimer {
  margin-top: 20rpx;
  color: #8A93A2;
  font-size: 23rpx;
  line-height: 1.6;
}

.ai-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 18rpx;
  color: #8A93A2;
  font-size: 21rpx;
  font-variant-numeric: tabular-nums;
}

.ai-unavailable {
  margin-top: 20rpx;
  color: #8A93A2;
  font-size: 26rpx;
  line-height: 1.6;
}

/* FAILED State */
.failed-container {
  margin-top: 24rpx;
}

.failed-card {
  padding: 52rpx 36rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #F5C6C6;
  text-align: center;
}

.failed-title {
  color: #C0392B;
  font-size: 34rpx;
  font-weight: 700;
}

.failed-desc {
  margin-top: 14rpx;
  color: #525B6B;
  font-size: 26rpx;
  line-height: 1.6;
}

.retry-box {
  margin-top: 28rpx;
  padding: 24rpx 26rpx;
  border-radius: 10rpx;
  background: #FDF2F2;
  border: 1rpx solid #F5C6C6;
  text-align: left;
}

.retry-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.retry-label {
  color: #525B6B;
  font-size: 25rpx;
}

.retry-val {
  color: #C0392B;
  font-size: 25rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.retry-reason {
  margin-top: 14rpx;
  padding-top: 14rpx;
  border-top: 1rpx dashed #F5C6C6;
  font-size: 24rpx;
  line-height: 1.55;
}

.reason-label {
  color: #C0392B;
  font-weight: 600;
}

.reason-text {
  color: #525B6B;
}

.limit-box {
  margin-top: 28rpx;
  padding: 22rpx 24rpx;
  border-radius: 10rpx;
  background: #FAFBFC;
  border: 1rpx solid #E8EAEE;
  color: #525B6B;
  font-size: 25rpx;
  line-height: 1.6;
  text-align: left;
}
</style>
