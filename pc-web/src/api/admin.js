import request from './request'

// ==================== 认证与个人资料 (REQ01) ====================
export function loginApi(payload) {
  return request.post('/auth/login', payload)
}

export function getMeApi(config = {}) {
  return request.get('/auth/me', config)
}

export function updateProfileApi(payload) {
  return request.put('/auth/profile', payload)
}

// ==================== 工作台与缓存观测 (REQ09) ====================
export function getDashboardSummaryApi() {
  return request.get('/admin/dashboard/summary')
}

export function getCacheMetricsApi() {
  return request.get('/admin/cache/metrics')
}

export function evictCacheApi(params) {
  return request.delete('/admin/cache/evict', { params })
}

// ==================== 用户候选列表 ====================
export function getUsersApi(role) {
  return request.get('/admin/users', {
    params: role ? { role } : undefined
  })
}

// ==================== 问卷草稿与不可变版本 (REQ02 & REQ03) ====================
export function getQuestionnairesApi() {
  return request.get('/admin/questionnaires')
}

export function getQuestionnaireDetailApi(id) {
  return request.get(`/admin/questionnaires/${id}`)
}

export function createQuestionnaireApi(payload) {
  return request.post('/admin/questionnaires', payload)
}

export function updateQuestionnaireApi(id, payload) {
  return request.put(`/admin/questionnaires/${id}`, payload)
}

export function publishQuestionnaireApi(id, payload = {}) {
  return request.post(`/admin/questionnaires/${id}/publish`, payload)
}

export function getQuestionnaireVersionsByQidApi(id) {
  return request.get(`/admin/questionnaires/${id}/versions`)
}

export function getAllPublishedVersionsApi() {
  return request.get('/admin/questionnaire-versions')
}

export function getQuestionnaireVersionSnapshotApi(versionId) {
  return request.get(`/admin/questionnaire-versions/${versionId}`)
}

// ==================== 评估活动管理 (REQ04) ====================
export function getActivitiesApi() {
  return request.get('/admin/activities')
}

export function getActivityDetailApi(id) {
  return request.get(`/admin/activities/${id}`)
}

export function createActivityApi(payload) {
  return request.post('/admin/activities', payload)
}

export function updateActivityApi(id, payload) {
  return request.put(`/admin/activities/${id}`, payload)
}

export function publishActivityApi(id) {
  return request.post(`/admin/activities/${id}/publish`)
}

export function closeActivityApi(id) {
  return request.post(`/admin/activities/${id}/close`)
}

// ==================== 结果统计与报告管理 (REQ08 & REQ10) ====================
export function getActivityStatisticsApi(id) {
  return request.get(`/admin/activities/${id}/statistics`)
}

export function getAdminReportDetailApi(reportId) {
  return request.get(`/admin/reports/${reportId}`)
}

export function retryAdminReportApi(reportId, reset = false, config = {}) {
  return request.post(
    `/admin/reports/${reportId}/retry`,
    {},
    {
      ...config,
      params: reset ? { reset: true } : undefined
    }
  )
}
