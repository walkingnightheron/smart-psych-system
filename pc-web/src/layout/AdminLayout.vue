<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Odometer,
  Document,
  Calendar,
  User,
  DataAnalysis,
  Setting,
  Tickets,
  ArrowDown,
  SwitchButton,
  EditPen
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const title = computed(() => route.meta.title ?? '')
const activeMenu = computed(() => route.path)

const profileDialogVisible = ref(false)
const profileSaving = ref(false)
const profileFormRef = ref(null)
const profileForm = reactive({
  realName: '',
  deptClass: '',
  phone: ''
})

const profileRules = {
  realName: [
    { required: true, message: '请输入真实姓名', trigger: 'blur' },
    { min: 2, max: 64, message: '姓名长度在 2 到 64 个字符之间', trigger: 'blur' }
  ],
  deptClass: [
    { required: true, message: '请输入所属部门或院系', trigger: 'blur' },
    { max: 128, message: '长度不能超过 128 个字符', trigger: 'blur' }
  ],
  phone: [
    {
      pattern: /^$|^1\d{10}$|^0\d{2,3}-?\d{7,8}$/,
      message: '请输入合法的11位手机号或座机号',
      trigger: 'blur'
    }
  ]
}

function openProfileDialog() {
  profileForm.realName = userStore.profile?.realName ?? ''
  profileForm.deptClass = userStore.profile?.deptClass ?? ''
  profileForm.phone = userStore.profile?.phone ?? ''
  profileDialogVisible.value = true
}

async function submitProfile() {
  if (!profileFormRef.value) return
  const valid = await profileFormRef.value.validate().catch(() => false)
  if (!valid) return

  profileSaving.value = true
  try {
    await userStore.updateProfile({
      realName: profileForm.realName.trim(),
      deptClass: profileForm.deptClass.trim(),
      phone: profileForm.phone.trim()
    })
    ElMessage.success('个人资料已更新')
    profileDialogVisible.value = false
  } finally {
    profileSaving.value = false
  }
}

function handleUserCommand(cmd) {
  if (cmd === 'profile') {
    openProfileDialog()
  } else if (cmd === 'logout') {
    userStore.clear()
    ElMessage.success('已退出登录')
    router.replace('/login')
  }
}
</script>

<template>
  <el-container class="admin-layout">
    <el-aside width="216px" class="admin-aside">
      <div class="brand-box" @click="router.push('/dashboard')">
        <div class="brand-mark">心</div>
        <div class="brand-text">
          <div class="brand-name">心理健康评估系统</div>
          <div class="brand-sub">管理端</div>
        </div>
      </div>

      <el-menu
        router
        :default-active="activeMenu"
        class="side-menu"
      >
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>工作台概览</span>
        </el-menu-item>
        <el-menu-item index="/questionnaires">
          <el-icon><Document /></el-icon>
          <span>问卷配置与版本</span>
        </el-menu-item>
        <el-menu-item index="/activities">
          <el-icon><Calendar /></el-icon>
          <span>评估活动管理</span>
        </el-menu-item>
        <el-menu-item index="/students">
          <el-icon><User /></el-icon>
          <span>学生名册</span>
        </el-menu-item>
        <el-menu-item index="/statistics">
          <el-icon><DataAnalysis /></el-icon>
          <span>结果统计与报告</span>
        </el-menu-item>
        <el-menu-item index="/ai-settings">
          <el-icon><Setting /></el-icon>
          <span>智能分析配置</span>
        </el-menu-item>
        <el-menu-item index="/audit-logs">
          <el-icon><Tickets /></el-icon>
          <span>操作日志</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container class="main-wrapper">
      <el-header height="56px" class="admin-header">
        <div class="header-left">
          <h1 class="current-page-title">{{ title }}</h1>
        </div>

        <div class="header-right">
          <el-dropdown trigger="click" @command="handleUserCommand">
            <div class="user-trigger">
              <span class="user-name">{{ userStore.displayName }}</span>
              <span v-if="userStore.profile?.deptClass" class="user-dept">
                （{{ userStore.profile.deptClass }}）
              </span>
              <el-icon class="caret-icon"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">
                  <el-icon><EditPen /></el-icon>
                  <span>个人资料维护</span>
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">
                  <el-icon><SwitchButton /></el-icon>
                  <span>退出登录</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>

    <!-- 个人资料维护弹窗 -->
    <el-dialog
      v-model="profileDialogVisible"
      title="个人资料维护"
      width="440px"
      destroy-on-close
    >
      <el-form
        ref="profileFormRef"
        :model="profileForm"
        :rules="profileRules"
        label-width="84px"
      >
        <el-form-item label="登录工号">
          <el-input :model-value="userStore.profile?.username" disabled />
        </el-form-item>
        <el-form-item label="真实姓名" prop="realName">
          <el-input
            v-model="profileForm.realName"
            placeholder="请输入真实姓名"
            maxlength="64"
            clearable
          />
        </el-form-item>
        <el-form-item label="所属部门" prop="deptClass">
          <el-input
            v-model="profileForm.deptClass"
            placeholder="例如：心理健康教育中心 / 计算机与软件学院"
            maxlength="128"
            clearable
          />
        </el-form-item>
        <el-form-item label="联系电话" prop="phone">
          <el-input
            v-model="profileForm.phone"
            placeholder="请输入11位手机号码"
            maxlength="32"
            clearable
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="profileDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="profileSaving" @click="submitProfile">
          保存
        </el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100vh;
  overflow: hidden;
  background-color: var(--bg-canvas);
}

.admin-aside {
  display: flex;
  flex-direction: column;
  background-color: #FFFFFF;
  border-right: 1px solid #E5E7EB;
  z-index: 10;
}

.brand-box {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  cursor: pointer;
  border-bottom: 1px solid #E5E7EB;
}

.brand-mark {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background-color: #EEF3FA;
  border: 1px solid #D0DDF0;
  color: #2055A4;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.brand-name {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}

.brand-sub {
  font-size: 11px;
  color: #6B7280;
}

.side-menu {
  border-right: none;
  padding: 12px 8px;
  flex: 1;
  background-color: #FFFFFF;
}

.side-menu :deep(.el-menu-item) {
  position: relative;
  height: 40px;
  line-height: 40px;
  margin-bottom: 4px;
  padding-left: 14px !important;
  border-radius: 6px;
  font-size: 13.5px;
  font-weight: 500;
  color: #4B5563;
}

.side-menu :deep(.el-menu-item:hover) {
  background-color: #F5F6F8 !important;
  color: #111827 !important;
}

.side-menu :deep(.el-menu-item.is-active) {
  background-color: #EEF3FA !important;
  color: #2055A4 !important;
  font-weight: 600;
}

.side-menu :deep(.el-menu-item.is-active::before) {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  background-color: #2055A4;
  border-radius: 0 2px 2px 0;
}

.main-wrapper {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background-color: #FFFFFF;
  border-bottom: 1px solid #E5E7EB;
}

.header-left {
  display: flex;
  align-items: center;
}

.current-page-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #111827;
}

.header-right {
  display: flex;
  align-items: center;
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
  color: #111827;
  font-size: 13px;
}

.user-trigger:hover {
  background-color: #F5F6F8;
}

.user-name {
  font-weight: 500;
  color: #111827;
}

.user-dept {
  color: #4B5563;
}

.caret-icon {
  margin-left: 4px;
  color: #6B7280;
}

.admin-main {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  background-color: var(--bg-canvas);
}
</style>
