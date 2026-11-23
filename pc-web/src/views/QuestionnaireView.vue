<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Plus,
  Delete,
  Top,
  Bottom
} from '@element-plus/icons-vue'
import {
  getQuestionnairesApi,
  getQuestionnaireDetailApi,
  createQuestionnaireApi,
  updateQuestionnaireApi,
  publishQuestionnaireApi,
  getQuestionnaireVersionsByQidApi,
  getQuestionnaireVersionSnapshotApi
} from '@/api/admin'

const loading = ref(false)
const questionnaires = ref([])

// ==================== 草稿新增/编辑抽屉 ====================
const editorVisible = ref(false)
const editorLoading = ref(false)
const editorSaving = ref(false)
const activeEditorTab = ref('questions')
const editingId = ref(null)

const draftForm = reactive({
  code: '',
  title: '',
  description: '',
  scoringRule: {
    ruleVersion: 'R-2026.1',
    formula: 'SUM',
    reverseMaxPlusMin: 5,
    dimensions: [],
    levels: []
  },
  questions: []
})

function createDefaultOptions() {
  return [
    { optCode: 'A', label: '从不', score: 1, sort: 1 },
    { optCode: 'B', label: '偶尔', score: 2, sort: 2 },
    { optCode: 'C', label: '经常', score: 3, sort: 3 },
    { optCode: 'D', label: '总是', score: 4, sort: 4 }
  ]
}

function createEmptyQuestion(no, defaultDim = 'STRESS') {
  return {
    questionNo: no,
    stem: '',
    questionType: 'SINGLE',
    dimensionCode: defaultDim,
    isReverse: 0,
    required: 1,
    options: createDefaultOptions()
  }
}

function resetDraftForm() {
  draftForm.code = ''
  draftForm.title = ''
  draftForm.description = ''
  draftForm.scoringRule = {
    ruleVersion: 'R-2026.1',
    formula: 'SUM',
    reverseMaxPlusMin: 5,
    dimensions: [
      { code: 'STRESS', name: '学习压力', formula: 'SUM' },
      { code: 'MOOD', name: '情绪状态', formula: 'SUM' },
      { code: 'SLEEP', name: '睡眠与作息', formula: 'SUM' }
    ],
    levels: [
      {
        code: 'GOOD',
        name: '状态良好',
        min: 6,
        max: 10,
        minInclusive: true,
        maxInclusive: true,
        advice: '您近期整体心理适应与作息状态良好，建议继续保持规律作息与积极社交。'
      },
      {
        code: 'MILD',
        name: '轻度压力',
        min: 11,
        max: 15,
        minInclusive: true,
        maxInclusive: true,
        advice: '您近期存在一定学习或生活压力，建议合理规划学习节奏，适当进行户外运动与倾诉。'
      }
    ]
  }
  draftForm.questions = [createEmptyQuestion(1, 'STRESS')]
}

function fillStandard6Template() {
  const suffix = String(Date.now()).slice(-4)
  if (!editingId.value) {
    draftForm.code = `PSYCH_SIM_${suffix}`
    draftForm.title = `高校学生心理适应测评模拟卷（${suffix}）`
  }
  draftForm.description =
    '面向高校学生的教学模拟心理适应评估问卷，涵盖学习压力、情绪状态、睡眠与作息三个维度，共6道单选题（含2道反向计分题）。结果仅供软件工程实训功能验证，不作医学诊断。'
  draftForm.scoringRule = {
    ruleVersion: 'R-2026.1',
    formula: 'SUM',
    reverseMaxPlusMin: 5,
    dimensions: [
      { code: 'STRESS', name: '学习压力', formula: 'SUM' },
      { code: 'MOOD', name: '情绪状态', formula: 'SUM' },
      { code: 'SLEEP', name: '睡眠与作息', formula: 'SUM' }
    ],
    levels: [
      {
        code: 'GOOD',
        name: '状态良好',
        min: 6,
        max: 10,
        minInclusive: true,
        maxInclusive: true,
        advice: '您近期整体心理适应与作息状态良好，建议继续保持规律作息与积极社交。'
      },
      {
        code: 'MILD',
        name: '轻度压力',
        min: 11,
        max: 15,
        minInclusive: true,
        maxInclusive: true,
        advice: '您近期存在一定学习或生活压力，建议合理规划学习节奏，适当进行户外运动与倾诉。'
      },
      {
        code: 'MODERATE',
        name: '中度困扰',
        min: 16,
        max: 20,
        minInclusive: true,
        maxInclusive: true,
        advice: '您近期情绪或睡眠受到较明显困扰，建议主动参与校园心理沙龙或预约中心老师沟通。'
      },
      {
        code: 'HIGH',
        name: '重点关注',
        min: 21,
        max: 24,
        minInclusive: true,
        maxInclusive: true,
        advice: '您近期心理负荷较高，建议尽快联系辅导员或学校心理健康教育中心获取一对一支持。'
      }
    ]
  }
  draftForm.questions = [
    {
      questionNo: 1,
      stem: '近期面对课程任务与考核时，我感到难以负荷。',
      questionType: 'SINGLE',
      dimensionCode: 'STRESS',
      isReverse: 0,
      required: 1,
      options: createDefaultOptions()
    },
    {
      questionNo: 2,
      stem: '课余时间我仍会反复担忧未完成的学习事项，难以放松。',
      questionType: 'SINGLE',
      dimensionCode: 'STRESS',
      isReverse: 0,
      required: 1,
      options: createDefaultOptions()
    },
    {
      questionNo: 3,
      stem: '近期我容易感到烦躁、低落或提不起精神。',
      questionType: 'SINGLE',
      dimensionCode: 'MOOD',
      isReverse: 0,
      required: 1,
      options: createDefaultOptions()
    },
    {
      questionNo: 4,
      stem: '遇到困难时，我能够保持平和心态并找到积极的排解方式。（反向计分）',
      questionType: 'SINGLE',
      dimensionCode: 'MOOD',
      isReverse: 1,
      required: 1,
      options: createDefaultOptions()
    },
    {
      questionNo: 5,
      stem: '近期我存在入睡困难、夜间易醒或早醒的情况。',
      questionType: 'SINGLE',
      dimensionCode: 'SLEEP',
      isReverse: 0,
      required: 1,
      options: createDefaultOptions()
    },
    {
      questionNo: 6,
      stem: '早晨醒来后我感到精力充沛，能够投入新一天学习。（反向计分）',
      questionType: 'SINGLE',
      dimensionCode: 'SLEEP',
      isReverse: 1,
      required: 1,
      options: createDefaultOptions()
    }
  ]
  ElMessage.success('已填入标准6题模拟模板')
}

function safeParseJson(val, fallback) {
  if (!val) return fallback
  if (typeof val === 'object') return val
  try {
    return JSON.parse(val)
  } catch {
    return fallback
  }
}

async function loadList() {
  loading.value = true
  try {
    const res = await getQuestionnairesApi()
    const list = res?.data ?? res ?? []
    questionnaires.value = Array.isArray(list) ? list : []
  } finally {
    loading.value = false
  }
}

function openCreateDrawer() {
  editingId.value = null
  activeEditorTab.value = 'questions'
  resetDraftForm()
  editorVisible.value = true
}

async function openEditDrawer(row) {
  editingId.value = row.id
  activeEditorTab.value = 'questions'
  editorVisible.value = true
  editorLoading.value = true
  try {
    const res = await getQuestionnaireDetailApi(row.id)
    const detail = res?.data ?? res ?? {}
    const rule = safeParseJson(detail.scoringRule ?? detail.scoringRuleJson, {})
    const rawQuestions = safeParseJson(detail.questions, [])

    draftForm.code = detail.code || ''
    draftForm.title = detail.title || ''
    draftForm.description = detail.description || ''
    draftForm.scoringRule = {
      ruleVersion: rule.ruleVersion || 'R-2026.1',
      formula: rule.formula || 'SUM',
      reverseMaxPlusMin: Number(rule.reverseMaxPlusMin ?? 5),
      dimensions: Array.isArray(rule.dimensions)
        ? rule.dimensions.map((d) => ({
            code: d.code || '',
            name: d.name || '',
            formula: d.formula || 'SUM'
          }))
        : [],
      levels: Array.isArray(rule.levels)
        ? rule.levels.map((lv) => ({
            code: lv.code || '',
            name: lv.name || '',
            min: Number(lv.min ?? 0),
            max: Number(lv.max ?? 10),
            minInclusive: lv.minInclusive !== false,
            maxInclusive: lv.maxInclusive !== false,
            advice: lv.advice || ''
          }))
        : []
    }
    draftForm.questions = Array.isArray(rawQuestions)
      ? rawQuestions.map((q, idx) => {
          const opts = safeParseJson(q.options ?? q.optionsJson, [])
          return {
            questionNo: Number(q.questionNo ?? idx + 1),
            stem: q.stem || '',
            questionType: q.questionType || 'SINGLE',
            dimensionCode: q.dimensionCode || 'DEFAULT',
            isReverse: Number(q.isReverse ? 1 : 0),
            required: q.required === 0 || q.required === false ? 0 : 1,
            options: Array.isArray(opts)
              ? opts.map((o, oIdx) => ({
                  optCode: o.optCode || String.fromCharCode(65 + oIdx),
                  label: o.label || '',
                  score: Number(o.score ?? oIdx + 1),
                  sort: Number(o.sort ?? oIdx + 1)
                }))
              : createDefaultOptions()
          }
        })
      : []
  } finally {
    editorLoading.value = false
  }
}

function renumberQuestions() {
  draftForm.questions.forEach((q, idx) => {
    q.questionNo = idx + 1
  })
}

function addQuestion() {
  const defaultDim = draftForm.scoringRule.dimensions[0]?.code || 'STRESS'
  draftForm.questions.push(createEmptyQuestion(draftForm.questions.length + 1, defaultDim))
}

function removeQuestion(index) {
  if (draftForm.questions.length <= 1) {
    ElMessage.warning('至少需要保留一道题目')
    return
  }
  draftForm.questions.splice(index, 1)
  renumberQuestions()
}

function moveQuestion(index, delta) {
  const target = index + delta
  if (target < 0 || target >= draftForm.questions.length) return
  const temp = draftForm.questions[index]
  draftForm.questions[index] = draftForm.questions[target]
  draftForm.questions[target] = temp
  renumberQuestions()
}

function addOption(question) {
  const nextIdx = question.options.length
  const nextCode = String.fromCharCode(65 + nextIdx)
  question.options.push({
    optCode: nextCode,
    label: '',
    score: nextIdx + 1,
    sort: nextIdx + 1
  })
}

function removeOption(question, optIdx) {
  if (question.options.length <= 2) {
    ElMessage.warning('每道单选题至少需要保留 2 个选项')
    return
  }
  question.options.splice(optIdx, 1)
  question.options.forEach((o, idx) => {
    o.sort = idx + 1
  })
}

function addDimension() {
  draftForm.scoringRule.dimensions.push({
    code: '',
    name: '',
    formula: 'SUM'
  })
}

function removeDimension(idx) {
  draftForm.scoringRule.dimensions.splice(idx, 1)
}

function addLevel() {
  const prevMax =
    draftForm.scoringRule.levels.length > 0
      ? Number(draftForm.scoringRule.levels[draftForm.scoringRule.levels.length - 1].max ?? 0)
      : 0
  draftForm.scoringRule.levels.push({
    code: '',
    name: '',
    min: prevMax + 1,
    max: prevMax + 5,
    minInclusive: true,
    maxInclusive: true,
    advice: ''
  })
}

function removeLevel(idx) {
  draftForm.scoringRule.levels.splice(idx, 1)
}

function validateBeforeSave() {
  if (!draftForm.code.trim()) return '请填写问卷唯一编码（code）'
  if (!draftForm.title.trim()) return '请填写问卷标题'
  if (!draftForm.questions.length) return '请至少配置 1 道题目'

  for (let i = 0; i < draftForm.questions.length; i++) {
    const q = draftForm.questions[i]
    if (!q.stem.trim()) return `第 ${i + 1} 题的题干不能为空`
    if (!q.dimensionCode?.trim()) return `第 ${i + 1} 题未指定归属维度编码`
    if (!q.options || q.options.length < 2) return `第 ${i + 1} 题至少需要 2 个选项`
    const codeSet = new Set()
    for (let j = 0; j < q.options.length; j++) {
      const opt = q.options[j]
      if (!opt.optCode?.trim()) return `第 ${i + 1} 题存在空的选项编码`
      if (codeSet.has(opt.optCode.trim())) {
        return `第 ${i + 1} 题选项编码 "${opt.optCode}" 重复`
      }
      codeSet.add(opt.optCode.trim())
      if (!opt.label?.trim()) return `第 ${i + 1} 题选项 ${opt.optCode} 的文本不能为空`
      if (opt.score === null || opt.score === undefined || Number.isNaN(Number(opt.score))) {
        return `第 ${i + 1} 题选项 ${opt.optCode} 的分值不合法`
      }
    }
  }

  if (!draftForm.scoringRule.dimensions.length) {
    return '请至少配置 1 个评估维度（计分规则页签）'
  }
  for (const dim of draftForm.scoringRule.dimensions) {
    if (!dim.code?.trim() || !dim.name?.trim()) {
      return '计分规则中存在未填写编码或名称的维度'
    }
  }
  if (!draftForm.scoringRule.levels.length) {
    return '请至少配置 1 个结果区间（计分规则页签）'
  }
  for (const lv of draftForm.scoringRule.levels) {
    if (!lv.code?.trim() || !lv.name?.trim()) {
      return '结果区间存在未填写的区间编码或名称'
    }
    if (Number(lv.min) > Number(lv.max)) {
      return `结果区间「${lv.name}」最低分不能大于最高分`
    }
  }
  return ''
}

async function saveDraft() {
  const errMsg = validateBeforeSave()
  if (errMsg) {
    ElMessage.warning(errMsg)
    return
  }

  const payload = {
    code: draftForm.code.trim(),
    title: draftForm.title.trim(),
    description: draftForm.description.trim(),
    scoringRule: {
      ruleVersion: draftForm.scoringRule.ruleVersion.trim() || 'R-2026.1',
      formula: draftForm.scoringRule.formula,
      reverseMaxPlusMin: Number(draftForm.scoringRule.reverseMaxPlusMin),
      dimensions: draftForm.scoringRule.dimensions.map((d) => ({
        code: d.code.trim(),
        name: d.name.trim(),
        formula: d.formula || 'SUM'
      })),
      levels: draftForm.scoringRule.levels.map((lv) => ({
        code: lv.code.trim(),
        name: lv.name.trim(),
        min: Number(lv.min),
        max: Number(lv.max),
        minInclusive: Boolean(lv.minInclusive),
        maxInclusive: Boolean(lv.maxInclusive),
        advice: lv.advice?.trim() || ''
      }))
    },
    questions: draftForm.questions.map((q, idx) => ({
      questionNo: Number(q.questionNo || idx + 1),
      stem: q.stem.trim(),
      questionType: 'SINGLE',
      dimensionCode: q.dimensionCode.trim(),
      isReverse: q.isReverse ? 1 : 0,
      required: q.required ? 1 : 0,
      options: q.options.map((o, oIdx) => ({
        optCode: o.optCode.trim(),
        label: o.label.trim(),
        score: Number(o.score),
        sort: Number(o.sort || oIdx + 1)
      }))
    }))
  }

  editorSaving.value = true
  try {
    if (editingId.value) {
      await updateQuestionnaireApi(editingId.value, payload)
      ElMessage.success('问卷草稿已保存')
    } else {
      await createQuestionnaireApi(payload)
      ElMessage.success('问卷已创建')
    }
    editorVisible.value = false
    await loadList()
  } finally {
    editorSaving.value = false
  }
}

// ==================== 发布新版本弹窗 ====================
const publishDialogVisible = ref(false)
const publishing = ref(false)
const publishTarget = ref(null)
const publishRuleVersion = ref('')

function openPublishDialog(row) {
  publishTarget.value = row
  const nextVer = (row.latestVersion || 0) + 1
  publishRuleVersion.value = `R-2026.${nextVer}`
  publishDialogVisible.value = true
}

async function confirmPublish() {
  if (!publishTarget.value) return
  publishing.value = true
  try {
    const body = publishRuleVersion.value.trim()
      ? { ruleVersion: publishRuleVersion.value.trim() }
      : {}
    const res = await publishQuestionnaireApi(publishTarget.value.id, body)
    const ver = res?.data ?? res
    ElMessage.success(
      `已发布版本 v${ver?.versionNum ?? (publishTarget.value.latestVersion || 0) + 1}`
    )
    publishDialogVisible.value = false
    await loadList()
  } finally {
    publishing.value = false
  }
}

// ==================== 历史版本快照只读抽屉 ====================
const historyDialogVisible = ref(false)
const historyLoading = ref(false)
const snapshotLoading = ref(false)
const historyTarget = ref(null)
const versionList = ref([])
const selectedVersionId = ref(null)
const currentSnapshot = ref(null)
const snapshotViewMode = ref('structured')

async function openHistoryDialog(row) {
  historyTarget.value = row
  versionList.value = []
  selectedVersionId.value = null
  currentSnapshot.value = null
  snapshotViewMode.value = 'structured'
  historyDialogVisible.value = true
  historyLoading.value = true
  try {
    const res = await getQuestionnaireVersionsByQidApi(row.id)
    const list = res?.data ?? res ?? []
    versionList.value = Array.isArray(list) ? list : []
    if (versionList.value.length > 0) {
      await inspectSnapshot(versionList.value[0].id)
    }
  } finally {
    historyLoading.value = false
  }
}

async function inspectSnapshot(versionId) {
  selectedVersionId.value = versionId
  snapshotLoading.value = true
  try {
    const res = await getQuestionnaireVersionSnapshotApi(versionId)
    const data = res?.data ?? res ?? {}
    currentSnapshot.value = {
      ...data,
      questionsParsed: safeParseJson(data.questionsSnapshot ?? data.questions, []),
      scoringRuleParsed: safeParseJson(data.scoringRuleSnapshot ?? data.scoringRule, {})
    }
  } finally {
    snapshotLoading.value = false
  }
}

onMounted(() => {
  loadList()
})
</script>

<template>
  <div class="page-container">
    <!-- 顶部工具栏 -->
    <div class="page-toolbar">
      <div class="toolbar-left">
        <h2 class="toolbar-title">问卷配置与版本</h2>
        <span class="toolbar-desc">
          管理心理测评问卷草稿、选项分值与计分规则，发布版本后生成只读快照供评估活动绑定
        </span>
      </div>
      <div class="toolbar-right">
        <el-button @click="loadList">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateDrawer">
          新建问卷
        </el-button>
      </div>
    </div>

    <!-- 问卷列表表格 -->
    <div class="panel">
      <el-table
        :data="questionnaires"
        v-loading="loading"
        empty-text="暂无问卷记录，请点击右上角「新建问卷」"
      >
        <el-table-column prop="code" label="问卷编码" width="190">
          <template #default="{ row }">
            <span class="mono-code code-text">{{ row.code }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="title" label="问卷名称" min-width="250" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="q-title">{{ row.title }}</div>
            <div v-if="row.description" class="q-desc">{{ row.description }}</div>
          </template>
        </el-table-column>

        <el-table-column label="已发布版本" width="130" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.latestVersion > 0" type="primary" size="small" effect="plain">
              v{{ row.latestVersion }}
            </el-tag>
            <el-tag v-else type="info" size="small" effect="plain">未发布</el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="questionCount" label="题目数" width="110" align="center">
          <template #default="{ row }">
            <span class="tabular-nums">{{ row.questionCount ?? '-' }} 题</span>
          </template>
        </el-table-column>

        <el-table-column prop="updatedAt" label="最后修改时间" width="175" align="center">
          <template #default="{ row }">
            <span class="tabular-nums time-text">{{ row.updatedAt || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="230" fixed="right" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEditDrawer(row)">
              编辑草稿
            </el-button>
            <el-button link type="primary" size="small" @click="openPublishDialog(row)">
              发布版本
            </el-button>
            <el-button link size="small" @click="openHistoryDialog(row)">
              历史版本
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 问卷草稿与计分规则编辑抽屉 -->
    <el-drawer
      v-model="editorVisible"
      :title="editingId ? '编辑问卷草稿' : '新建问卷'"
      size="900px"
      destroy-on-close
    >
      <div v-loading="editorLoading" class="drawer-body">
        <div class="editor-note-bar">
          <span>草稿修改不会影响已发布的历史版本快照。保存后可执行「发布版本」生成新快照。</span>
          <el-button
            v-if="!editingId"
            type="primary"
            plain
            size="small"
            @click="fillStandard6Template"
          >
            填入标准6题模拟模板
          </el-button>
        </div>

        <!-- 基本信息 -->
        <el-form label-position="top" class="basic-grid">
          <el-form-item label="问卷唯一编码 (code)" required>
            <el-input
              v-model="draftForm.code"
              :disabled="Boolean(editingId)"
              placeholder="如 CAMPUS_PSYCH_2026"
            />
          </el-form-item>
          <el-form-item label="问卷标题 (title)" required>
            <el-input
              v-model="draftForm.title"
              placeholder="请输入问卷完整标题"
              maxlength="128"
            />
          </el-form-item>
          <el-form-item label="问卷导语及评估说明 (description)" class="span-2">
            <el-input
              v-model="draftForm.description"
              type="textarea"
              :rows="2"
              placeholder="向受测学生展示的指导语与说明"
            />
          </el-form-item>
        </el-form>

        <!-- 配置页签 -->
        <el-tabs v-model="activeEditorTab" type="border-card" class="config-tabs">
          <!-- 页签 1: 题目与选项 -->
          <el-tab-pane name="questions" :label="`题目与选项 (${draftForm.questions.length}题)`">
            <div class="tab-toolbar">
              <span class="tab-hint">
                反向计分题得分 = 反向计分基数 ({{ draftForm.scoringRule.reverseMaxPlusMin }}) - 选项原始分
              </span>
              <el-button type="primary" size="small" :icon="Plus" @click="addQuestion">
                新增题目
              </el-button>
            </div>

            <div class="question-cards">
              <div
                v-for="(q, qIdx) in draftForm.questions"
                :key="qIdx"
                class="q-editor-card"
              >
                <div class="q-card-header">
                  <div class="q-header-left">
                    <span class="q-badge tabular-nums">第 {{ q.questionNo }} 题</span>
                    <el-input-number
                      v-model="q.questionNo"
                      :min="1"
                      :max="999"
                      size="small"
                      controls-position="right"
                      style="width: 84px"
                    />
                    <el-select
                      v-model="q.dimensionCode"
                      filterable
                      allow-create
                      default-first-option
                      size="small"
                      placeholder="归属维度"
                      style="width: 165px"
                    >
                      <el-option
                        v-for="dim in draftForm.scoringRule.dimensions"
                        :key="dim.code"
                        :label="`${dim.name || dim.code} (${dim.code})`"
                        :value="dim.code"
                      />
                    </el-select>

                    <div class="inline-switch">
                      <span>反向计分</span>
                      <el-switch
                        v-model="q.isReverse"
                        :active-value="1"
                        :inactive-value="0"
                      />
                    </div>

                    <div class="inline-switch">
                      <span>必答</span>
                      <el-switch
                        v-model="q.required"
                        :active-value="1"
                        :inactive-value="0"
                      />
                    </div>
                  </div>

                  <div class="q-header-actions">
                    <el-button
                      size="small"
                      :icon="Top"
                      :disabled="qIdx === 0"
                      title="上移"
                      @click="moveQuestion(qIdx, -1)"
                    />
                    <el-button
                      size="small"
                      :icon="Bottom"
                      :disabled="qIdx === draftForm.questions.length - 1"
                      title="下移"
                      @click="moveQuestion(qIdx, 1)"
                    />
                    <el-button
                      size="small"
                      type="danger"
                      plain
                      :icon="Delete"
                      title="删除本题"
                      @click="removeQuestion(qIdx)"
                    />
                  </div>
                </div>

                <el-input
                  v-model="q.stem"
                  placeholder="请输入题干内容..."
                  class="stem-input"
                />

                <el-table :data="q.options" size="small" border>
                  <el-table-column label="选项编码" width="105">
                    <template #default="{ row: opt }">
                      <el-input v-model="opt.optCode" size="small" placeholder="A/B/C/D" />
                    </template>
                  </el-table-column>
                  <el-table-column label="选项文字" min-width="220">
                    <template #default="{ row: opt }">
                      <el-input v-model="opt.label" size="small" placeholder="如：从不 / 偶尔 / 经常 / 总是" />
                    </template>
                  </el-table-column>
                  <el-table-column label="分值" width="135" align="center">
                    <template #default="{ row: opt }">
                      <el-input-number
                        v-model="opt.score"
                        :step="1"
                        :precision="0"
                        size="small"
                        style="width: 105px"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column label="排序" width="105" align="center">
                    <template #default="{ row: opt }">
                      <el-input-number
                        v-model="opt.sort"
                        :min="1"
                        size="small"
                        controls-position="right"
                        style="width: 78px"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="75" align="center">
                    <template #default="{ $index }">
                      <el-button
                        link
                        type="danger"
                        size="small"
                        @click="removeOption(q, $index)"
                      >
                        删除
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>

                <div class="opt-footer">
                  <el-button link type="primary" size="small" :icon="Plus" @click="addOption(q)">
                    添加选项
                  </el-button>
                  <span v-if="q.isReverse === 1" class="reverse-tip tabular-nums">
                    已启用反向计分：实际得分 = {{ draftForm.scoringRule.reverseMaxPlusMin }} - 原始分
                  </span>
                </div>
              </div>
            </div>
          </el-tab-pane>

          <!-- 页签 2: 计分规则与结果区间 -->
          <el-tab-pane name="scoring" label="计分规则与结果区间">
            <div class="rule-base-grid">
              <div class="rule-field">
                <label>规则版本标识 (ruleVersion)</label>
                <el-input v-model="draftForm.scoringRule.ruleVersion" placeholder="如 R-2026.1" />
              </div>
              <div class="rule-field">
                <label>总分汇总公式 (formula)</label>
                <el-select v-model="draftForm.scoringRule.formula" style="width: 100%">
                  <el-option label="SUM · 各题得分求和" value="SUM" />
                  <el-option label="AVG · 各题得分均值" value="AVG" />
                </el-select>
              </div>
              <div class="rule-field">
                <label>反向计分基数 (reverseMaxPlusMin)</label>
                <el-input-number
                  v-model="draftForm.scoringRule.reverseMaxPlusMin"
                  :min="1"
                  :max="100"
                  style="width: 100%"
                />
              </div>
            </div>

            <!-- 维度定义 -->
            <div class="sub-block-header">
              <span>评估维度定义 (dimensions)</span>
              <el-button size="small" type="primary" plain :icon="Plus" @click="addDimension">
                添加维度
              </el-button>
            </div>
            <el-table :data="draftForm.scoringRule.dimensions" size="small" border>
              <el-table-column label="维度编码 (code)" width="170">
                <template #default="{ row }">
                  <el-input v-model="row.code" size="small" placeholder="如 STRESS" />
                </template>
              </el-table-column>
              <el-table-column label="维度名称 (name)" min-width="180">
                <template #default="{ row }">
                  <el-input v-model="row.name" size="small" placeholder="如 学习压力" />
                </template>
              </el-table-column>
              <el-table-column label="计分公式" width="160">
                <template #default="{ row }">
                  <el-select v-model="row.formula" size="small">
                    <el-option label="SUM 求和" value="SUM" />
                    <el-option label="AVG 均值" value="AVG" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80" align="center">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" @click="removeDimension($index)">
                    移除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>

            <!-- 结果区间 -->
            <div class="sub-block-header" style="margin-top: 20px">
              <span>总分结果区间与反馈建议 (levels)</span>
              <el-button size="small" type="primary" plain :icon="Plus" @click="addLevel">
                添加结果区间
              </el-button>
            </div>
            <el-table :data="draftForm.scoringRule.levels" size="small" border>
              <el-table-column label="区间编码" width="115">
                <template #default="{ row }">
                  <el-input v-model="row.code" size="small" placeholder="GOOD" />
                </template>
              </el-table-column>
              <el-table-column label="区间名称" width="125">
                <template #default="{ row }">
                  <el-input v-model="row.name" size="small" placeholder="状态良好" />
                </template>
              </el-table-column>
              <el-table-column label="最低分 (min)" width="118">
                <template #default="{ row }">
                  <el-input-number v-model="row.min" size="small" style="width: 95px" />
                </template>
              </el-table-column>
              <el-table-column label="含下界" width="68" align="center">
                <template #default="{ row }">
                  <el-checkbox v-model="row.minInclusive" />
                </template>
              </el-table-column>
              <el-table-column label="最高分 (max)" width="118">
                <template #default="{ row }">
                  <el-input-number v-model="row.max" size="small" style="width: 95px" />
                </template>
              </el-table-column>
              <el-table-column label="含上界" width="68" align="center">
                <template #default="{ row }">
                  <el-checkbox v-model="row.maxInclusive" />
                </template>
              </el-table-column>
              <el-table-column label="反馈与指导建议 (advice)" min-width="210">
                <template #default="{ row }">
                  <el-input
                    v-model="row.advice"
                    size="small"
                    placeholder="针对该分数段的调适建议"
                  />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="68" align="center">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" @click="removeLevel($index)">
                    移除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="editorVisible = false">取消</el-button>
          <el-button type="primary" :loading="editorSaving" @click="saveDraft">
            保存草稿
          </el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 发布版本弹窗 -->
    <el-dialog
      v-model="publishDialogVisible"
      title="发布版本"
      width="480px"
      destroy-on-close
    >
      <div class="publish-notice">
        发布后将当前草稿的全部题目、选项分值与计分规则冻结为只读版本快照
        <strong class="tabular-nums">v{{ (publishTarget?.latestVersion || 0) + 1 }}</strong>。已绑定历史版本的评估活动不受影响。
      </div>

      <el-form label-width="100px">
        <el-form-item label="目标问卷">
          <strong>{{ publishTarget?.title }}</strong>
        </el-form-item>
        <el-form-item label="生成版本号">
          <el-tag type="primary" size="small" effect="plain" class="tabular-nums">
            v{{ (publishTarget?.latestVersion || 0) + 1 }}
          </el-tag>
        </el-form-item>
        <el-form-item label="规则版本号">
          <el-input
            v-model="publishRuleVersion"
            placeholder="如 R-2026.2（留空按草稿或默认生成）"
            clearable
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="publishDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="publishing" @click="confirmPublish">
          确认发布
        </el-button>
      </template>
    </el-dialog>

    <!-- 历史版本只读快照抽屉 -->
    <el-drawer
      v-model="historyDialogVisible"
      :title="`历史版本快照 · ${historyTarget?.title || ''}`"
      size="860px"
      destroy-on-close
    >
      <div v-loading="historyLoading" class="history-layout">
        <div v-if="!versionList.length && !historyLoading" class="empty-versions">
          <el-empty description="该问卷尚未发布过版本，请先在列表点击「发布版本」" />
        </div>

        <template v-else>
          <!-- 左侧版本列表 -->
          <div class="version-sidebar">
            <div class="ver-side-title">已发布版本</div>
            <div
              v-for="ver in versionList"
              :key="ver.id"
              class="ver-item"
              :class="{ active: selectedVersionId === ver.id }"
              @click="inspectSnapshot(ver.id)"
            >
              <div class="ver-row-top">
                <span class="ver-num tabular-nums">v{{ ver.versionNum }}</span>
                <span class="mono-code ver-rule">{{ ver.ruleVersion }}</span>
              </div>
              <div class="ver-time tabular-nums">{{ ver.publishedAt || '-' }}</div>
            </div>
          </div>

          <!-- 右侧只读快照内容 -->
          <div class="snapshot-viewer" v-loading="snapshotLoading">
            <template v-if="currentSnapshot">
              <div class="snap-header">
                <div class="snap-title-wrap">
                  <span class="snap-title tabular-nums">
                    版本 v{{ currentSnapshot.versionNum }}（规则 {{ currentSnapshot.ruleVersion }}）
                  </span>
                  <el-tag
                    v-if="currentSnapshot.cacheSource"
                    size="small"
                    :type="currentSnapshot.cacheSource === 'HIT' ? 'success' : 'warning'"
                    effect="plain"
                  >
                    缓存 {{ currentSnapshot.cacheSource }}
                  </el-tag>
                </div>
                <el-radio-group v-model="snapshotViewMode" size="small">
                  <el-radio-button value="structured">结构化视图</el-radio-button>
                  <el-radio-button value="json">原始 JSON</el-radio-button>
                </el-radio-group>
              </div>

              <div v-if="snapshotViewMode === 'structured'" class="snap-structured">
                <div class="snap-meta-box">
                  <div>
                    <strong>计分公式：</strong>{{ currentSnapshot.scoringRuleParsed?.formula || 'SUM' }}
                    <span class="divider">|</span>
                    <strong>反向计分基数：</strong>
                    <span class="tabular-nums">{{ currentSnapshot.scoringRuleParsed?.reverseMaxPlusMin ?? 5 }}</span>
                  </div>
                  <div style="margin-top: 6px">
                    <strong>评估维度：</strong>
                    <el-tag
                      v-for="d in currentSnapshot.scoringRuleParsed?.dimensions || []"
                      :key="d.code"
                      size="small"
                      effect="plain"
                      style="margin-right: 6px"
                    >
                      {{ d.name }} ({{ d.code }})
                    </el-tag>
                  </div>
                  <div style="margin-top: 6px">
                    <strong>结果区间：</strong>
                    <el-tag
                      v-for="lv in currentSnapshot.scoringRuleParsed?.levels || []"
                      :key="lv.code"
                      size="small"
                      type="info"
                      effect="plain"
                      class="tabular-nums"
                      style="margin-right: 6px"
                    >
                      {{ lv.name }} [{{ lv.min }}~{{ lv.max }}分]
                    </el-tag>
                  </div>
                </div>

                <div class="snap-q-list">
                  <div
                    v-for="q in currentSnapshot.questionsParsed"
                    :key="q.questionNo"
                    class="snap-q-item"
                  >
                    <div class="snap-q-head">
                      <strong class="tabular-nums">Q{{ q.questionNo }}. {{ q.stem }}</strong>
                      <div class="snap-q-tags">
                        <el-tag size="small" effect="plain">{{ q.dimensionCode }}</el-tag>
                        <el-tag v-if="q.isReverse" size="small" type="warning" effect="plain">
                          反向计分
                        </el-tag>
                      </div>
                    </div>
                    <div class="snap-opts">
                      <span
                        v-for="opt in q.options || []"
                        :key="opt.optCode"
                        class="snap-opt-chip tabular-nums"
                      >
                        {{ opt.optCode }}. {{ opt.label }}（{{ opt.score }}分）
                      </span>
                    </div>
                  </div>
                </div>
              </div>

              <pre v-else class="snap-json mono-code">{{
                JSON.stringify(
                  {
                    versionId: currentSnapshot.id,
                    versionNum: currentSnapshot.versionNum,
                    ruleVersion: currentSnapshot.ruleVersion,
                    scoringRuleSnapshot: currentSnapshot.scoringRuleParsed,
                    questionsSnapshot: currentSnapshot.questionsParsed
                  },
                  null,
                  2
                )
              }}</pre>
            </template>
          </div>
        </template>
      </div>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="historyDialogVisible = false">关闭</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.code-text {
  color: #2055A4;
  font-weight: 500;
}

.q-title {
  font-weight: 500;
  color: #111827;
}

.q-desc {
  margin-top: 2px;
  font-size: 12px;
  color: #6B7280;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time-text {
  font-size: 12px;
  color: #6B7280;
}

.drawer-body {
  padding-right: 4px;
}

.editor-note-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  margin-bottom: 16px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
  font-size: 12.5px;
  color: #4B5563;
}

.basic-grid {
  display: grid;
  grid-template-columns: 1fr 1.4fr;
  gap: 0 16px;
}

.basic-grid .span-2 {
  grid-column: span 2;
}

.config-tabs {
  border-radius: 6px;
}

.tab-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.tab-hint {
  font-size: 12px;
  color: #6B7280;
}

.question-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.q-editor-card {
  padding: 14px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
}

.q-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.q-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.q-badge {
  padding: 2px 8px;
  border-radius: 4px;
  background-color: #EEF3FA;
  border: 1px solid #D0DDF0;
  color: #2055A4;
  font-size: 12px;
  font-weight: 600;
}

.inline-switch {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #4B5563;
}

.stem-input {
  margin-bottom: 10px;
}

.opt-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.reverse-tip {
  font-size: 12px;
  color: #D97706;
}

.rule-base-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 18px;
}

.rule-field label {
  display: block;
  margin-bottom: 6px;
  font-size: 12.5px;
  font-weight: 500;
  color: #4B5563;
}

.sub-block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #111827;
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.publish-notice {
  padding: 10px 14px;
  margin-bottom: 16px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
  font-size: 13px;
  line-height: 1.6;
  color: #4B5563;
}

/* History Snapshot Drawer */
.history-layout {
  display: grid;
  grid-template-columns: 200px 1fr;
  gap: 16px;
  min-height: 420px;
}

.empty-versions {
  grid-column: span 2;
}

.version-sidebar {
  border-right: 1px solid #E5E7EB;
  padding-right: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ver-side-title {
  font-size: 12px;
  font-weight: 600;
  color: #6B7280;
  margin-bottom: 4px;
}

.ver-item {
  padding: 10px 12px;
  border-radius: 6px;
  border: 1px solid #E5E7EB;
  background-color: #FFFFFF;
  cursor: pointer;
}

.ver-item:hover {
  background-color: #F9FAFB;
}

.ver-item.active {
  background-color: #EEF3FA;
  border-color: #2055A4;
}

.ver-row-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ver-num {
  font-weight: 600;
  color: #2055A4;
  font-size: 13px;
}

.ver-rule {
  color: #4B5563;
}

.ver-time {
  margin-top: 4px;
  font-size: 11px;
  color: #6B7280;
}

.snapshot-viewer {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.snap-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 8px;
  flex-wrap: wrap;
}

.snap-title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.snap-title {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}

.snap-meta-box {
  padding: 12px 14px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
  font-size: 12.5px;
  margin-bottom: 12px;
  color: #4B5563;
}

.divider {
  margin: 0 10px;
  color: #D1D5DB;
}

.snap-q-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.snap-q-item {
  padding: 12px;
  border-radius: 6px;
  border: 1px solid #E5E7EB;
  background-color: #FFFFFF;
}

.snap-q-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 8px;
  color: #111827;
}

.snap-q-tags {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.snap-opts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.snap-opt-chip {
  padding: 2px 8px;
  border-radius: 4px;
  background-color: #F5F6F8;
  border: 1px solid #E5E7EB;
  font-size: 12px;
  color: #4B5563;
}

.snap-json {
  max-height: 480px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  border-radius: 6px;
  background-color: #F9FAFB;
  border: 1px solid #E5E7EB;
  color: #111827;
  line-height: 1.5;
}
</style>
