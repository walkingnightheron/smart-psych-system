<script setup>
import { reactive } from 'vue'

const questions = [
  {
    id: 1,
    title: '示例题目 1：最近两周，我感到情绪低落、沮丧或绝望。',
    options: [
      { label: '从不', value: 0 },
      { label: '有时', value: 1 },
      { label: '经常', value: 2 },
      { label: '总是', value: 3 }
    ]
  },
  {
    id: 2,
    title: '示例题目 2：最近两周，我对平时喜欢的事情失去兴趣。',
    options: [
      { label: '从不', value: 0 },
      { label: '有时', value: 1 },
      { label: '经常', value: 2 },
      { label: '总是', value: 3 }
    ]
  },
  {
    id: 3,
    title: '示例题目 3：最近两周，我入睡困难或睡眠质量不佳。',
    options: [
      { label: '从不', value: 0 },
      { label: '有时', value: 1 },
      { label: '经常', value: 2 },
      { label: '总是', value: 3 }
    ]
  }
]

const answers = reactive({})

function choose(questionId, value) {
  answers[questionId] = Number(value)
}

function submit() {
  const missing = questions.some((question) => answers[question.id] === undefined)
  if (missing) {
    uni.showToast({ title: '请完成全部题目', icon: 'none' })
    return
  }
  uni.showToast({ title: '提交接口待接入（REQ06）', icon: 'none' })
}
</script>

<template>
  <view class="page">
    <view class="notice">教学模拟问卷，仅用于功能验证，不作医学诊断</view>

    <view v-for="(question, index) in questions" :key="question.id" class="question-card">
      <view class="question-title">{{ index + 1 }}. {{ question.title }}</view>
      <radio-group @change="(event) => choose(question.id, event.detail.value)">
        <label v-for="option in question.options" :key="option.value" class="option">
          <radio
            :value="String(option.value)"
            :checked="answers[question.id] === option.value"
            color="#409eff"
          />
          <text class="option-label">{{ option.label }}</text>
        </label>
      </radio-group>
    </view>

    <button class="submit-btn" @tap="submit">提交答卷</button>
    <view class="footer-tip">骨架阶段：答卷提交与校验待接入（REQ06）</view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 32rpx;
}

.notice {
  padding: 20rpx 24rpx;
  margin-bottom: 24rpx;
  border-radius: 16rpx;
  background: #fdf6ec;
  color: #e6a23c;
  font-size: 24rpx;
}

.question-card {
  padding: 32rpx;
  margin-bottom: 24rpx;
  border-radius: 24rpx;
  background: #fff;
  box-shadow: 0 8rpx 24rpx rgba(0, 0, 0, 0.04);
}

.question-title {
  margin-bottom: 24rpx;
  color: #303133;
  font-size: 30rpx;
  font-weight: 600;
  line-height: 1.6;
}

.option {
  display: flex;
  align-items: center;
  padding: 20rpx 0;
  border-top: 1px solid #f2f3f5;
}

.option-label {
  margin-left: 16rpx;
  color: #606266;
  font-size: 28rpx;
}

.submit-btn {
  margin-top: 16rpx;
  border-radius: 16rpx;
  background: #409eff;
  color: #fff;
  font-size: 32rpx;
  line-height: 96rpx;
}

.footer-tip {
  margin-top: 40rpx;
  color: #c0c4cc;
  font-size: 24rpx;
  text-align: center;
}
</style>
