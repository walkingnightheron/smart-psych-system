import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import AdminLayout from '@/layout/AdminLayout.vue'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '管理员登录' }
    },
    {
      path: '/',
      component: AdminLayout,
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '工作台概览' }
        },
        {
          path: 'questionnaires',
          name: 'questionnaires',
          component: () => import('@/views/QuestionnaireView.vue'),
          meta: { title: '问卷配置与版本' }
        },
        {
          path: 'activities',
          name: 'activities',
          component: () => import('@/views/ActivityView.vue'),
          meta: { title: '评估活动管理' }
        },
        {
          path: 'students',
          name: 'students',
          component: () => import('@/views/StudentsView.vue'),
          meta: { title: '学生名册' }
        },
        {
          path: 'statistics',
          name: 'statistics',
          component: () => import('@/views/StatisticsView.vue'),
          meta: { title: '结果统计与报告' }
        }
      ]
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/dashboard'
    }
  ]
})

router.beforeEach(async (to) => {
  const userStore = useUserStore()

  if (to.path === '/login') {
    if (userStore.token && userStore.profile?.role === 'ADMIN') {
      return '/dashboard'
    }
    return true
  }

  if (!userStore.token) {
    return {
      path: '/login',
      query: to.fullPath && to.fullPath !== '/dashboard' ? { redirect: to.fullPath } : undefined
    }
  }

  if (!userStore.profile) {
    try {
      await userStore.fetchMe({ silentError: true })
    } catch {
      userStore.clear()
      return '/login'
    }
  }

  if (userStore.profile?.role !== 'ADMIN') {
    ElMessage.error('权限不足（403）：学生账号仅可访问 H5 参与端，禁止登录 PC 管理端')
    userStore.clear()
    return '/login'
  }

  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title ?? ''} · 智慧心理健康评估系统`
})

export default router
