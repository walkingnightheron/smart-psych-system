<script setup>
import { computed, reactive, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMeApi, getMyActivitiesApi, updateProfileApi } from '../../api/h5'

const loading = ref(false)
const forbiddenMessage = ref('')
const user = ref(uni.getStorageSync('user') || null)
const activities = ref([])

const showProfileModal = ref(false)
const savingProfile = ref(false)
const profileForm = reactive({
  realName: '',
  deptClass: '',
  phone: ''
})

const showBoundaryPanel = ref(false)
const boundaryForm = reactive({
  activityId: '1',
  reportId: '1'
})

const avatarChar = computed(() => {
  const name = user.value?.realName || user.value?.username || '学'
  return String(name).trim().charAt(0)
})

onShow(() => {
  const token = uni.getStorageSync('token')
  if (!token) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    setTimeout(() => {
      uni.reLaunch({ url: '/pages/login/login' })
    }, 120)
    return
  }
  loadData(false)
})

onPullDownRefresh(async () => {
  await loadData(true)
  uni.stopPullDownRefresh()
})

async function loadData(showRefreshToast = false) {
  loading.value = true
  forbiddenMessage.value = ''

  const [meRes, actRes] = await Promise.allSettled([
    getMeApi(),
    getMyActivitiesApi()
  ])

  if (meRes.status === 'fulfilled' && meRes.value?.data) {
    user.value = meRes.value.data
    uni.setStorageSync('user', meRes.value.data)
  }

  if (actRes.status === 'fulfilled') {
    const list = Array.isArray(actRes.value?.data) ? actRes.value.data : []
    activities.value = list
  } else {
    const err = actRes.reason || {}
    if (err.statusCode === 403) {
      forbiddenMessage.value = err.message || '当前账号无权访问学生端评估活动（403）'
      activities.value = []
    }
  }

  loading.value = false
  if (showRefreshToast && !forbiddenMessage.value) {
    uni.showToast({ title: '已同步最新活动状态', icon: 'none' })
  }
}

function openProfileModal() {
  profileForm.realName = user.value?.realName || ''
  profileForm.deptClass = user.value?.deptClass || ''
  profileForm.phone = user.value?.phone || ''
  showProfileModal.value = true
}

async function saveProfile() {
  const realName = profileForm.realName.trim()
  const deptClass = profileForm.deptClass.trim()
  const phone = profileForm.phone.trim()

  if (!realName) {
    uni.showToast({ title: '姓名不能为空', icon: 'none' })
    return
  }
  if (phone && !/^[0-9+\-\s]{6,20}$/.test(phone)) {
    uni.showToast({ title: '请输入有效的联系电话格式', icon: 'none' })
    return
  }

  savingProfile.value = true
  try {
    const res = await updateProfileApi({
      realName,
      deptClass,
      phone
    })
    if (res?.data) {
      user.value = res.data
      uni.setStorageSync('user', res.data)
    } else if (user.value) {
      user.value = { ...user.value, realName, deptClass, phone }
      uni.setStorageSync('user', user.value)
    }
    showProfileModal.value = false
    uni.showToast({ title: '个人资料已更新', icon: 'none' })
  } catch (e) {
    // Error handled in request.js
  } finally {
    savingProfile.value = false
  }
}

function handleLogout() {
  uni.showModal({
    title: '退出登录',
    content: '确定退出当前账号并返回登录页吗？',
    confirmText: '退出',
    confirmColor: '#C0392B',
    success: (res) => {
      if (res.confirm) {
        uni.removeStorageSync('token')
        uni.removeStorageSync('user')
        uni.reLaunch({ url: '/pages/login/login' })
      }
    }
  })
}

function getActivityId(item) {
  return item.id ?? item.activityId
}

function getStatusClass(item) {
  if (item.canSubmit === true) return 'status-open'
  if (item.submitted === true) return 'status-submitted'
  if (item.timeWindowStatus === 'NOT_STARTED') return 'status-upcoming'
  if (item.timeWindowStatus === 'EXPIRED') return 'status-closed'
  return 'status-closed'
}

function getStatusLabel(item) {
  if (item.statusText) return item.statusText
  if (item.submitted) return '已提交'
  if (item.canSubmit) return '进行中'
  if (item.status === 'CLOSED') return '已关闭'
  if (item.timeWindowStatus === 'NOT_STARTED') return '未开始'
  if (item.timeWindowStatus === 'EXPIRED') return '已截止'
  return '暂不可填写'
}

function goFill(item) {
  const id = getActivityId(item)
  uni.navigateTo({ url: `/pages/survey/fill?activityId=${id}` })
}

function goReport(reportId, sheetId) {
  if (!reportId) {
    uni.navigateTo({ url: '/pages/report/report' })
    return
  }
  const suffix = sheetId ? `&sheetId=${sheetId}` : ''
  uni.navigateTo({ url: `/pages/report/report?reportId=${reportId}${suffix}` })
}

function goRecordList(sheetId) {
  if (sheetId) {
    uni.navigateTo({ url: `/pages/record/list?sheetId=${sheetId}` })
  } else {
    uni.navigateTo({ url: '/pages/record/list' })
  }
}

function testUnauthorizedActivity() {
  const id = String(boundaryForm.activityId || '').trim()
  if (!id) {
    uni.showToast({ title: '请输入要测试的活动 ID', icon: 'none' })
    return
  }
  uni.navigateTo({ url: `/pages/survey/fill?activityId=${encodeURIComponent(id)}` })
}

function testUnauthorizedReport() {
  const id = String(boundaryForm.reportId || '').trim()
  if (!id) {
    uni.showToast({ title: '请输入要测试的报告 ID', icon: 'none' })
    return
  }
  uni.navigateTo({ url: `/pages/report/report?reportId=${encodeURIComponent(id)}` })
}
</script>

<template>
  <view class="activity-page">
    <!-- Personal Info Header -->
    <view class="profile-bar">
      <view class="profile-left">
        <view class="avatar">
          <text class="avatar-text">{{ avatarChar }}</text>
        </view>
        <view class="profile-meta">
          <view class="name-line">
            <text class="real-name">{{ user?.realName || '同学' }}</text>
          </view>
          <view class="sub-line">
            <text v-if="user?.username">{{ user.username }}</text>
            <text v-if="user?.username && user?.deptClass" class="dot">·</text>
            <text v-if="user?.deptClass">{{ user.deptClass }}</text>
          </view>
        </view>
      </view>
      <view class="profile-actions">
        <text class="action-link" @tap="openProfileModal">修改资料</text>
        <text class="action-link logout" @tap="handleLogout">退出</text>
      </view>
    </view>

    <!-- Navigation Tabs -->
    <view class="nav-tabs">
      <view class="nav-tab active">
        <text>我的评估活动</text>
      </view>
      <view class="nav-tab" @tap="goRecordList()">
        <text>提交记录</text>
      </view>
    </view>

    <!-- 403 Banner -->
    <view v-if="forbiddenMessage" class="alert-banner">
      <view class="alert-title">服务端访问受限（403）</view>
      <view class="alert-desc">{{ forbiddenMessage }}</view>
      <view class="alert-action" @tap="handleLogout">切换学生账号</view>
    </view>

    <!-- Loading -->
    <view v-if="loading && activities.length === 0" class="loading-card">
      <text class="loading-text">正在加载评估活动...</text>
    </view>

    <!-- Activities List -->
    <view v-else-if="activities.length > 0" class="activity-list">
      <view
        v-for="item in activities"
        :key="getActivityId(item)"
        class="activity-item"
      >
        <view class="item-top">
          <text class="item-title">{{ item.title || item.activityTitle }}</text>
          <text class="item-status" :class="getStatusClass(item)">
            {{ getStatusLabel(item) }}
          </text>
        </view>

        <view class="item-meta">
          <text class="version-tag">v{{ item.versionNum || 1 }}</text>
          <text class="questionnaire-title">{{ item.questionnaireTitle || '评估问卷快照' }}</text>
        </view>

        <view class="item-time">
          开放时间：{{ item.startTime || '--' }} ~ {{ item.endTime || '--' }}
        </view>

        <view class="item-actions">
          <button
            v-if="item.canSubmit === true"
            class="btn-main"
            @tap="goFill(item)"
          >
            开始填写
          </button>

          <view v-else-if="item.submitted === true" class="btn-group">
            <button
              class="btn-main"
              @tap="goReport(item.reportId, item.sheetId)"
            >
              查看报告
            </button>
            <button
              class="btn-plain"
              @tap="goRecordList(item.sheetId)"
            >
              作答记录
            </button>
          </view>

          <button v-else class="btn-disabled" disabled>
            {{ getStatusLabel(item) }}
          </button>
        </view>
      </view>
    </view>

    <!-- Empty State -->
    <view v-else-if="!forbiddenMessage" class="empty-card">
      <view class="empty-title">当前暂无分配给您的评估活动</view>
      <view class="empty-desc">
        系统仅向活动目标参与名单内的学生展示对应任务。若您已完成历史问卷，可前往「提交记录」查看。
      </view>
      <button class="empty-btn" @tap="loadData(true)">重新检查活动</button>
    </view>

    <!-- Boundary Test Panel -->
    <view class="boundary-card">
      <view class="boundary-header" @tap="showBoundaryPanel = !showBoundaryPanel">
        <text class="boundary-title">越权访问自测（实训演示用）</text>
        <text class="boundary-toggle">{{ showBoundaryPanel ? '收起' : '展开' }}</text>
      </view>

      <view v-if="showBoundaryPanel" class="boundary-body">
        <view class="boundary-tip">
          用于答辩演示：如名单外学生（20260004）尝试访问活动 #1，或读取他人报告 #1/#2，观察服务端 HTTP 403 拦截。
        </view>

        <view class="boundary-row">
          <text class="boundary-label">活动 ID</text>
          <input
            v-model="boundaryForm.activityId"
            class="boundary-input"
            type="number"
            placeholder="例如 1"
          />
          <button class="boundary-btn" @tap="testUnauthorizedActivity">测试</button>
        </view>

        <view class="boundary-row">
          <text class="boundary-label">报告 ID</text>
          <input
            v-model="boundaryForm.reportId"
            class="boundary-input"
            type="number"
            placeholder="例如 1 或 2"
          />
          <button class="boundary-btn" @tap="testUnauthorizedReport">测试</button>
        </view>
      </view>
    </view>

    <view class="page-footer">
      教学模拟评估数据 · 严格执行一人一卷与数据隔离
    </view>

    <!-- Edit Profile Modal -->
    <view v-if="showProfileModal" class="modal-mask" @tap="showProfileModal = false">
      <view class="modal-sheet" @tap.stop>
        <view class="modal-header">
          <text class="modal-title">修改个人资料</text>
          <text class="modal-close" @tap="showProfileModal = false">关闭</text>
        </view>

        <view class="modal-body">
          <view class="form-item">
            <text class="form-label">学号</text>
            <input
              :value="user?.username || ''"
              class="form-input readonly"
              disabled
            />
          </view>

          <view class="form-item">
            <text class="form-label">姓名</text>
            <input
              v-model="profileForm.realName"
              class="form-input"
              placeholder="请输入真实姓名"
              maxlength="32"
            />
          </view>

          <view class="form-item">
            <text class="form-label">院系 / 班级</text>
            <input
              v-model="profileForm.deptClass"
              class="form-input"
              placeholder="例如：软件2542班"
              maxlength="64"
            />
          </view>

          <view class="form-item">
            <text class="form-label">联系电话</text>
            <input
              v-model="profileForm.phone"
              class="form-input"
              placeholder="请输入手机号码"
              maxlength="20"
            />
          </view>
        </view>

        <view class="modal-footer">
          <button class="modal-cancel" @tap="showProfileModal = false">取消</button>
          <button
            class="modal-save"
            :loading="savingProfile"
            :disabled="savingProfile"
            @tap="saveProfile"
          >
            保存
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.activity-page {
  min-height: 100vh;
  padding: 24rpx 30rpx 64rpx;
  background: #F5F6F8;
}

.profile-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 26rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
}

.profile-left {
  display: flex;
  align-items: center;
  min-width: 0;
}

.avatar {
  flex-shrink: 0;
  width: 76rpx;
  height: 76rpx;
  margin-right: 20rpx;
  border-radius: 50%;
  background: #EFF4FC;
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-text {
  color: #2055A4;
  font-size: 32rpx;
  font-weight: 600;
}

.profile-meta {
  flex: 1;
  min-width: 0;
}

.name-line {
  display: flex;
  align-items: center;
}

.real-name {
  color: #1A1F2C;
  font-size: 32rpx;
  font-weight: 700;
}

.sub-line {
  margin-top: 8rpx;
  color: #525B6B;
  font-size: 24rpx;
}

.dot {
  margin: 0 12rpx;
  color: #8A93A2;
}

.profile-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10rpx;
  flex-shrink: 0;
}

.action-link {
  color: #2055A4;
  font-size: 24rpx;
}

.action-link.logout {
  color: #C0392B;
}

.nav-tabs {
  display: flex;
  margin-top: 24rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  overflow: hidden;
}

.nav-tab {
  flex: 1;
  padding: 26rpx 0;
  text-align: center;
  color: #525B6B;
  font-size: 28rpx;
  position: relative;
}

.nav-tab.active {
  color: #2055A4;
  font-weight: 700;
  background: #EFF4FC;
}

.alert-banner {
  margin-top: 24rpx;
  padding: 26rpx;
  border-radius: 12rpx;
  background: #FDF2F2;
  border: 1rpx solid #F5C6C6;
}

.alert-title {
  color: #C0392B;
  font-size: 28rpx;
  font-weight: 700;
}

.alert-desc {
  margin-top: 10rpx;
  color: #525B6B;
  font-size: 24rpx;
  line-height: 1.55;
}

.alert-action {
  display: inline-block;
  margin-top: 18rpx;
  padding: 10rpx 24rpx;
  border-radius: 8rpx;
  background: #C0392B;
  color: #FFFFFF;
  font-size: 24rpx;
}

.loading-card {
  margin-top: 24rpx;
  padding: 80rpx 32rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  text-align: center;
}

.loading-text {
  color: #525B6B;
  font-size: 26rpx;
}

.activity-list {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
  margin-top: 24rpx;
}

.activity-item {
  padding: 28rpx 26rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
}

.item-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16rpx;
}

.item-title {
  flex: 1;
  color: #1A1F2C;
  font-size: 30rpx;
  font-weight: 700;
  line-height: 1.45;
}

.item-status {
  flex-shrink: 0;
  font-size: 24rpx;
  font-weight: 600;
}

.status-open {
  color: #1E7E4E;
}

.status-submitted {
  color: #2055A4;
}

.status-upcoming {
  color: #B45309;
}

.status-closed {
  color: #8A93A2;
}

.item-meta {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 18rpx;
}

.version-tag {
  padding: 4rpx 12rpx;
  border-radius: 6rpx;
  background: #F5F6F8;
  color: #525B6B;
  font-size: 22rpx;
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

.item-time {
  margin-top: 14rpx;
  color: #8A93A2;
  font-size: 23rpx;
  font-variant-numeric: tabular-nums;
}

.item-actions {
  margin-top: 24rpx;
}

.btn-main {
  width: 100%;
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 10rpx;
  background: #2055A4;
  color: #FFFFFF;
  font-size: 28rpx;
  font-weight: 600;
  text-align: center;
}

.btn-main:active {
  background: #184282;
}

.btn-group {
  display: flex;
  gap: 18rpx;
}

.btn-group .btn-main {
  flex: 1.2;
}

.btn-plain {
  flex: 1;
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 10rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
  color: #525B6B;
  font-size: 28rpx;
  text-align: center;
}

.btn-disabled {
  width: 100%;
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 10rpx;
  background: #F5F6F8;
  color: #8A93A2;
  font-size: 28rpx;
  text-align: center;
}

.empty-card {
  margin-top: 24rpx;
  padding: 72rpx 40rpx;
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
  height: 76rpx;
  line-height: 76rpx;
  border-radius: 10rpx;
  background: #EFF4FC;
  color: #2055A4;
  font-size: 26rpx;
  font-weight: 600;
}

.boundary-card {
  margin-top: 32rpx;
  border-radius: 12rpx;
  background: #FFFFFF;
  border: 1rpx dashed #E8EAEE;
  overflow: hidden;
}

.boundary-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 26rpx;
}

.boundary-title {
  color: #525B6B;
  font-size: 24rpx;
}

.boundary-toggle {
  color: #2055A4;
  font-size: 24rpx;
}

.boundary-body {
  padding: 0 26rpx 26rpx;
  border-top: 1rpx solid #F5F6F8;
}

.boundary-tip {
  margin-top: 18rpx;
  color: #8A93A2;
  font-size: 22rpx;
  line-height: 1.55;
}

.boundary-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 18rpx;
}

.boundary-label {
  width: 96rpx;
  color: #525B6B;
  font-size: 24rpx;
}

.boundary-input {
  flex: 1;
  height: 68rpx;
  padding: 0 20rpx;
  border-radius: 8rpx;
  background: #FAFBFC;
  border: 1rpx solid #E8EAEE;
  font-size: 25rpx;
}

.boundary-btn {
  padding: 0 24rpx;
  height: 68rpx;
  line-height: 68rpx;
  border-radius: 8rpx;
  background: #525B6B;
  color: #FFFFFF;
  font-size: 23rpx;
}

.page-footer {
  margin-top: 40rpx;
  color: #8A93A2;
  font-size: 22rpx;
  text-align: center;
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

.modal-sheet {
  width: 100%;
  padding: 32rpx 34rpx calc(32rpx + env(safe-area-inset-bottom));
  border-radius: 24rpx 24rpx 0 0;
  background: #FFFFFF;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 28rpx;
}

.modal-title {
  color: #1A1F2C;
  font-size: 32rpx;
  font-weight: 700;
}

.modal-close {
  color: #8A93A2;
  font-size: 26rpx;
  padding: 8rpx;
}

.form-item {
  margin-bottom: 24rpx;
}

.form-label {
  display: block;
  margin-bottom: 10rpx;
  color: #525B6B;
  font-size: 25rpx;
}

.form-input {
  width: 100%;
  height: 86rpx;
  padding: 0 24rpx;
  border-radius: 10rpx;
  background: #FAFBFC;
  border: 1rpx solid #E8EAEE;
  font-size: 28rpx;
  color: #1A1F2C;
}

.form-input.readonly {
  background: #F5F6F8;
  color: #8A93A2;
}

.modal-footer {
  display: flex;
  gap: 20rpx;
  margin-top: 32rpx;
}

.modal-cancel {
  flex: 1;
  height: 86rpx;
  line-height: 86rpx;
  border-radius: 10rpx;
  background: #F5F6F8;
  color: #525B6B;
  font-size: 28rpx;
}

.modal-save {
  flex: 1.5;
  height: 86rpx;
  line-height: 86rpx;
  border-radius: 10rpx;
  background: #2055A4;
  color: #FFFFFF;
  font-size: 28rpx;
  font-weight: 600;
}

.modal-save:active {
  background: #184282;
}
</style>
