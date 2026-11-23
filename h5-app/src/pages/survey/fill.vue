<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { getActivityDetailApi, submitActivityAnswersApi } from '../../api/h5'

const activityId = ref('')
const loading = ref(true)
const submitting = ref(false)
const loadError = ref(null)
const detail = ref(null)
const missingQuestionNo = ref(null)

const answers = reactive({})

onLoad((query) => {
  const id = query?.activityId || query?.id || ''
  activityId.value = String(id).trim()
  if (!activityId.value) {
    loading.value = false
    loadError.value = {
      statusCode: 400,
      message: '缺少评估活动参数（activityId），请从「我的评估活动」页面进入。'
    }
    return
  }
  fetchDetail()
})

const questionnaireInfo = computed(() => {
  const d = detail.value || {}
  const snap = d.questionnaire || d.version || d.questionnaireVersion || {}
  return {
    activityTitle: d.activityTitle || (snap.title ? d.title : '') || '',
    title: d.questionnaireTitle || snap.title || d.title || '心理健康评估问卷',
    description:
      d.description ||
      d.questionnaireDescription ||
      snap.description ||
      '请根据您近期的真实感受独立选择最符合的一项，答案无对错之分。',
    versionNum: d.versionNum ?? snap.versionNum ?? 1,
    ruleVersion: d.ruleVersion ?? snap.ruleVersion ?? '',
    cacheSource: d.cacheSource || '',
    submitted: Boolean(d.submitted),
    canSubmit: d.canSubmit !== false,
    statusText: d.statusText || '',
    sheetId: d.sheetId ?? null,
    reportId: d.reportId ?? null
  }
})

const sortedQuestions = computed(() => {
  const d = detail.value || {}
  const snap = d.questionnaire || d.version || d.questionnaireVersion || {}
  const rawList = Array.isArray(d.questions)
    ? d.questions
    : Array.isArray(snap.questions)
      ? snap.questions
      : []

  return [...rawList]
    .sort((a, b) => Number(a.questionNo) - Number(b.questionNo))
    .map((q) => {
      const rawOptions = Array.isArray(q.options) ? q.options : []
      const sortedOptions = [...rawOptions].sort(
        (x, y) => Number(x.sort ?? 0) - Number(y.sort ?? 0)
      )
      const isRequired = q.required === undefined ? true : Boolean(Number(q.required) || q.required === true)
      return {
        questionNo: q.questionNo,
        stem: q.stem || q.title || '',
        questionType: q.questionType || 'SINGLE',
        required: isRequired,
        options: sortedOptions.map((opt) => ({
          optCode: String(opt.optCode),
          label: opt.label || '',
          sort: opt.sort ?? 0
        }))
      }
    })
})

const totalCount = computed(() => sortedQuestions.value.length)

const answeredCount = computed(() =>
  sortedQuestions.value.reduce((acc, q) => {
    return answers[q.questionNo] ? acc + 1 : acc
  }, 0)
)

const progressPercent = computed(() => {
  if (!totalCount.value) return 0
  return Math.round((answeredCount.value / totalCount.value) * 100)
})

const submitStatusText = computed(() => {
  if (submitting.value) return '提交中...'
  if (!questionnaireInfo.value.canSubmit) return questionnaireInfo.value.statusText || '当前不可提交'
  if (questionnaireInfo.value.submitted) return '已提交，请勿重复提交'
  return '提交答卷'
})

async function fetchDetail() {
  loading.value = true
  loadError.value = null
  try {
    const res = await getActivityDetailApi(activityId.value)
    detail.value = res?.data || null
  } catch (err) {
    loadError.value = {
      statusCode: err?.statusCode || 400,
      message: err?.message || '无法加载活动问卷快照'
    }
  } finally {
    loading.value = false
  }
}

function selectOption(questionNo, optCode) {
  answers[questionNo] = String(optCode)
  if (missingQuestionNo.value === questionNo) {
    missingQuestionNo.value = null
  }
}

function scrollToQuestion(questionNo) {
  uni.pageScrollTo({
    selector: `#question-card-${questionNo}`,
    duration: 260
  })
}

async function handleSubmit() {
  if (submitting.value) return
  if (!questionnaireInfo.value.canSubmit) {
    uni.showToast({ title: submitStatusText.value, icon: 'none' })
    return
  }
  if (!sortedQuestions.value.length) {
    uni.showToast({ title: '当前问卷暂无可提交题目', icon: 'none' })
    return
  }

  const firstUnanswered = sortedQuestions.value.find(
    (q) => q.required && !answers[q.questionNo]
  )
  if (firstUnanswered) {
    missingQuestionNo.value = firstUnanswered.questionNo
    uni.showToast({
      title: `第 ${firstUnanswered.questionNo} 题尚未作答`,
      icon: 'none'
    })
    scrollToQuestion(firstUnanswered.questionNo)
    return
  }

  const payload = {
    answers: sortedQuestions.value
      .filter((q) => Boolean(answers[q.questionNo]))
      .map((q) => ({
        questionNo: Number(q.questionNo),
        selectedOptCode: String(answers[q.questionNo])
      }))
  }

  submitting.value = true
  try {
    const res = await submitActivityAnswersApi(activityId.value, payload, {
      skipErrorToast: true
    })
    const reportId = res?.data?.reportId ?? ''
    const sheetId = res?.data?.sheetId ?? ''

    uni.showToast({ title: '答卷已提交', icon: 'success' })
    setTimeout(() => {
      uni.redirectTo({
        url: `/pages/report/report?reportId=${reportId}&sheetId=${sheetId}`
      })
    }, 300)
  } catch (err) {
    // CRITICAL REQ06: HTTP 409 Duplicate / Concurrent Submission Conflict
    if (err?.statusCode === 409 || err?.code === 409) {
      const conflictData = err?.data || {}
      const sheetId = conflictData.sheetId ?? questionnaireInfo.value.sheetId ?? ''
      const reportId = conflictData.reportId ?? questionnaireInfo.value.reportId ?? ''

      uni.showModal({
        title: '已存在提交记录',
        content: `${err.message || '您已提交过本次评估答卷，系统仅保留一份有效答卷。'}（原答卷编号 #${sheetId || '--'}）`,
        confirmText: '查看报告',
        cancelText: '查看记录',
        confirmColor: '#2055A4',
        success: (modalRes) => {
          if (modalRes.confirm) {
            uni.redirectTo({
              url: `/pages/report/report?reportId=${reportId}&sheetId=${sheetId}`
            })
          } else if (modalRes.cancel) {
            uni.redirectTo({
              url: `/pages/record/list?sheetId=${sheetId}`
            })
          }
        }
      })
      return
    }

    if (err?.statusCode !== 401) {
      uni.showToast({
        title: err?.message || '提交答卷失败，请检查后重试',
        icon: 'none'
      })
    }
  } finally {
    submitting.value = false
  }
}

function goExistingReport() {
  const { reportId, sheetId } = questionnaireInfo.value
  uni.navigateTo({
    url: `/pages/report/report?reportId=${reportId || ''}&sheetId=${sheetId || ''}`
  })
}

function goExistingRecord() {
  const { sheetId } = questionnaireInfo.value
  uni.navigateTo({
    url: `/pages/record/list?sheetId=${sheetId || ''}`
  })
}

function goBackActivities() {
  uni.reLaunch({ url: '/pages/activity/list' })
}
</script>

<template>
  <view class="fill-page">
    <!-- Loading State -->
    <view v-if="loading" class="state-card">
      <view class="spinner" />
      <view class="state-title">正在加载问卷...</view>
      <view class="state-desc">请稍候，正在读取活动问卷内容</view>
    </view>

    <!-- Error / 403 / 400 State -->
    <view v-else-if="loadError" class="state-card">
      <view class="error-code">HTTP {{ loadError.statusCode }}</view>
      <view class="state-title">
        {{ loadError.statusCode === 403 ? '无权访问该评估活动' : '当前无法打开该问卷' }}
      </view>
      <view class="state-desc">{{ loadError.message }}</view>
      <button class="state-btn" @tap="goBackActivities">返回活动列表</button>
    </view>

    <!-- Dynamic Survey Content -->
    <template v-else>
      <!-- Sticky Progress Header -->
      <view class="progress-header">
        <view class="progress-row">
          <text class="progress-label">答题进度 {{ answeredCount }} / {{ totalCount }}</text>
          <text class="progress-percent">{{ progressPercent }}%</text>
        </view>
        <view class="progress-track">
          <view class="progress-fill" :style="{ width: `${progressPercent}%` }" />
        </view>
      </view>

      <view class="content-body">
        <!-- Already Submitted Banner -->
        <view v-if="questionnaireInfo.submitted" class="submitted-notice">
          <view class="notice-title">您已提交过本活动答卷</view>
          <view class="notice-desc">
            同一活动每位同学仅保留一份有效答卷，可查看评估报告或作答明细。
          </view>
          <view class="notice-actions">
            <text class="notice-link" @tap="goExistingReport">查看原报告</text>
            <text class="notice-link" @tap="goExistingRecord">查看记录</text>
          </view>
        </view>

        <!-- Questionnaire Header -->
        <view class="intro-card">
          <view v-if="questionnaireInfo.activityTitle" class="activity-sub">
            {{ questionnaireInfo.activityTitle }}
          </view>
          <view class="intro-title">{{ questionnaireInfo.title }}</view>
          <view class="intro-desc">{{ questionnaireInfo.description }}</view>
        </view>

        <!-- 100% Data-Driven Single-Choice Questions -->
        <view
          v-for="question in sortedQuestions"
          :id="`question-card-${question.questionNo}`"
          :key="question.questionNo"
          class="question-card"
          :class="{
            'is-answered': Boolean(answers[question.questionNo]),
            'is-missing': missingQuestionNo === question.questionNo
          }"
        >
          <view class="question-header">
            <text class="question-no">{{ question.questionNo }}.</text>
            <text class="question-stem">{{ question.stem }}</text>
            <text v-if="question.required" class="required-mark">*</text>
          </view>

          <view class="options-list">
            <view
              v-for="opt in question.options"
              :key="opt.optCode"
              class="option-row"
              :class="{ 'is-selected': answers[question.questionNo] === opt.optCode }"
              @tap="selectOption(question.questionNo, opt.optCode)"
            >
              <view class="radio">
                <view v-if="answers[question.questionNo] === opt.optCode" class="radio-dot" />
              </view>
              <text class="option-label">{{ opt.label }}</text>
            </view>
          </view>
        </view>

        <!-- Bottom Spacer for Fixed Footer -->
        <view class="footer-spacer" />
      </view>

      <!-- Fixed Submit Footer -->
      <view class="submit-footer">
        <button
          class="submit-btn"
          :class="{
            'is-disabled': submitting || !questionnaireInfo.canSubmit || questionnaireInfo.submitted,
            'is-submitting': submitting
          }"
          :loading="submitting"
          :disabled="submitting || !questionnaireInfo.canSubmit || questionnaireInfo.submitted"
          @tap="handleSubmit"
        >
          {{ submitStatusText }}
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.fill-page {
  min-height: 100vh;
  background: #FFFFFF;
  padding-bottom: calc(120rpx + env(safe-area-inset-bottom));
}

.progress-header {
  position: sticky;
  top: 0;
  z-index: 20;
  padding: 18rpx 30rpx;
  background: #FFFFFF;
  border-bottom: 1rpx solid #E8EAEE;
}

.progress-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10rpx;
}

.progress-label {
  color: #1A1F2C;
  font-size: 26rpx;
}

.progress-percent {
  color: #2055A4;
  font-size: 26rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.progress-track {
  height: 6rpx;
  border-radius: 3rpx;
  background: #E8EAEE;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #2055A4;
  transition: width 0.2s ease;
}

.content-body {
  padding: 28rpx 30rpx 0;
}

.submitted-notice {
  margin-bottom: 24rpx;
  padding: 24rpx;
  border-radius: 10rpx;
  background: #EFF4FC;
  border: 1rpx solid #D6E3F7;
}

.notice-title {
  color: #1A1F2C;
  font-size: 28rpx;
  font-weight: 700;
}

.notice-desc {
  margin-top: 8rpx;
  color: #525B6B;
  font-size: 24rpx;
  line-height: 1.55;
}

.notice-actions {
  display: flex;
  gap: 24rpx;
  margin-top: 16rpx;
}

.notice-link {
  color: #2055A4;
  font-size: 26rpx;
  font-weight: 600;
}

.intro-card {
  margin-bottom: 28rpx;
  padding-bottom: 28rpx;
  border-bottom: 1rpx solid #E8EAEE;
}

.activity-sub {
  color: #525B6B;
  font-size: 24rpx;
  margin-bottom: 10rpx;
}

.intro-title {
  color: #1A1F2C;
  font-size: 34rpx;
  font-weight: 700;
  line-height: 1.45;
}

.intro-desc {
  margin-top: 14rpx;
  color: #525B6B;
  font-size: 26rpx;
  line-height: 1.65;
}

.question-card {
  margin-bottom: 24rpx;
  padding: 28rpx 26rpx;
  border-radius: 10rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
}

.question-card.is-answered {
  border-color: #2055A4;
}

.question-card.is-missing {
  border-color: #C0392B;
}

.question-header {
  display: flex;
  align-items: flex-start;
  margin-bottom: 22rpx;
}

.question-no {
  flex-shrink: 0;
  margin-right: 10rpx;
  color: #1A1F2C;
  font-size: 30rpx;
  font-weight: 700;
}

.question-stem {
  flex: 1;
  color: #1A1F2C;
  font-size: 30rpx;
  line-height: 1.55;
}

.required-mark {
  flex-shrink: 0;
  margin-left: 8rpx;
  color: #C0392B;
  font-size: 30rpx;
}

.options-list {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.option-row {
  display: flex;
  align-items: center;
  padding: 22rpx 24rpx;
  border-radius: 10rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
}

.option-row.is-selected {
  background: #EFF4FC;
  border-color: #2055A4;
}

.radio {
  flex-shrink: 0;
  width: 36rpx;
  height: 36rpx;
  margin-right: 18rpx;
  border-radius: 50%;
  background: #FFFFFF;
  border: 2rpx solid #C4C9D4;
  display: flex;
  align-items: center;
  justify-content: center;
}

.option-row.is-selected .radio {
  border-color: #2055A4;
}

.radio-dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
  background: #2055A4;
}

.option-label {
  flex: 1;
  color: #1A1F2C;
  font-size: 28rpx;
  line-height: 1.45;
}

.option-row.is-selected .option-label {
  color: #2055A4;
  font-weight: 600;
}

.footer-spacer {
  height: 40rpx;
}

.submit-footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 30;
  padding: 20rpx 30rpx calc(20rpx + env(safe-area-inset-bottom));
  background: #FFFFFF;
  border-top: 1rpx solid #E8EAEE;
}

.submit-btn {
  width: 100%;
  height: 92rpx;
  line-height: 92rpx;
  border-radius: 10rpx;
  background: #2055A4;
  color: #FFFFFF;
  font-size: 30rpx;
  font-weight: 600;
  text-align: center;
}

.submit-btn:active {
  background: #184282;
}

.submit-btn.is-disabled {
  background: #E8EAEE;
  color: #8A93A2;
}

.submit-btn.is-submitting {
  opacity: 0.85;
}

/* State Card */
.state-card {
  margin: 48rpx 32rpx;
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

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.error-code {
  display: inline-block;
  margin-bottom: 20rpx;
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
  font-size: 26rpx;
  line-height: 1.65;
}

.state-btn {
  margin-top: 40rpx;
  width: 100%;
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 10rpx;
  background: #2055A4;
  color: #FFFFFF;
  font-size: 28rpx;
  font-weight: 600;
}

.state-btn:active {
  background: #184282;
}
</style>
