import { request } from './request'

export function loginApi(payload) {
  return request({
    url: '/auth/login',
    method: 'POST',
    data: payload,
    skipAuthRedirect: true
  })
}

export function getMeApi(options = {}) {
  return request({
    url: '/auth/me',
    method: 'GET',
    ...options
  })
}

export function updateProfileApi(payload) {
  return request({
    url: '/auth/profile',
    method: 'PUT',
    data: payload
  })
}

export function getMyActivitiesApi(options = {}) {
  return request({
    url: '/h5/activities',
    method: 'GET',
    ...options
  })
}

export function getActivityDetailApi(activityId, options = {}) {
  return request({
    url: `/h5/activities/${encodeURIComponent(activityId)}/detail`,
    method: 'GET',
    ...options
  })
}

export function submitActivityAnswersApi(activityId, payload, options = {}) {
  return request({
    url: `/h5/activities/${encodeURIComponent(activityId)}/submit`,
    method: 'POST',
    data: payload,
    ...options
  })
}

export function getMyRecordsApi(options = {}) {
  return request({
    url: '/h5/records',
    method: 'GET',
    ...options
  })
}

export function getMyRecordDetailApi(sheetId, options = {}) {
  return request({
    url: `/h5/records/${encodeURIComponent(sheetId)}`,
    method: 'GET',
    ...options
  })
}

export function getMyReportApi(reportId, options = {}) {
  return request({
    url: `/h5/reports/${encodeURIComponent(reportId)}`,
    method: 'GET',
    ...options
  })
}

export function retryMyReportApi(reportId, options = {}) {
  return request({
    url: `/h5/reports/${encodeURIComponent(reportId)}/retry`,
    method: 'POST',
    ...options
  })
}
