import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '@/layout/AdminLayout.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录' }
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
          meta: { title: '工作台' }
        },
        {
          path: 'questionnaires',
          name: 'questionnaires',
          component: () => import('@/views/QuestionnaireView.vue'),
          meta: { title: '问卷配置' }
        },
        {
          path: 'activities',
          name: 'activities',
          component: () => import('@/views/ActivityView.vue'),
          meta: { title: '活动管理' }
        },
        {
          path: 'statistics',
          name: 'statistics',
          component: () => import('@/views/StatisticsView.vue'),
          meta: { title: '结果统计' }
        }
      ]
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/dashboard'
    }
  ]
})

router.afterEach((to) => {
  document.title = `${to.meta.title ?? ''} · 智慧心理健康评估系统`
})

export default router
