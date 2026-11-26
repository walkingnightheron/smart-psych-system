import request, { downloadBlob } from './request'

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

export function adjustPublishedSettingsApi(id, data) {
  return request.put(`/admin/activities/${id}/settings`, data)
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

// ==================== 智能分析配置与 AI 洞察 (Section 5) ====================
export function listLlmProvidersApi(config = {}) {
  return request.get('/admin/llm-providers', config)
}

export function createLlmProviderApi(data, config = {}) {
  return request.post('/admin/llm-providers', data, config)
}

export function updateLlmProviderApi(id, data, config = {}) {
  return request.put(`/admin/llm-providers/${id}`, data, config)
}

export function deleteLlmProviderApi(id, config = {}) {
  return request.delete(`/admin/llm-providers/${id}`, config)
}

export function fetchLlmProviderModelsApi(id, config = {}) {
  return request.get(`/admin/llm-providers/${id}/models`, { timeout: 30000, ...config })
}

export function getLlmConfigApi(config = {}) {
  return request.get('/admin/llm-config', config)
}

export function saveLlmConfigApi(data, config = {}) {
  return request.put('/admin/llm-config', data, config)
}

export function testLlmConfigApi(data = {}, config = {}) {
  return request.post('/admin/llm-config/test', data, { timeout: 130000, ...config })
}

export function getActivityAiInsightApi(id, config = {}) {
  return request.get(`/admin/activities/${id}/ai-insight`, { timeout: 130000, ...config })
}

export function getReportAiInterpretationApi(reportId, config = {}) {
  return request.get(`/admin/reports/${reportId}/ai-interpretation`, { timeout: 130000, ...config })
}

// ==================== 操作日志审计 ====================
export function getAuditLogsApi(params = {}, config = {}) {
  const cleanParams = {}
  for (const key of ['keyword', 'actionType', 'resourceType', 'startTime', 'endTime']) {
    const val = params?.[key]
    if (val !== undefined && val !== null && String(val).trim()) {
      cleanParams[key] = String(val).trim()
    }
  }
  cleanParams.page = Number(params?.page) > 0 ? Number(params.page) : 1
  cleanParams.pageSize = Number(params?.pageSize) > 0 ? Number(params.pageSize) : 20
  return request.get('/admin/audit-logs', { params: cleanParams, ...config })
}

// ==================== 学生名册与 XLSX 导入/导出 (Section 7) ====================
export function downloadQuestionnaireTemplateApi() {
  return downloadBlob('/admin/templates/questionnaire.xlsx', '心理测评问卷导入模板.xlsx')
}

export function downloadStudentsTemplateApi() {
  return downloadBlob('/admin/templates/students.xlsx', '学生名单导入模板.xlsx')
}

export function parseQuestionnaireXlsxApi(file) {
  let formData
  if (file instanceof FormData) {
    formData = file
  } else {
    formData = new FormData()
    formData.append('file', file)
  }
  return request.post('/admin/questionnaires/import/parse', formData)
}

export function importQuestionnaireXlsxApi(formDataOrPayload) {
  let formData = formDataOrPayload
  if (!(formDataOrPayload instanceof FormData)) {
    formData = new FormData()
    if (formDataOrPayload?.file) {
      formData.append('file', formDataOrPayload.file)
    }
    if (formDataOrPayload?.code !== undefined && formDataOrPayload?.code !== null) {
      formData.append('code', String(formDataOrPayload.code))
    }
    if (formDataOrPayload?.title !== undefined && formDataOrPayload?.title !== null) {
      formData.append('title', String(formDataOrPayload.title))
    }
    if (formDataOrPayload?.description) {
      formData.append('description', String(formDataOrPayload.description))
    }
    if (formDataOrPayload?.formula) {
      formData.append('formula', String(formDataOrPayload.formula))
    }
    if (
      formDataOrPayload?.reverseMaxPlusMin !== undefined &&
      formDataOrPayload?.reverseMaxPlusMin !== null &&
      formDataOrPayload?.reverseMaxPlusMin !== ''
    ) {
      formData.append('reverseMaxPlusMin', String(formDataOrPayload.reverseMaxPlusMin))
    }
    if (formDataOrPayload?.ruleVersion) {
      formData.append('ruleVersion', String(formDataOrPayload.ruleVersion))
    }
  }
  return request.post('/admin/questionnaires/import', formData)
}

export function getStudentsApi(params = {}) {
  const cleanParams = {}
  if (params?.keyword && String(params.keyword).trim()) {
    cleanParams.keyword = String(params.keyword).trim()
  }
  if (params?.deptClass && String(params.deptClass).trim()) {
    cleanParams.deptClass = String(params.deptClass).trim()
  }
  return request.get('/admin/students', {
    params: Object.keys(cleanParams).length > 0 ? cleanParams : undefined
  })
}

export function createStudentApi(data) {
  return request.post('/admin/students', data)
}

export function updateStudentApi(id, data) {
  return request.put(`/admin/students/${id}`, data)
}

export function updateStudentStatusApi(id, status) {
  const payload =
    status && typeof status === 'object' && 'status' in status ? status : { status }
  return request.put(`/admin/students/${id}/status`, payload)
}

export function resetStudentPasswordApi(id, password = '') {
  const payload =
    password && typeof password === 'object' && 'password' in password
      ? password
      : { password: password ?? '' }
  return request.post(`/admin/students/${id}/reset-password`, payload)
}

export function deleteStudentApi(id, config = {}) {
  return request.delete(`/admin/students/${id}`, config)
}

export function importStudentsXlsxApi(file, updateExisting = false) {
  let formData
  if (file instanceof FormData) {
    formData = file
  } else {
    formData = new FormData()
    formData.append('file', file)
    formData.append('updateExisting', String(Boolean(updateExisting)))
  }
  return request.post('/admin/users/import-students', formData)
}

export function importActivityParticipantsXlsxApi(
  activityId,
  file,
  mode = 'APPEND',
  autoCreateMissing = true
) {
  let formData
  if (file instanceof FormData) {
    formData = file
  } else {
    formData = new FormData()
    formData.append('file', file)
    formData.append('mode', mode || 'APPEND')
    formData.append('autoCreateMissing', String(Boolean(autoCreateMissing)))
  }
  return request.post(`/admin/activities/${activityId}/participants/import`, formData)
}

export function exportActivityStatisticsXlsxApi(activityId) {
  return downloadBlob(
    `/admin/activities/${activityId}/statistics/export.xlsx`,
    `评估活动统计报表_${activityId}.xlsx`
  )
}
