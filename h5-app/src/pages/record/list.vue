<script setup>
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMyRecordDetailApi, getMyRecordsApi } from '../../api/h5'

const loading = ref(false)
const records = ref([])
const pendingSheetId = ref('')

const showDetailModal = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const activeDetail = ref(null)

onLoad((query) => {
  const targetId = query?.sheetId || query?.id || ''
  if (targetId) {
    pendingSheetId.value = String(targetId).trim()
  }
})

onShow(async () => {
  const token = uni.getStorageSync('token')
  if (!token) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    setTimeout(() => {
      uni.reLaunch({ url: '/pages/login/login' })
    }, 120)
    return
  }

  await fetchRecords()

  if (pendingSheetId.value) {
    const target = pendingSheetId.value
    pendingSheetId.value = ''
    openDetailModal(target)
  }
})

onPullDownRefresh(async () => {
  await fetchRecords(true)
  uni.stopPullDownRefresh()
})

async function fetchRecords(showToast = false) {
  loading.value = true
  try {
    const res = await getMyRecordsApi()
    records.value = Array.isArray(res?.data) ? res.data : []
    if (showToast) {
      uni.showToast({ title: '已刷新提交记录', icon: 'none' })
    }
  } catch (e) {
    // Handled in request.js
  } finally {
    loading.value = false
  }
}

function getSheetId(item) {
  return item?.sheetId ?? item?.id
}

function getStatusClass(status) {
  if (status === 'SUCCESS') return 'status-success'
  if (status === 'FAILED') return 'status-failed'
  return 'status-pending'
}

function getStatusText(status) {
  if (status === 'SUCCESS') return '报告已生成'
  if (status === 'FAILED') return '报告生成未完成'
  return '报告生成中'
}

function formatScore(val) {
  if (val === null || val === undefined || val === '') return '--'
  const num = Number(val)
  if (Number.isNaN(num)) return String(val)
  return Number.isInteger(num) ? String(num) : num.toFixed(2).replace(/\.00$/, '')
}

async function openDetailModal(sheetId) {
  if (!sheetId) return
  showDetailModal.value = true
  detailLoading.value = true
  detailError.value = ''
  activeDetail.value = null

  try {
    const res = await getMyRecordDetailApi(sheetId)
    activeDetail.value = res?.data || null
  } catch (err) {
    detailError.value = err?.message || '无法读取该份答卷明细'
  } finally {
    detailLoading.value = false
  }
}

function closeDetailModal() {
  showDetailModal.value = false
}

const normalizedDetailAnswers = computed(() => {
  const d = activeDetail.value || {}
  const rawList = Array.isArray(d.answers)
    ? d.answers
    : Array.isArray(d.items)
      ? d.items
      : Array.isArray(d.answerItems)
        ? d.answerItems
        : Array.isArray(d.questions)
          ? d.questions
          : []

  return [...rawList]
    .sort((a, b) => Number(a.questionNo) - Number(b.questionNo))
    .map((item) => {
      const optCode = item.selectedOptCode ?? item.optCode ?? ''
      let optLabel = item.selectedOptLabel ?? item.optLabel ?? item.label ?? ''
      if (!optLabel && Array.isArray(item.options)) {
        const matched = item.options.find((o) => String(o.optCode) === String(optCode))
        if (matched) optLabel = matched.label || ''
      }
      return {
        questionNo: item.questionNo,
        stem: item.stem || item.questionStem || item.title || `第 ${item.questionNo} 题`,
        selectedOptCode: optCode,
        selectedOptLabel: optLabel
      }
    })
})

function goReport(reportId, sheetId) {
  if (!reportId) {
    uni.showToast({ title: '暂未关联报告编号', icon: 'none' })
    return
  }
  const suffix = sheetId ? `&sheetId=${sheetId}` : ''
  uni.navigateTo({
    url: `/pages/report/report?reportId=${reportId}${suffix}`
  })
}

function goActivities() {
  uni.reLaunch({ url: '/pages/activity/list' })
}
</script>

<template>
  <view class="record-page">
    <!-- Header -->
    <view class="page-header">
      <view>
        <view class="header-title">我的提交记录</view>
        <view class="header-sub">仅本人可见</view>
      </view>
      <view class="count-box">
        <text class="count-num">{{ records.length }}</text>
        <text class="count-unit">份</text>
      </view>
    </view>

    <!-- Loading -->
    <view v-if="loading && records.length === 0" class="loading-card">
      <text class="loading-text">正在读取提交记录...</text>
    </view>

    <!-- Records List -->
    <view v-else-if="records.length > 0" class="record-list">
      <view
        v-for="item in records"
        :key="getSheetId(item)"
        class="record-card"
      >
        <view class="card-header">
          <text class="activity-title">{{ item.activityTitle || '心理健康评估活动' }}</text>
          <text class="status-text" :class="getStatusClass(item.taskStatus)">
            {{ getStatusText(item.taskStatus) }}
          </text>
        </view>

        <view class="card-meta">
          <text class="version-tag">v{{ item.versionNum || 1 }}</text>
          <text class="questionnaire-title">{{ item.questionnaireTitle || '评估问卷快照' }}</text>
        </view>

        <view class="info-row">
          <view class="info-cell">
            <text class="info-label">答卷编号</text>
            <text class="info-value">#{{ getSheetId(item) }}</text>
          </view>
          <view class="info-cell">
            <text class="info-label">综合总分</text>
            <text class="info-value score">{{ item.taskStatus === 'SUCCESS' ? formatScore(item.totalScore) : '--' }}</text>
          </view>
          <view class="info-cell">
            <text class="info-label">评估结果</text>
            <text class="info-value level">{{ item.levelName || (item.taskStatus === 'PENDING' ? '计算中' : '--') }}</text>
          </view>
        </view>

        <view class="time-row">
          提交时间：{{ item.submittedAt || '--' }}
        </view>

        <view class="action-row">
          <button
            class="btn-plain"
            @tap="openDetailModal(getSheetId(item))"
          >
            作答明细
          </button>
          <button
            class="btn-main"
            @tap="goReport(item.reportId, getSheetId(item))"
          >
            查看报告
          </button>
        </view>
      </view>
    </view>

    <!-- Empty State -->
    <view v-else class="empty-card">
      <view class="empty-title">您尚未提交过评估答卷</view>
      <view class="empty-desc">
        前往「我的评估活动」完成开放中的问卷后，这里将保留您的作答明细与报告入口。
      </view>
      <button class="empty-btn" @tap="goActivities">前往我的评估活动</button>
    </view>

    <view v-if="records.length > 0" class="bottom-action">
      <button class="btn-plain" @tap="goActivities">返回我的评估活动</button>
    </view>

    <!-- Detail Modal -->
    <view v-if="showDetailModal" class="modal-mask" @tap="closeDetailModal">
      <view class="detail-popup" @tap.stop>
        <view class="popup-header">
          <view class="popup-title-wrap">
            <text class="popup-title">作答明细</text>
            <text v-if="activeDetail" class="popup-sub">
              答卷 #{{ activeDetail.sheetId || activeDetail.id || '--' }} · {{ activeDetail.submittedAt || '' }}
            </text>
          </view>
          <text class="popup-close" @tap="closeDetailModal">关闭</text>
        </view>

        <scroll-view scroll-y class="popup-scroll">
          <view v-if="detailLoading" class="popup-state">
            <text class="popup-state-text">正在加载题目与已选选项...</text>
          </view>

          <view v-else-if="detailError" class="popup-state">
            <text class="popup-error-text">{{ detailError }}</text>
          </view>

          <view v-else-if="activeDetail" class="detail-content">
            <view class="detail-banner">
              <text class="detail-q-name">
                {{ activeDetail.questionnaireTitle || activeDetail.activityTitle || '评估问卷快照' }}
              </text>
              <text class="detail-status" :class="getStatusClass(activeDetail.taskStatus)">
                {{ getStatusText(activeDetail.taskStatus) }}
              </text>
            </view>

            <view v-if="normalizedDetailAnswers.length > 0" class="answer-items">
              <view
                v-for="ans in normalizedDetailAnswers"
                :key="ans.questionNo"
                class="answer-item"
              >
                <view class="answer-stem">
                  <text class="answer-no">{{ ans.questionNo }}.</text>
                  <text class="answer-text">{{ ans.stem }}</text>
                </view>
                <view class="answer-selected">
                  <text class="selected-label">已选：{{ ans.selectedOptLabel || `选项 ${ans.selectedOptCode}` }}</text>
                </view>
              </view>
            </view>

            <view v-else class="popup-state">
              <text class="popup-state-text">暂无可展示的单题作答明细</text>
            </view>
          </view>
        </scroll-view>

        <view class="popup-footer">
          <button class="btn-plain" @tap="closeDetailModal">关闭</button>
          <button
            v-if="activeDetail?.reportId"
            class="btn-main"
            @tap="goReport(activeDetail.reportId, activeDetail.sheetId || activeDetail.id)"
          >
            查看关联报告
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.record-page {
  min-height: 100vh;
  padding: 24rpx 30rpx 64rpx;
  background: #F5F6F8;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 26rpx 28rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
}

.header-title {
  color: #1A1F2C;
  font-size: 32rpx;
  font-weight: 700;
}

.header-sub {
  margin-top: 6rpx;
  color: #8A93A2;
  font-size: 22rpx;
}

.count-box {
  text-align: right;
}

.count-num {
  color: #2055A4;
  font-size: 40rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.count-unit {
  color: #525B6B;
  font-size: 22rpx;
}

.loading-card {
  margin-top: 24rpx;
  padding: 72rpx 32rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  text-align: center;
}

.loading-text {
  color: #525B6B;
  font-size: 26rpx;
}

.record-list {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
  margin-top: 24rpx;
}

.record-card {
  padding: 28rpx 26rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
}

.card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16rpx;
}

.activity-title {
  flex: 1;
  color: #1A1F2C;
  font-size: 30rpx;
  font-weight: 700;
  line-height: 1.45;
}

.status-text {
  flex-shrink: 0;
  font-size: 23rpx;
  font-weight: 600;
}

.status-success {
  color: #1E7E4E;
}

.status-pending {
  color: #B45309;
}

.status-failed {
  color: #C0392B;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 16rpx;
}

.version-tag {
  padding: 4rpx 12rpx;
  border-radius: 6rpx;
  background: #F5F6F8;
  color: #525B6B;
  font-size: 21rpx;
  font-variant-numeric: tabular-nums;
}

.questionnaire-title {
  flex: 1;
  color: #525B6B;
  font-size: 24rpx;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.info-row {
  display: flex;
  margin-top: 22rpx;
  padding: 20rpx 0;
  border-radius: 10rpx;
  background: #FAFBFC;
}

.info-cell {
  flex: 1;
  text-align: center;
  position: relative;
}

.info-cell:not(:last-child)::after {
  content: '';
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 1rpx;
  height: 44rpx;
  background: #E8EAEE;
}

.info-label {
  display: block;
  color: #8A93A2;
  font-size: 22rpx;
}

.info-value {
  display: block;
  margin-top: 8rpx;
  color: #1A1F2C;
  font-size: 27rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.info-value.score {
  color: #2055A4;
}

.info-value.level {
  color: #1E7E4E;
}

.time-row {
  margin-top: 16rpx;
  color: #8A93A2;
  font-size: 23rpx;
  font-variant-numeric: tabular-nums;
}

.action-row {
  display: flex;
  gap: 18rpx;
  margin-top: 22rpx;
}

.btn-main {
  flex: 1.2;
  height: 80rpx;
  line-height: 80rpx;
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
  height: 80rpx;
  line-height: 80rpx;
  border-radius: 10rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  color: #525B6B;
  font-size: 27rpx;
  text-align: center;
}

.empty-card {
  margin-top: 24rpx;
  padding: 80rpx 40rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  text-align: center;
}

.empty-title {
  color: #1A1F2C;
  font-size: 30rpx;
  font-weight: 700;
}

.empty-desc {
  margin-top: 14rpx;
  color: #525B6B;
  font-size: 24rpx;
  line-height: 1.65;
}

.empty-btn {
  margin-top: 32rpx;
  padding: 0 44rpx;
  height: 80rpx;
  line-height: 80rpx;
  border-radius: 10rpx;
  background: #EFF4FC;
  color: #2055A4;
  font-size: 27rpx;
  font-weight: 600;
}

.bottom-action {
  margin-top: 32rpx;
}

/* Modal */
.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 999;
  background: rgba(26, 31, 44, 0.5);
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.detail-popup {
  width: 100%;
  max-height: 84vh;
  display: flex;
  flex-direction: column;
  border-radius: 24rpx 24rpx 0 0;
  background: #FFFFFF;
  overflow: hidden;
}

.popup-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 30rpx 32rpx 24rpx;
  border-bottom: 1rpx solid #E8EAEE;
}

.popup-title {
  display: block;
  color: #1A1F2C;
  font-size: 32rpx;
  font-weight: 700;
}

.popup-sub {
  display: block;
  margin-top: 6rpx;
  color: #8A93A2;
  font-size: 22rpx;
  font-variant-numeric: tabular-nums;
}

.popup-close {
  color: #8A93A2;
  font-size: 26rpx;
  padding: 6rpx;
}

.popup-scroll {
  max-height: 58vh;
  padding: 24rpx 32rpx;
}

.popup-state {
  padding: 64rpx 24rpx;
  text-align: center;
}

.popup-state-text {
  color: #525B6B;
  font-size: 26rpx;
}

.popup-error-text {
  color: #C0392B;
  font-size: 26rpx;
}

.detail-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 18rpx 22rpx;
  margin-bottom: 20rpx;
  border-radius: 10rpx;
  background: #FAFBFC;
  border: 1rpx solid #E8EAEE;
}

.detail-q-name {
  flex: 1;
  color: #525B6B;
  font-size: 25rpx;
}

.detail-status {
  flex-shrink: 0;
  font-size: 21rpx;
  font-weight: 600;
}

.answer-items {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  padding-bottom: 16rpx;
}

.answer-item {
  padding: 22rpx 24rpx;
  border-radius: 10rpx;
  background: #FAFBFC;
  border: 1rpx solid #E8EAEE;
}

.answer-stem {
  display: flex;
  align-items: flex-start;
  gap: 10rpx;
}

.answer-no {
  flex-shrink: 0;
  color: #2055A4;
  font-size: 25rpx;
  font-weight: 700;
}

.answer-text {
  flex: 1;
  color: #1A1F2C;
  font-size: 26rpx;
  line-height: 1.5;
}

.answer-selected {
  margin-top: 14rpx;
  padding: 12rpx 18rpx;
  border-radius: 8rpx;
  background: #FFFFFF;
  border: 1rpx solid #D6E3F7;
}

.selected-label {
  color: #2055A4;
  font-size: 25rpx;
}

.popup-footer {
  display: flex;
  gap: 20rpx;
  padding: 24rpx 32rpx calc(24rpx + env(safe-area-inset-bottom));
  border-top: 1rpx solid #E8EAEE;
  background: #FFFFFF;
}
</style>
