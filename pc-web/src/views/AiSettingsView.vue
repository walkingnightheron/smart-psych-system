<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getLlmConfigApi,
  saveLlmConfigApi,
  testLlmConfigApi,
  listLlmProvidersApi,
  createLlmProviderApi,
  updateLlmProviderApi,
  deleteLlmProviderApi,
  fetchLlmProviderModelsApi
} from '@/api/admin'

const PROVIDER_PRESETS = [
  {
    value: 'dashscope',
    label: '阿里百炼兼容',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1'
  },
  { value: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1' },
  { value: 'moonshot', label: 'Moonshot', baseUrl: 'https://api.moonshot.cn/v1' },
  { value: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1' },
  { value: 'custom', label: '自定义', baseUrl: '' }
]

const providers = ref([])
const providerLoading = ref(false)
const providerDialogVisible = ref(false)
const providerSaving = ref(false)
const modelLoadingMap = ref({})
const modelOptionsMap = ref({})

const providerFormRef = ref(null)
const providerForm = reactive({
  id: null,
  name: '',
  baseUrlPreset: 'custom',
  baseUrl: '',
  apiKey: '',
  enabled: true
})

const providerRules = {
  name: [{ required: true, message: '请输入提供商名称', trigger: 'blur' }],
  baseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }]
}

const providerDialogTitle = computed(() =>
  providerForm.id ? '编辑 LLM 提供商' : '新增 LLM 提供商'
)

const routeConfig = reactive({
  reportInsightEnabled: false,
  reportProviderId: null,
  reportModel: '',
  reportTemperature: 0.7,
  reportMaxTokens: 800,
  cohortInsightEnabled: false,
  cohortProviderId: null,
  cohortModel: '',
  cohortTemperature: 0.7,
  cohortMaxTokens: 800,
  fallbackEnabled: false,
  reportFallbackProviderId: null,
  reportFallbackModel: '',
  cohortFallbackProviderId: null,
  cohortFallbackModel: '',
  timeoutSeconds: 30
})
const configLoading = ref(false)
const configSaving = ref(false)
const configTesting = ref(false)
const configUpdatedAt = ref('')

const enabledProviders = computed(() => providers.value.filter((p) => p.enabled))

function findPresetByBaseUrl(baseUrl) {
  if (!baseUrl) return null
  return PROVIDER_PRESETS.find(
    (p) => p.baseUrl && baseUrl.toString().toLowerCase().startsWith(p.baseUrl.toLowerCase())
  )
}

function findPresetByValue(value) {
  return PROVIDER_PRESETS.find((p) => p.value === value)
}

function formatApiKey(row) {
  if (!row.hasApiKey) return '未配置'
  return `已配置（尾号 ${row.apiKeyTail || '****'}）`
}

function resetProviderForm() {
  providerForm.id = null
  providerForm.name = ''
  providerForm.baseUrlPreset = 'custom'
  providerForm.baseUrl = ''
  providerForm.apiKey = ''
  providerForm.enabled = true
}

function openCreateProvider() {
  resetProviderForm()
  providerDialogVisible.value = true
  nextTick(() => providerFormRef.value?.clearValidate())
}

function openEditProvider(row) {
  const preset = findPresetByBaseUrl(row.baseUrl)
  providerForm.id = row.id
  providerForm.name = row.name ?? ''
  providerForm.baseUrlPreset = preset ? preset.value : 'custom'
  providerForm.baseUrl = row.baseUrl ?? ''
  providerForm.apiKey = ''
  providerForm.enabled = Boolean(row.enabled)
  providerDialogVisible.value = true
  nextTick(() => providerFormRef.value?.clearValidate())
}

function handleProviderPresetChange(val) {
  const preset = findPresetByValue(val)
  if (preset && preset.baseUrl) {
    providerForm.baseUrl = preset.baseUrl
  }
  if (preset && preset.value !== 'custom') {
    providerForm.name = preset.label
  }
}

async function saveProvider() {
  if (!providerFormRef.value) return
  const valid = await providerFormRef.value.validate().catch(() => false)
  if (!valid) return

  providerSaving.value = true
  try {
    const payload = {
      name: providerForm.name.trim(),
      baseUrl: providerForm.baseUrl.trim(),
      apiKey: providerForm.apiKey,
      enabled: providerForm.enabled
    }
    if (providerForm.id) {
      await updateLlmProviderApi(providerForm.id, payload)
      ElMessage.success('提供商已更新')
    } else {
      await createLlmProviderApi(payload)
      ElMessage.success('提供商已创建')
    }
    providerDialogVisible.value = false
    await loadProviders()
    await loadConfig()
  } finally {
    providerSaving.value = false
  }
}

async function handleDeleteProvider(row) {
  try {
    await ElMessageBox.confirm(`确定删除提供商「${row.name}」吗？若该提供商正被功能路由引用，删除将被拒绝。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteLlmProviderApi(row.id)
    ElMessage.success('提供商已删除')
    await loadProviders()
    await loadConfig()
  } catch (err) {
    const msg = err?.response?.data?.message || err?.message || '删除失败'
    ElMessage.error(msg)
  }
}

async function handleFetchProviderModels(row) {
  modelLoadingMap.value[row.id] = true
  try {
    const res = await fetchLlmProviderModelsApi(row.id, { silentError: true })
    const data = res?.data ?? res ?? {}
    if (data.ok && Array.isArray(data.models)) {
      ElMessage.success(`「${row.name}」拉取到 ${data.models.length} 个模型`)
      modelOptionsMap.value[row.id] = data.models
    } else {
      ElMessage.error(data.error || '拉取模型列表失败')
    }
  } catch (err) {
    const msg = err?.response?.data?.message || err?.message || '拉取模型列表失败'
    ElMessage.error(msg)
  } finally {
    modelLoadingMap.value[row.id] = false
  }
}

async function loadProviders() {
  providerLoading.value = true
  try {
    const res = await listLlmProvidersApi({ silentError: true })
    const list = res?.data ?? res ?? []
    providers.value = Array.isArray(list) ? list : []
  } catch (err) {
    providers.value = []
    ElMessage.error(err?.response?.data?.message || '加载提供商列表失败')
  } finally {
    providerLoading.value = false
  }
}

function applyConfig(vo) {
  if (!vo) return
  routeConfig.reportInsightEnabled = Boolean(vo.reportInsightEnabled)
  routeConfig.reportProviderId = vo.reportProviderId ?? null
  routeConfig.reportModel = vo.reportModel ?? ''
  routeConfig.reportTemperature = Number(vo.reportTemperature ?? 0.7)
  routeConfig.reportMaxTokens = Number(vo.reportMaxTokens ?? 800)
  routeConfig.cohortInsightEnabled = Boolean(vo.cohortInsightEnabled)
  routeConfig.cohortProviderId = vo.cohortProviderId ?? null
  routeConfig.cohortModel = vo.cohortModel ?? ''
  routeConfig.cohortTemperature = Number(vo.cohortTemperature ?? 0.7)
  routeConfig.cohortMaxTokens = Number(vo.cohortMaxTokens ?? 800)
  routeConfig.fallbackEnabled = Boolean(vo.fallbackEnabled)
  routeConfig.reportFallbackProviderId = vo.reportFallbackProviderId ?? null
  routeConfig.reportFallbackModel = vo.reportFallbackModel ?? ''
  routeConfig.cohortFallbackProviderId = vo.cohortFallbackProviderId ?? null
  routeConfig.cohortFallbackModel = vo.cohortFallbackModel ?? ''
  routeConfig.timeoutSeconds = Number(vo.timeoutSeconds ?? 30)
  configUpdatedAt.value = vo.updatedAt ?? ''
}

async function loadConfig() {
  configLoading.value = true
  try {
    const res = await getLlmConfigApi({ silentError: true })
    applyConfig(res?.data ?? res ?? null)
  } catch (err) {
    ElMessage.error(err?.response?.data?.message || '加载功能路由配置失败')
  } finally {
    configLoading.value = false
  }
}

async function fetchModelsForSelect(providerId) {
  if (!providerId) return
  if (modelOptionsMap.value[providerId]) return
  modelLoadingMap.value[providerId] = true
  try {
    const res = await fetchLlmProviderModelsApi(providerId, { silentError: true })
    const data = res?.data ?? res ?? {}
    if (data.ok && Array.isArray(data.models)) {
      modelOptionsMap.value[providerId] = data.models
    }
  } catch {
    // 失败则允许手输兜底
  } finally {
    modelLoadingMap.value[providerId] = false
  }
}

async function handleReportProviderChange() {
  routeConfig.reportModel = ''
  if (routeConfig.reportProviderId) {
    await fetchModelsForSelect(routeConfig.reportProviderId)
  }
}

async function handleCohortProviderChange() {
  routeConfig.cohortModel = ''
  if (routeConfig.cohortProviderId) {
    await fetchModelsForSelect(routeConfig.cohortProviderId)
  }
}

async function handleReportFallbackProviderChange() {
  routeConfig.reportFallbackModel = ''
  if (routeConfig.reportFallbackProviderId) {
    await fetchModelsForSelect(routeConfig.reportFallbackProviderId)
  }
}

async function handleCohortFallbackProviderChange() {
  routeConfig.cohortFallbackModel = ''
  if (routeConfig.cohortFallbackProviderId) {
    await fetchModelsForSelect(routeConfig.cohortFallbackProviderId)
  }
}

// 主备完全相同（提供商 + 模型）时提示避免：主路由故障时备用大概率同样不可用
const reportFallbackSameAsPrimary = computed(() => {
  if (!routeConfig.fallbackEnabled || routeConfig.reportFallbackProviderId == null) return false
  const fallbackModel = routeConfig.reportFallbackModel?.trim() || ''
  return (
    fallbackModel !== '' &&
    routeConfig.reportFallbackProviderId === routeConfig.reportProviderId &&
    fallbackModel === (routeConfig.reportModel?.trim() || '')
  )
})

const cohortFallbackSameAsPrimary = computed(() => {
  if (!routeConfig.fallbackEnabled || routeConfig.cohortFallbackProviderId == null) return false
  const fallbackModel = routeConfig.cohortFallbackModel?.trim() || ''
  return (
    fallbackModel !== '' &&
    routeConfig.cohortFallbackProviderId === routeConfig.cohortProviderId &&
    fallbackModel === (routeConfig.cohortModel?.trim() || '')
  )
})

function collectConfigPayload() {
  return {
    reportInsightEnabled: routeConfig.reportInsightEnabled,
    reportProviderId: routeConfig.reportProviderId,
    reportModel: routeConfig.reportModel?.trim() || '',
    reportTemperature: routeConfig.reportTemperature,
    reportMaxTokens: routeConfig.reportMaxTokens,
    cohortInsightEnabled: routeConfig.cohortInsightEnabled,
    cohortProviderId: routeConfig.cohortProviderId,
    cohortModel: routeConfig.cohortModel?.trim() || '',
    cohortTemperature: routeConfig.cohortTemperature,
    cohortMaxTokens: routeConfig.cohortMaxTokens,
    fallbackEnabled: routeConfig.fallbackEnabled,
    reportFallbackProviderId: routeConfig.reportFallbackProviderId,
    reportFallbackModel: routeConfig.reportFallbackModel?.trim() || '',
    cohortFallbackProviderId: routeConfig.cohortFallbackProviderId,
    cohortFallbackModel: routeConfig.cohortFallbackModel?.trim() || '',
    timeoutSeconds: routeConfig.timeoutSeconds
  }
}

async function handleSaveConfig() {
  // 与服务端校验对齐：启用备用路由时，已绑定提供商的分组必须填写模型；未绑定的分组允许留空
  if (routeConfig.fallbackEnabled) {
    if (routeConfig.reportFallbackProviderId && !routeConfig.reportFallbackModel?.trim()) {
      ElMessage.warning('已为报告解读选择备用提供商，请填写备用模型后再保存')
      return
    }
    if (routeConfig.cohortFallbackProviderId && !routeConfig.cohortFallbackModel?.trim()) {
      ElMessage.warning('已为群体洞察选择备用提供商，请填写备用模型后再保存')
      return
    }
  }
  const sameTips = []
  if (reportFallbackSameAsPrimary.value) sameTips.push('报告解读')
  if (cohortFallbackSameAsPrimary.value) sameTips.push('群体洞察')
  if (sameTips.length > 0) {
    ElMessage.warning(`${sameTips.join('、')}的备用路由与主路由完全相同，降级意义有限，建议更换备用组合`)
  }
  configSaving.value = true
  try {
    const res = await saveLlmConfigApi(collectConfigPayload())
    applyConfig(res?.data ?? res ?? null)
    ElMessage.success('功能路由已保存')
  } finally {
    configSaving.value = false
  }
}

async function handleTestRoute(target) {
  const isReport = target === 'report' || target === 'reportFallback'
  const isFallback = target === 'reportFallback' || target === 'cohortFallback'
  const label =
    (isReport ? '报告个性化解读' : '活动群体洞察') + (isFallback ? ' · 备用路由' : '')
  const providerId = isFallback
    ? isReport
      ? routeConfig.reportFallbackProviderId
      : routeConfig.cohortFallbackProviderId
    : isReport
      ? routeConfig.reportProviderId
      : routeConfig.cohortProviderId
  const model = isFallback
    ? isReport
      ? routeConfig.reportFallbackModel
      : routeConfig.cohortFallbackModel
    : isReport
      ? routeConfig.reportModel
      : routeConfig.cohortModel
  if (!providerId || !model) {
    ElMessage.warning(`请先在「${label}」中选择提供商与模型`)
    return
  }
  configTesting.value = true
  try {
    const res = await testLlmConfigApi({ providerId, model }, { silentError: true })
    const data = res?.data ?? res ?? { ok: false, error: '测试接口无响应数据' }
    if (data.ok) {
      ElMessageBox.alert(
        `模型：${data.model || '-'}；耗时：${data.latencyMs ?? '-'} ms；回复：${data.reply || '（无内容）'}`,
        `${label} · 连接正常`,
        { type: 'success', confirmButtonText: '知道了' }
      )
    } else {
      ElMessageBox.alert(data.error || '未知错误', `${label} · 连接失败`, {
        type: 'error',
        confirmButtonText: '知道了'
      })
    }
  } catch (err) {
    const msg = err?.response?.data?.message || err?.message || '测试请求失败'
    ElMessageBox.alert(msg, `${label} · 连接失败`, { type: 'error', confirmButtonText: '知道了' })
  } finally {
    configTesting.value = false
  }
}

onMounted(() => {
  loadProviders()
  loadConfig()
})
</script>

<template>
  <div class="page-container">
    <div class="page-toolbar">
      <div class="toolbar-left">
        <h2 class="toolbar-title">智能分析配置</h2>
        <span class="toolbar-desc">管理多个 LLM 提供商，并为报告解读、群体洞察分配功能路由</span>
      </div>
      <div class="toolbar-right">
        <span v-if="configUpdatedAt" class="updated-at tabular-nums">
          最近更新 {{ configUpdatedAt }}
        </span>
      </div>
    </div>

    <!-- 1. 提供商管理 -->
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title-wrap">
          <h3 class="panel-title">LLM 提供商管理</h3>
          <span class="panel-subtitle">支持配置多个上游接口，密钥仅存服务端、接口不回显完整密钥</span>
        </div>
        <el-button type="primary" size="small" @click="openCreateProvider">新增提供商</el-button>
      </div>
      <div v-loading="providerLoading" class="panel-body">
        <el-table :data="providers" border style="width: 100%">
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="baseUrl" label="Base URL" min-width="220">
            <template #default="{ row }">
              <span class="mono-code">{{ row.baseUrl || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="密钥状态" width="170">
            <template #default="{ row }">
              <span class="tabular-nums">{{ formatApiKey(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="启用状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.enabled" size="small" type="success" effect="plain">启用</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">禁用</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right" align="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEditProvider(row)">编辑</el-button>
              <el-button
                link
                type="primary"
                size="small"
                :loading="modelLoadingMap[row.id]"
                @click="handleFetchProviderModels(row)"
              >
                拉取模型
              </el-button>
              <el-button link type="danger" size="small" @click="handleDeleteProvider(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 2. 功能路由 -->
    <div v-loading="configLoading" class="panel">
      <div class="panel-header">
        <div class="panel-title-wrap">
          <h3 class="panel-title">AI 功能路由</h3>
          <span class="panel-subtitle">分别为报告解读与群体洞察指定模型、生成参数与备用降级路由</span>
        </div>
      </div>
      <div class="panel-body">
        <div class="fallback-global-bar">
          <div class="fallback-global-left">
            <span class="fallback-global-label">备用路由（自动降级）</span>
            <el-switch v-model="routeConfig.fallbackEnabled" />
          </div>
          <span class="inline-hint">
            开启后主路由调用失败时自动切换至备用提供商与模型；每组备用可留空，留空的功能不参与降级
          </span>
        </div>

        <div class="route-grid">
          <!-- 报告解读 -->
          <div class="route-card">
            <div class="route-card-title route-card-head">
              <span>报告个性化解读</span>
              <el-button link type="primary" :loading="configTesting" @click="handleTestRoute('report')">
                测试此路连接
              </el-button>
            </div>
            <el-form label-width="100px">
              <el-form-item label="启用">
                <el-switch v-model="routeConfig.reportInsightEnabled" />
              </el-form-item>
              <el-form-item label="提供商">
                <el-select
                  v-model="routeConfig.reportProviderId"
                  placeholder="请选择"
                  clearable
                  style="width: 100%"
                  @change="handleReportProviderChange"
                >
                  <el-option
                    v-for="p in enabledProviders"
                    :key="p.id"
                    :value="p.id"
                    :label="p.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="模型">
                <el-select
                  v-model="routeConfig.reportModel"
                  placeholder="选择提供商后自动拉取，也可手动输入"
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  :loading="modelLoadingMap[routeConfig.reportProviderId]"
                  style="width: 100%"
                >
                  <el-option
                    v-for="m in modelOptionsMap[routeConfig.reportProviderId] || []"
                    :key="m"
                    :value="m"
                    :label="m"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="Temperature">
                <el-input-number
                  v-model="routeConfig.reportTemperature"
                  :min="0"
                  :max="2"
                  :step="0.1"
                  :precision="2"
                  controls-position="right"
                />
                <span class="inline-hint">0 ~ 2</span>
              </el-form-item>
              <el-form-item label="Max Tokens">
                <el-input-number
                  v-model="routeConfig.reportMaxTokens"
                  :min="1"
                  :max="4096"
                  :step="100"
                  controls-position="right"
                />
                <span class="inline-hint">1 ~ 4096</span>
              </el-form-item>

              <div class="fallback-divider">
                <span>备用路由（主路由失败时自动降级）</span>
                <el-button
                  v-if="routeConfig.fallbackEnabled"
                  link
                  type="primary"
                  size="small"
                  :loading="configTesting"
                  @click="handleTestRoute('reportFallback')"
                >
                  测试备用连接
                </el-button>
              </div>
              <el-form-item label="备用提供商">
                <el-select
                  v-model="routeConfig.reportFallbackProviderId"
                  placeholder="可选，留空则该功能不降级"
                  clearable
                  :disabled="!routeConfig.fallbackEnabled"
                  style="width: 100%"
                  @change="handleReportFallbackProviderChange"
                >
                  <el-option
                    v-for="p in enabledProviders"
                    :key="p.id"
                    :value="p.id"
                    :label="p.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="备用模型">
                <el-select
                  v-model="routeConfig.reportFallbackModel"
                  placeholder="选择备用提供商后自动拉取，也可手动输入"
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  :disabled="!routeConfig.fallbackEnabled"
                  :loading="modelLoadingMap[routeConfig.reportFallbackProviderId]"
                  style="width: 100%"
                >
                  <el-option
                    v-for="m in modelOptionsMap[routeConfig.reportFallbackProviderId] || []"
                    :key="m"
                    :value="m"
                    :label="m"
                  />
                </el-select>
              </el-form-item>
              <div v-if="reportFallbackSameAsPrimary" class="fallback-warn">
                备用与主路由的提供商、模型完全相同，主路由故障时备用大概率同样失败，建议更换组合。
              </div>
            </el-form>
          </div>

          <!-- 群体洞察 -->
          <div class="route-card">
            <div class="route-card-title route-card-head">
              <span>活动群体洞察</span>
              <el-button link type="primary" :loading="configTesting" @click="handleTestRoute('cohort')">
                测试此路连接
              </el-button>
            </div>
            <el-form label-width="100px">
              <el-form-item label="启用">
                <el-switch v-model="routeConfig.cohortInsightEnabled" />
              </el-form-item>
              <el-form-item label="提供商">
                <el-select
                  v-model="routeConfig.cohortProviderId"
                  placeholder="请选择"
                  clearable
                  style="width: 100%"
                  @change="handleCohortProviderChange"
                >
                  <el-option
                    v-for="p in enabledProviders"
                    :key="p.id"
                    :value="p.id"
                    :label="p.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="模型">
                <el-select
                  v-model="routeConfig.cohortModel"
                  placeholder="选择提供商后自动拉取，也可手动输入"
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  :loading="modelLoadingMap[routeConfig.cohortProviderId]"
                  style="width: 100%"
                >
                  <el-option
                    v-for="m in modelOptionsMap[routeConfig.cohortProviderId] || []"
                    :key="m"
                    :value="m"
                    :label="m"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="Temperature">
                <el-input-number
                  v-model="routeConfig.cohortTemperature"
                  :min="0"
                  :max="2"
                  :step="0.1"
                  :precision="2"
                  controls-position="right"
                />
                <span class="inline-hint">0 ~ 2</span>
              </el-form-item>
              <el-form-item label="Max Tokens">
                <el-input-number
                  v-model="routeConfig.cohortMaxTokens"
                  :min="1"
                  :max="4096"
                  :step="100"
                  controls-position="right"
                />
                <span class="inline-hint">1 ~ 4096</span>
              </el-form-item>

              <div class="fallback-divider">
                <span>备用路由（主路由失败时自动降级）</span>
                <el-button
                  v-if="routeConfig.fallbackEnabled"
                  link
                  type="primary"
                  size="small"
                  :loading="configTesting"
                  @click="handleTestRoute('cohortFallback')"
                >
                  测试备用连接
                </el-button>
              </div>
              <el-form-item label="备用提供商">
                <el-select
                  v-model="routeConfig.cohortFallbackProviderId"
                  placeholder="可选，留空则该功能不降级"
                  clearable
                  :disabled="!routeConfig.fallbackEnabled"
                  style="width: 100%"
                  @change="handleCohortFallbackProviderChange"
                >
                  <el-option
                    v-for="p in enabledProviders"
                    :key="p.id"
                    :value="p.id"
                    :label="p.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="备用模型">
                <el-select
                  v-model="routeConfig.cohortFallbackModel"
                  placeholder="选择备用提供商后自动拉取，也可手动输入"
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  :disabled="!routeConfig.fallbackEnabled"
                  :loading="modelLoadingMap[routeConfig.cohortFallbackProviderId]"
                  style="width: 100%"
                >
                  <el-option
                    v-for="m in modelOptionsMap[routeConfig.cohortFallbackProviderId] || []"
                    :key="m"
                    :value="m"
                    :label="m"
                  />
                </el-select>
              </el-form-item>
              <div v-if="cohortFallbackSameAsPrimary" class="fallback-warn">
                备用与主路由的提供商、模型完全相同，主路由故障时备用大概率同样失败，建议更换组合。
              </div>
            </el-form>
          </div>
        </div>

        <div class="global-row">
          <el-form label-width="100px" inline>
            <el-form-item label="全局超时">
              <el-input-number
                v-model="routeConfig.timeoutSeconds"
                :min="5"
                :max="120"
                :step="5"
                controls-position="right"
              />
              <span class="inline-hint">5 ~ 120 秒</span>
            </el-form-item>
          </el-form>
        </div>

        <div class="route-actions">
          <el-button type="primary" :loading="configSaving" @click="handleSaveConfig">保存路由</el-button>
          <el-button plain :loading="configTesting" @click="handleTestRoute('report')">测试报告解读</el-button>
          <el-button plain :loading="configTesting" @click="handleTestRoute('cohort')">测试群体洞察</el-button>
        </div>

        <div class="security-note">
          安全说明：API Key 仅保存在服务端数据库，页面与接口均不回显完整密钥；删除被引用的提供商前，请先在上方路由中切换至其他提供商。
        </div>
      </div>
    </div>

    <!-- 新增/编辑提供商弹窗 -->
    <el-dialog v-model="providerDialogVisible" :title="providerDialogTitle" width="560px" destroy-on-close>
      <el-form
        ref="providerFormRef"
        :model="providerForm"
        :rules="providerRules"
        label-width="100px"
      >
        <el-form-item label="名称" prop="name">
          <el-input v-model="providerForm.name" placeholder="如：阿里百炼 / DeepSeek" maxlength="64" />
        </el-form-item>

        <el-form-item label="预设" prop="baseUrlPreset">
          <el-select v-model="providerForm.baseUrlPreset" style="width: 100%" @change="handleProviderPresetChange">
            <el-option
              v-for="p in PROVIDER_PRESETS"
              :key="p.value"
              :value="p.value"
              :label="p.label"
            />
          </el-select>
          <div class="field-hint">选择预设自动填入对应 Base URL，仍可手动修改</div>
        </el-form-item>

        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="providerForm.baseUrl" placeholder="https://your-llm-endpoint/v1" maxlength="256" />
        </el-form-item>

        <el-form-item label="API Key">
          <el-input
            v-model="providerForm.apiKey"
            type="password"
            show-password
            :placeholder="providerForm.id ? '留空保留原密钥，填写则覆盖' : '请输入 API Key'"
            maxlength="512"
            autocomplete="new-password"
          />
          <div class="field-hint">密钥仅服务端存储，不会回显</div>
        </el-form-item>

        <el-form-item label="启用">
          <el-switch v-model="providerForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="providerDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="providerSaving" @click="saveProvider">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.route-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.updated-at {
  font-size: 12px;
  color: #6B7280;
}

.route-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.route-card {
  padding: 16px;
  border: 1px solid #E5E7EB;
  border-radius: 6px;
  background-color: #FFFFFF;
}

.route-card-title {
  margin-bottom: 14px;
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}

.inline-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #6B7280;
}

.field-hint {
  width: 100%;
  font-size: 12px;
  color: #6B7280;
  line-height: 1.6;
}

.fallback-global-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 16px;
  margin-bottom: 16px;
  border: 1px solid #E5E7EB;
  border-radius: 6px;
  background-color: #F9FAFB;
}

.fallback-global-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.fallback-global-label {
  font-size: 13px;
  font-weight: 600;
  color: #111827;
}

.fallback-divider {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 14px 0 12px;
  padding-top: 12px;
  border-top: 1px solid #E5E7EB;
  font-size: 12.5px;
  font-weight: 600;
  color: #4B5563;
}

.fallback-warn {
  padding: 8px 10px;
  margin-bottom: 4px;
  border-radius: 4px;
  background-color: #FFFBEB;
  border: 1px solid #FDE68A;
  font-size: 12px;
  line-height: 1.6;
  color: #D97706;
}

.global-row {
  margin-top: 16px;
  padding: 12px 16px;
  border: 1px solid #E5E7EB;
  border-radius: 6px;
  background-color: #FFFFFF;
}

.route-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.security-note {
  margin-top: 16px;
  font-size: 12px;
  color: #6B7280;
  line-height: 1.6;
}

@media (max-width: 960px) {
  .route-grid {
    grid-template-columns: 1fr;
  }
}
</style>
