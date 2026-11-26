// 问卷已发布版本快照的结构化对比工具。
// 输入为版本详情接口返回的原始数据（questionsSnapshot / scoringRuleSnapshot 可能是 JSON 字符串），
// 输出为按「题目 / 选项 / 计分规则」分组的旧值-新值差异列表，供版本对比视图直接渲染。

function parseMaybeJson(val, fallback) {
  if (val === null || val === undefined || val === '') return fallback
  if (typeof val === 'object') return val
  try {
    return JSON.parse(val)
  } catch {
    return fallback
  }
}

function asArray(val) {
  const parsed = parseMaybeJson(val, [])
  return Array.isArray(parsed) ? parsed : []
}

function normalizeOption(o, idx) {
  return {
    optCode: String(o?.optCode ?? String.fromCharCode(65 + idx)),
    label: String(o?.label ?? ''),
    score: Number(o?.score ?? 0),
    sort: Number(o?.sort ?? idx + 1)
  }
}

function normalizeQuestion(q, idx) {
  return {
    questionNo: Number(q?.questionNo ?? idx + 1),
    stem: String(q?.stem ?? ''),
    questionType: String(q?.questionType ?? 'SINGLE'),
    dimensionCode: String(q?.dimensionCode ?? ''),
    isReverse: q?.isReverse === true || Number(q?.isReverse) === 1 ? 1 : 0,
    required: q?.required === 0 || q?.required === false ? 0 : 1,
    options: asArray(q?.options ?? q?.optionsJson).map(normalizeOption)
  }
}

function normalizeDimension(d) {
  return {
    code: String(d?.code ?? ''),
    name: String(d?.name ?? ''),
    formula: String(d?.formula ?? 'SUM')
  }
}

function normalizeLevel(lv) {
  return {
    code: String(lv?.code ?? ''),
    name: String(lv?.name ?? ''),
    min: Number(lv?.min ?? 0),
    max: Number(lv?.max ?? 0),
    minInclusive: lv?.minInclusive !== false,
    maxInclusive: lv?.maxInclusive !== false,
    advice: String(lv?.advice ?? '')
  }
}

export function normalizeVersionSnapshot(data) {
  const rawRule = parseMaybeJson(data?.scoringRuleSnapshot ?? data?.scoringRule, {})
  const rule = rawRule && typeof rawRule === 'object' ? rawRule : {}
  return {
    versionNum: data?.versionNum ?? null,
    ruleVersion: String(data?.ruleVersion ?? rule.ruleVersion ?? ''),
    publishedAt: data?.publishedAt ?? '',
    questions: asArray(data?.questionsSnapshot ?? data?.questions).map(normalizeQuestion),
    rule: {
      ruleVersion: String(rule.ruleVersion ?? data?.ruleVersion ?? ''),
      formula: String(rule.formula ?? 'SUM'),
      reverseMaxPlusMin:
        rule.reverseMaxPlusMin === undefined || rule.reverseMaxPlusMin === null
          ? null
          : Number(rule.reverseMaxPlusMin),
      dimensions: asArray(rule.dimensions).map(normalizeDimension),
      levels: asArray(rule.levels).map(normalizeLevel)
    }
  }
}

function optionText(opt) {
  return `${opt.label}（${opt.score}分）`
}

function diffOptions(oldOpts, newOpts) {
  const rows = []
  const oldMap = new Map(oldOpts.map((o) => [o.optCode, o]))
  const newMap = new Map(newOpts.map((o) => [o.optCode, o]))
  const codes = [...oldOpts.map((o) => o.optCode)]
  for (const code of newMap.keys()) {
    if (!oldMap.has(code)) codes.push(code)
  }

  for (const code of codes) {
    const oldOpt = oldMap.get(code)
    const newOpt = newMap.get(code)
    if (oldOpt && !newOpt) {
      rows.push({
        label: `选项 ${code}（删除）`,
        key: 'option',
        old: optionText(oldOpt),
        new: null
      })
      continue
    }
    if (!oldOpt && newOpt) {
      rows.push({
        label: `选项 ${code}（新增）`,
        key: 'option',
        old: null,
        new: optionText(newOpt)
      })
      continue
    }
    if (oldOpt.label !== newOpt.label) {
      rows.push({ label: `选项 ${code} · 文字`, key: 'label', old: oldOpt.label, new: newOpt.label })
    }
    if (oldOpt.score !== newOpt.score) {
      rows.push({ label: `选项 ${code} · 分值`, key: 'score', old: oldOpt.score, new: newOpt.score })
    }
    if (oldOpt.sort !== newOpt.sort) {
      rows.push({ label: `选项 ${code} · 排序`, key: 'sort', old: oldOpt.sort, new: newOpt.sort })
    }
  }
  return rows
}

function diffQuestions(oldQuestions, newQuestions) {
  const oldMap = new Map(oldQuestions.map((q) => [q.questionNo, q]))
  const newMap = new Map(newQuestions.map((q) => [q.questionNo, q]))

  const addedQuestions = newQuestions.filter((q) => !oldMap.has(q.questionNo))
  const removedQuestions = oldQuestions.filter((q) => !newMap.has(q.questionNo))
  const changedQuestions = []

  const questionFieldLabels = [
    ['stem', '题干'],
    ['questionType', '题型'],
    ['dimensionCode', '归属维度'],
    ['isReverse', '反向计分'],
    ['required', '必答']
  ]

  for (const newQ of newQuestions) {
    const oldQ = oldMap.get(newQ.questionNo)
    if (!oldQ) continue
    const changes = []
    for (const [key, label] of questionFieldLabels) {
      if (oldQ[key] !== newQ[key]) {
        changes.push({ label, key, old: oldQ[key], new: newQ[key] })
      }
    }
    changes.push(...diffOptions(oldQ.options, newQ.options))
    if (changes.length > 0) {
      changedQuestions.push({
        questionNo: newQ.questionNo,
        stem: newQ.stem,
        dimensionCode: newQ.dimensionCode,
        changes
      })
    }
  }

  changedQuestions.sort((a, b) => a.questionNo - b.questionNo)
  return { addedQuestions, removedQuestions, changedQuestions }
}

function diffKeyedList(oldList, newList, fieldLabels, addedLabel, removedLabel, summaryFn) {
  const oldMap = new Map(oldList.map((item) => [item.code, item]))
  const newMap = new Map(newList.map((item) => [item.code, item]))
  const codes = [...oldList.map((item) => item.code)]
  for (const code of newMap.keys()) {
    if (!oldMap.has(code)) codes.push(code)
  }

  const result = []
  for (const code of codes) {
    const oldItem = oldMap.get(code)
    const newItem = newMap.get(code)
    if (oldItem && !newItem) {
      result.push({
        code,
        status: 'removed',
        changes: [{ label: removedLabel, key: 'removed', old: summaryFn(oldItem), new: null }]
      })
      continue
    }
    if (!oldItem && newItem) {
      result.push({
        code,
        status: 'added',
        changes: [{ label: addedLabel, key: 'added', old: null, new: summaryFn(newItem) }]
      })
      continue
    }
    const changes = []
    for (const [key, label] of fieldLabels) {
      if (oldItem[key] !== newItem[key]) {
        changes.push({ label, key, old: oldItem[key], new: newItem[key] })
      }
    }
    if (changes.length > 0) {
      result.push({ code, status: 'changed', changes })
    }
  }
  return result
}

function diffScoringRule(oldRule, newRule) {
  const baseFields = []
  const baseLabels = [
    ['ruleVersion', '规则版本标识'],
    ['formula', '总分汇总公式'],
    ['reverseMaxPlusMin', '反向计分基数']
  ]
  for (const [key, label] of baseLabels) {
    if (oldRule[key] !== newRule[key]) {
      baseFields.push({ label, key, old: oldRule[key], new: newRule[key] })
    }
  }

  const dimensionChanges = diffKeyedList(
    oldRule.dimensions,
    newRule.dimensions,
    [
      ['name', '维度名称'],
      ['formula', '计分公式']
    ],
    '新增维度',
    '删除维度',
    (d) => `${d.name} · ${d.formula}`
  )

  const levelChanges = diffKeyedList(
    oldRule.levels,
    newRule.levels,
    [
      ['name', '区间名称'],
      ['min', '最低分'],
      ['max', '最高分'],
      ['minInclusive', '含下界'],
      ['maxInclusive', '含上界'],
      ['advice', '反馈建议']
    ],
    '新增区间',
    '删除区间',
    (lv) => `${lv.name} [${lv.min}~${lv.max}分]`
  )

  return { baseFields, dimensionChanges, levelChanges }
}

export function diffQuestionnaireVersions(oldData, newData) {
  const oldSnap = normalizeVersionSnapshot(oldData)
  const newSnap = normalizeVersionSnapshot(newData)

  const { addedQuestions, removedQuestions, changedQuestions } = diffQuestions(
    oldSnap.questions,
    newSnap.questions
  )
  const ruleDiff = diffScoringRule(oldSnap.rule, newSnap.rule)

  const ruleChangeCount =
    ruleDiff.baseFields.length + ruleDiff.dimensionChanges.length + ruleDiff.levelChanges.length

  return {
    oldMeta: {
      versionNum: oldSnap.versionNum,
      ruleVersion: oldSnap.ruleVersion,
      publishedAt: oldSnap.publishedAt
    },
    newMeta: {
      versionNum: newSnap.versionNum,
      ruleVersion: newSnap.ruleVersion,
      publishedAt: newSnap.publishedAt
    },
    addedQuestions,
    removedQuestions,
    changedQuestions,
    ruleDiff,
    stats: {
      added: addedQuestions.length,
      removed: removedQuestions.length,
      changed: changedQuestions.length,
      ruleChanges: ruleChangeCount
    },
    totalChanges:
      addedQuestions.length + removedQuestions.length + changedQuestions.length + ruleChangeCount
  }
}