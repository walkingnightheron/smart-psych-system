# 智慧心理健康评估系统 — 三端统一接口与权限契约 (API Contract)

> 版本：v1.0.0（冻结版）
> 基准路径：`/api`（开发环境 PC `5173` 与 H5 `5174` 均代理至 `http://localhost:8080/api`）

---

## 一、全局约定

### 1. 统一响应包体 `Result<T>`
```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```
* **HTTP 200**：正常返回，`code = 0`。
* **HTTP 400**：请求参数错误、配置非法、状态流转非法、时间窗外提交、必答题漏答、非法选项。`code = 400`，`message` 精确描述字段/题号/选项错误原因。
* **HTTP 401**：未携带 `Authorization: Bearer <token>`、令牌过期、签名无效或账号已禁用。`code = 401`。
* **HTTP 403**：角色不匹配（如 `STUDENT` 访问 `/api/admin/**`）或数据范围越权（如未授权管理员访问他人活动/统计、学生读取他人答卷/报告、名单外学生访问/提交活动）。`code = 403`。
* **HTTP 404**：目标资源不存在。`code = 404`。
* **HTTP 409**：同一用户对同一活动重复/并发提交答卷。`code = 409`，**且 `data` 必须返回本人原提交记录与报告标识**：
```json
{
  "code": 409,
  "message": "您已提交过本次评估答卷，请勿重复提交",
  "data": {
    "sheetId": 1,
    "reportId": 1,
    "activityId": 1
  }
}
```

### 2. 身份认证头
除 `POST /api/auth/login` 与 `GET /api/health` 外，其余所有 `/api/**` 接口均需携带请求头：
```http
Authorization: Bearer <jwt_token>
```

### 3. 角色与数据授权矩阵
* `ADMIN`：可访问 `/api/auth/**`、`/api/admin/**` 及本人授权范围内的报告查看。对于具体活动（`assessment_activity`），仅当 `currentUserId == owner_id` 或 `authorized_admin_ids` 包含 `currentUserId` 时允许查看详情、进度、统计、授权明细与操作状态；否则返回 **403**。
* `STUDENT`：可访问 `/api/auth/**`、`/api/h5/**`。仅可查看本人有资格（在 `activity_participant` 名单内）的活动详情、提交本人答卷、读取 `user_id == currentUserId` 的提交记录与评估报告；读取他人记录/报告或访问 `/api/admin/**` 一律返回 **403**。

---

## 二、认证与个人资料接口 (REQ01 — PC & H5 共用)

### 1. 登录 `POST /api/auth/login`
* 请求体：
```json
{
  "username": "admin01",
  "password": "123456"
}
```
* 响应 `data`：
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "user": {
    "id": 1,
    "username": "admin01",
    "realName": "林知夏（中心主任）",
    "role": "ADMIN",
    "deptClass": "心理健康教育中心",
    "phone": "13800000001"
  }
}
```

### 2. 获取当前登录用户 `GET /api/auth/me`
* 响应 `data`：同上 `user` 对象。

### 3. 更新个人资料 `PUT /api/auth/profile`
* 请求体：
```json
{
  "realName": "林知夏",
  "deptClass": "心理健康教育中心",
  "phone": "13800000001"
}
```
* 响应 `data`：更新后的 `user` 对象。

---

## 三、PC 管理端接口 (`/api/admin/**`，仅限 `ADMIN` 角色)

### 1. 工作台概览 `GET /api/admin/dashboard/summary`
* 统计当前管理员可见范围内的指标：
```json
{
  "questionnaireCount": 1,
  "publishedVersionCount": 1,
  "activeActivityCount": 1,
  "totalActivityCount": 2,
  "validSheetCount": 2,
  "pendingOrFailedReportCount": 0,
  "recentActivities": [
    {
      "id": 1,
      "title": "2026秋季学期软件2542班心理适应普查",
      "status": "PUBLISHED",
      "participantCount": 3,
      "completedCount": 2,
      "completionRate": 66.67,
      "startTime": "2026-11-01 00:00:00",
      "endTime": "2027-12-31 23:59:59"
    }
  ]
}
```

### 2. 用户候选列表（用于活动勾选目标学生与授权管理员） `GET /api/admin/users`
* 查询参数：`role`（可选：`STUDENT` 或 `ADMIN`）
* 响应 `data`：`[{ "id": 4, "username": "20260001", "realName": "苏晴", "role": "STUDENT", "deptClass": "软件2542班" }]`

### 3. 问卷配置与版本管理 (REQ02 & REQ03)
* `GET /api/admin/questionnaires` — 问卷列表（含 `id, code, title, description, latestVersion, questionCount, updatedAt`）
* `GET /api/admin/questionnaires/{id}` — 读取问卷草稿完整配置（含 `questions` 数组与 `scoringRule` 对象，用于回读核验）
* `POST /api/admin/questionnaires` — 新建问卷草稿（完整校验题干、选项非空、选项编码唯一、分值数值合法、维度与区间边界合法；任一错误返回 400 并定位到第几题/哪个选项/哪个区间）
* `PUT /api/admin/questionnaires/{id}` — 更新问卷草稿（仅更新草稿表，绝不修改已发布的 `questionnaire_version`）
* `POST /api/admin/questionnaires/{id}/publish` — 发布新版本：
  * 请求体（可选 `{"ruleVersion": "R-2026.2"}`，不传则自动按 `R-v{versionNum}` 或草稿规则版本生成）
  * 将当前草稿题目与计分规则完整冻结写入 `questionnaire_version`，递增 `questionnaire.latest_version`，清理对应缓存。
  * 响应 `data`：新生成的 `QuestionnaireVersionVO`。
* `GET /api/admin/questionnaires/{id}/versions` — 查询某问卷历史已发布版本列表
* `GET /api/admin/questionnaire-versions` — 查询全部可选的已发布问卷版本列表（供创建活动下拉选择）
* `GET /api/admin/questionnaire-versions/{versionId}` — 查看指定不可变版本快照详情（支持 Redis 缓存命中与观测）

#### 问卷草稿请求/响应结构约定：
```json
{
  "code": "CAMPUS_PSYCH_2026",
  "title": "大学生心理适应与压力评估问卷",
  "description": "问卷说明",
  "scoringRule": {
    "ruleVersion": "R-2026.1",
    "formula": "SUM",
    "reverseMaxPlusMin": 5,
    "dimensions": [
      { "code": "STRESS", "name": "学习压力", "formula": "SUM" }
    ],
    "levels": [
      { "code": "GOOD", "name": "状态良好", "min": 6, "max": 10, "minInclusive": true, "maxInclusive": true, "advice": "建议文本" }
    ]
  },
  "questions": [
    {
      "questionNo": 1,
      "stem": "近期面对课程任务与考核时，我感到难以负荷。",
      "questionType": "SINGLE",
      "dimensionCode": "STRESS",
      "isReverse": 0,
      "required": 1,
      "options": [
        { "optCode": "A", "label": "从不", "score": 1, "sort": 1 },
        { "optCode": "B", "label": "偶尔", "score": 2, "sort": 2 }
      ]
    }
  ]
}
```

### 4. 评估活动管理 (REQ04)
* `GET /api/admin/activities` — 查询本人负责或被授权的活动列表
* `GET /api/admin/activities/{id}` — 查询活动详情（含绑定版本信息、名单学生列表、授权管理员ID列表、完成进度；校验管理范围权限，越权 403）
* `POST /api/admin/activities` — 创建活动（初始 `DRAFT`；必须指定存在的 `questionnaireVersionId`、`startTime < endTime`、`participantUserIds`、`authorizedAdminIds`）
* `PUT /api/admin/activities/{id}` — 编辑草稿态活动（仅 `DRAFT` 状态允许修改绑定版本与基础设置；已发布/已关闭拒绝修改核心绑定）
* `PUT /api/admin/activities/{id}/settings` — 调整已发布活动设置（**仅 `PUBLISHED` 状态**，安全子集，守住 REQ03/REQ08 红线）
  * 请求体（字段均可选，`null` 表示不改动）：`{ "title": "新标题", "endTime": "2027-12-31 23:59:59", "addParticipantUserIds": [7], "authorizedAdminIds": [2] }`
  * 允许：改标题（非空 ≤128）、**延长结束时间**（新 `endTime` 必须晚于当前 `endTime`）、**追加参与学生**（只增不减，合并去重）、整体替换授权管理员（`authorizedAdminIds` 非 null 时生效，空列表表示清空）。
  * 禁止：改问卷版本、改开始时间、移除任何已有名单学生、缩短结束时间、对 `DRAFT`/`CLOSED` 调用（返回 400）；越权管理员返回 403。
  * 响应 `data`：`ActivityDetailVO`；成功后立即失效活动缓存。
* `POST /api/admin/activities/{id}/publish` — 发布活动（严格校验：仅 `DRAFT -> PUBLISHED`；`questionnaireVersionId` 必须存在且为已发布快照；目标名单 `participantCount > 0`；否则 400；失效活动缓存）
* `POST /api/admin/activities/{id}/close` — 关闭活动（严格校验：仅 `PUBLISHED -> CLOSED`；保留已有答卷和报告；**立即失效 Redis 活动缓存**，确保关闭后绝不放行任何提交）

### 5. 结果统计与报告管理 (REQ08 & REQ10)
* `GET /api/admin/activities/{id}/statistics` — 活动统计看板数据（校验管理范围权限，越权 403）：
```json
{
  "activityId": 1,
  "activityTitle": "2026秋季学期软件2542班心理适应普查",
  "status": "PUBLISHED",
  "questionnaireVersionId": 1,
  "questionnaireTitle": "大学生心理适应与压力评估问卷（教学模拟卷）",
  "versionNum": 1,
  "ruleVersion": "R-2026.1",
  "participantCount": 3,
  "completedCount": 2,
  "completionRate": 66.67,
  "successReportCount": 2,
  "pendingReportCount": 0,
  "failedReportCount": 0,
  "levelDistribution": [
    { "levelCode": "GOOD", "levelName": "状态良好", "count": 1 },
    { "levelCode": "MILD", "levelName": "轻度压力", "count": 0 },
    { "levelCode": "MODERATE", "levelName": "中度困扰", "count": 1 },
    { "levelCode": "HIGH", "levelName": "重点关注", "count": 0 }
  ],
  "dimensionAverages": [
    { "dimensionCode": "STRESS", "dimensionName": "学习压力", "avgScore": 4.50 },
    { "dimensionCode": "MOOD", "dimensionName": "情绪状态", "avgScore": 3.50 },
    { "dimensionCode": "SLEEP", "dimensionName": "睡眠与作息", "avgScore": 5.00 }
  ],
  "participantDetails": [
    {
      "userId": 4,
      "username": "20260001",
      "realName": "苏晴（已交卷学生A）",
      "deptClass": "软件2542班",
      "submitted": true,
      "sheetId": 1,
      "submittedAt": "2026-11-23 10:15:00",
      "reportId": 1,
      "taskStatus": "SUCCESS",
      "totalScore": 9.00,
      "levelCode": "GOOD",
      "levelName": "状态良好",
      "retryCount": 0,
      "lastError": null
    }
  ]
}
```
* `GET /api/admin/reports/{reportId}` — 管理员查看授权活动下的单份报告详情（越权返回 403）
* `POST /api/admin/reports/{reportId}/retry` — 手动触发失败/待补偿报告任务的受控重试（若 `retryCount >= maxRetries` 可携带 `?reset=true` 或按受控上限返回提示）

### 6. 热点缓存与异步消息可观测接口 (REQ09 & REQ10 验收证据)
* `GET /api/admin/cache/metrics` — 查看 Redis/缓存命中数（`hitCount`）、回源数（`missCount`）、失效清理数（`evictCount`）、最近操作日志列表及当前缓存模式（Redis / 内存降级）。
* `DELETE /api/admin/cache/evict` — 手动失效指定或全部热点缓存。

### 7. 学生名册与 XLSX 导入/导出接口（批量能力增强，仅限 `ADMIN`）

#### 7.1 统一 XLSX 校验错误结构（HTTP 400）
当上传文件格式非法或任意行校验不通过时，数据库零写入，返回 HTTP 400：
```json
{
  "code": 400,
  "message": "XLSX校验未通过，共发现 2 处错误，请修正后重新上传",
  "data": {
    "errorCount": 2,
    "errors": [
      { "sheet": "题目列表", "row": 4, "column": "选项B分值", "message": "分值必须为0~100之间的数字" }
    ]
  }
}
```

#### 7.2 标准模板下载
* `GET /api/admin/templates/questionnaire.xlsx` — 下载标准题库/问卷 3-Sheet 导入模板（`题目列表`、`维度配置`、`计分与区间`，内置 6 题示例行）。
* `GET /api/admin/templates/students.xlsx` — 下载标准学生名册/活动名单单 Sheet 模板（`学生名单`：`学号`、`姓名`、`学院班级`、`手机号`，内置示例行）。

#### 7.3 题库 / 问卷草稿 XLSX 导入
* `POST /api/admin/questionnaires/import/parse`（`multipart/form-data`，参数 `file`）
  * 解析题库 `.xlsx`（不落库），用于编辑器回填题目或预览；仅含 `题目列表` Sheet 时只返回 `questions`，包含 `维度配置` 与 `计分与区间` 时同步返回完整 `scoringRule`。
  * 响应 `data`：
```json
{
  "questionCount": 6,
  "hasScoringRule": true,
  "questions": [ /* QuestionConfig[] */ ],
  "scoringRule": { /* ScoringRuleConfig | null */ }
}
```
* `POST /api/admin/questionnaires/import`（`multipart/form-data`）
  * 表单参数：`file`（必填 `.xlsx`）、`code`（必填）、`title`（必填）、`description`（选填）、`formula`（选填，默认 `SUM`）、`reverseMaxPlusMin`（选填整数，缺省按题目选项自动推算）、`ruleVersion`（选填，默认 `R-draft`）。
  * 全量解析并经过 `QuestionnaireValidator` 校验后，单事务创建问卷草稿（`latestVersion = 0`），响应 `data` 为 `QuestionnaireDetailVO`。

#### 7.4 学生名册管理与 XLSX 批量导入
* `GET /api/admin/students` — 查询学生名册（仅 `role = STUDENT`）
  * 查询参数：`keyword`（可选，按学号/姓名/学院班级模糊匹配）、`deptClass`（可选，精确/模糊筛选班级）
  * 响应 `data`：
```json
[
  {
    "id": 4,
    "username": "20260001",
    "realName": "苏晴（已交卷学生A）",
    "deptClass": "软件2542班",
    "phone": "13900000001",
    "status": 1,
    "createdAt": "2026-11-23 09:00:00",
    "participatedActivityCount": 2,
    "submittedSheetCount": 1
  }
]
```
* `POST /api/admin/students` — 新增单个学生账号
  * 请求体：`{ "username": "20260199", "realName": "张同学", "deptClass": "软件2542班", "phone": "13900000199", "password": "" }`（`password` 留空则自动取学号后 6 位，不足 6 位取完整学号；`username` 全表唯一，与已有账号冲突返回 400）。
  * 响应 `data`：`StudentRosterItemVO`。
* `PUT /api/admin/students/{id}` — 修改学生信息（仅允许操作 `role = STUDENT`，尝试修改 `ADMIN` 返回 400）
  * 请求体：`{ "realName": "新姓名", "deptClass": "新班级", "phone": "新手机号", "status": 1 }`（`status` 可选 `0` 禁用 / `1` 正常）。
  * 响应 `data`：更新后的 `StudentRosterItemVO`。
* `PUT /api/admin/students/{id}/status` — 启用 / 禁用学生账号（禁用后该学生持有的 Token 立即返回 401）
  * 请求体：`{ "status": 0 }`（仅允许 `0` 或 `1`；禁止操作 `ADMIN`）。
  * 响应 `data`：更新后的 `StudentRosterItemVO`。
* `POST /api/admin/students/{id}/reset-password` — 重置学生密码（仅允许操作 `STUDENT`）
  * 请求体（可选）：`{ "password": "" }`，留空则重置为**学号后 6 位**（不足 6 位取完整学号）；若自定义密码须长度 4~64。
  * 响应 `data`：`{ "id": 4, "username": "20260001", "passwordRuleHint": "密码已重置为学号后6位（不足6位取完整学号）" }`。
* `DELETE /api/admin/students/{id}` — 删除学生账号（数据完整性保护）
  * 仅允许删除 `role = STUDENT`（尝试删除 `ADMIN` 返回 400）。
  * **历史保护红线**：若该学生已有有效答卷记录（`answer_sheet > 0`），或已被纳入**已发布/已关闭**活动的目标名单，**拒绝物理删除并返回 HTTP 400**（提示改用「禁用账号」以保障历史报告与完成率分母完整）。
  * 若仅未交卷且仅存在于草稿活动名单或未参与任何活动，在单事务内清理其在草稿活动中的 `activity_participant` 记录并物理删除该学生，同时失效对应草稿活动缓存。
* `POST /api/admin/users/import-students`（`multipart/form-data`）
  * 参数：`file`（必填 `.xlsx`）、`updateExisting`（可选 boolean，默认 `false`）。
  * 新建学生账号规则：`role = STUDENT`，初始密码为 **学号（`username`）后 6 位**（不足 6 位取完整学号），BCrypt 加密存储。
  * 若学号已存在且为 `ADMIN` 角色 → 报错拒绝（400）；文件内学号重复 → 报错定位（400）；已有 `STUDENT` 幂等跳过（`updateExisting=true` 时仅更新非空资料，不重置密码）。
  * 响应 `data`：
```json
{
  "totalRows": 20,
  "createdCount": 18,
  "updatedCount": 0,
  "skippedCount": 2,
  "passwordRuleHint": "新建学生初始密码为学号后6位（不足6位取完整学号）"
}
```

#### 7.5 评估活动目标名单 XLSX 导入
* `POST /api/admin/activities/{id}/participants/import`（`multipart/form-data`）
  * 参数：`file`（必填 `.xlsx`）、`mode`（可选：`APPEND` 追加去重[默认] / `REPLACE` 全量替换）、`autoCreateMissing`（可选 boolean，默认 `true`，遇到不存在的学号自动创建 `STUDENT` 账号，初始密码取学号后 6 位，此时要求该行 `姓名` 非空）。
  * 权限与状态约束：校验管理员活动数据范围（越权 **403**）；仅 `DRAFT` 状态允许导入修改名单（`PUBLISHED`/`CLOSED` 返回 **400**）；同步清理活动缓存。
  * 响应 `data`：
```json
{
  "activityId": 2,
  "mode": "APPEND",
  "totalRows": 10,
  "createdUserCount": 6,
  "addedCount": 8,
  "alreadyInRosterCount": 2,
  "currentParticipantCount": 10,
  "passwordRuleHint": "自动新建学生的初始密码为学号后6位（不足6位取完整学号）"
}
```

#### 7.6 评估结果统计 XLSX 导出 (REQ08 增强)
* `GET /api/admin/activities/{id}/statistics/export.xlsx`
  * 仅限 `ADMIN`，严格校验 `AccessControlService.checkAdminActivityAccess`（未授权管理员 **403**）。
  * 返回 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` 二进制流，含 3 个工作表：
    1. `活动汇总与统计`：活动基本信息、完成数/完成率（`66.67%`）、报告三态数量、结果区间分布（仅含 `SUCCESS`）、各维度平均分。
    2. `参与及评估结果明细`：名单内所有学生的提交状态、报告状态、总分、动态展开的各维度得分列、结果区间、反馈建议、重试次数与最近异常。
    3. `作答选项明细`：全部有效答卷逐题选项编码与换算得分（`A(1.00)`），便于教学复核。

---

## 四、H5 参与端接口 (`/api/h5/**`，仅限 `STUDENT` 角色)

### 1. 我的活动列表 `GET /api/h5/activities`
* 返回当前登录学生在名单内的活动列表，统一由服务端计算展示状态与可填写资格：
  * `status`: `DRAFT` / `PUBLISHED` / `CLOSED`（通常展示 `PUBLISHED` 与 `CLOSED`，或标注状态）
  * `timeWindowStatus`: `NOT_STARTED` / `OPEN` / `EXPIRED`
  * `canSubmit`: `boolean`（当且仅当在名单内、`status == PUBLISHED`、`now` 在 `[startTime, endTime]` 内、且尚未提交有效答卷时为 `true`）
  * `statusText`: 与管理端一致的状态解释（如 `"开放填写中"`、`"已提交答卷"`、`"活动未开始"`、`"活动已截止"`、`"活动已关闭"`）
  * `submitted`: `boolean`，若已提交则附带 `sheetId` 与 `reportId`

### 2. 获取活动填写详情与动态问卷快照 `GET /api/h5/activities/{activityId}/detail`
* 校验学生是否在活动名单内（不在名单内返回 **403**；草稿未发布返回 **400**）。
* 从 Redis 缓存（区分版本键 `psych:qver:{versionId}` 与活动键 `psych:act:{activityId}`）或数据库读取该活动固定绑定的 `questionnaire_version` 快照。
* **脱敏注意**：返回给 H5 填写端的题目选项列表仅包含 `optCode, label, sort`，不向参与端暴露每项分值与正反向标记，防止诱导作答。
* 响应同时附带 `cacheSource`（`HIT` / `MISS_DB`）供验收观察 REQ09。

### 3. 提交答卷 `POST /api/h5/activities/{activityId}/submit` (REQ06 & REQ10)
* 请求体：
```json
{
  "answers": [
    { "questionNo": 1, "selectedOptCode": "A" },
    { "questionNo": 2, "selectedOptCode": "B" }
  ]
}
```
* 服务端严格核验顺序：
  1. 校验活动实时状态（直接核验最新状态，即使旧缓存未过期，若活动已关闭/未发布/时间窗外一律拒绝并返回 400）。
  2. 校验当前学生是否在 `activity_participant` 名单内（不在返回 403）。
  3. 校验该活动绑定的 `questionnaire_version` 快照：必答题是否全部作答、是否存在未知题号、重复题号、或不属于该题的非法 `selectedOptCode`（任一不合法返回 400，不写入任何数据）。
  4. 在同一 `@Transactional` 事务中插入 `answer_sheet`、全部 `answer_item`（任意明细异常整单回滚）以及初始 `assessment_report(task_status='PENDING')`。
  5. 若唯一索引 `uk_submit_once(activity_id, user_id)` 触发 `DuplicateKeyException`（或预检发现已提交），立即返回 **HTTP 409** 并附带 `{ sheetId, reportId, activityId }`。
  6. 注册事务提交后事件 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`，仅在事务真正提交成功后投递报告生成任务至 RabbitMQ（若投递异常，捕获并记录 `assessment_report.last_error`，保留有效答卷不回滚）。
* 正常响应 `data`：
```json
{
  "sheetId": 3,
  "reportId": 3,
  "activityId": 1,
  "taskStatus": "PENDING",
  "submittedAt": "2026-11-23 14:20:00"
}
```

### 4. 我的提交记录列表 `GET /api/h5/records`
* 仅返回 `user_id == currentUserId` 的有效答卷及关联报告状态列表（含 `sheetId, activityId, activityTitle, questionnaireTitle, versionNum, submittedAt, reportId, taskStatus, totalScore, levelName`）。

### 5. 查看我的单份答卷记录 `GET /api/h5/records/{sheetId}`
* 校验 `sheet.userId == currentUserId`，否则返回 **403**。返回作答时间、问卷版本、各题已选选项及关联 `reportId`、`taskStatus`。

### 6. 查看我的评估报告 `GET /api/h5/reports/{reportId}` (REQ07 & REQ10)
* 校验 `report.userId == currentUserId`，否则返回 **403**。
* 明确返回三态 `taskStatus`：
  * `PENDING`：报告正在异步生成中（前端友好展示等待态并轮询）
  * `SUCCESS`：展示总分 `totalScore`、各维度得分数组 `dimensions`、结果区间 `levelCode/levelName`、建议 `feedbackText`、关联 `sheetId`、`questionnaireVersionId`、`versionNum`、`ruleVersion`、`generatedAt`
  * `FAILED`：展示友好失败提示、`retryCount`、`maxRetries`（支持点击重试 `POST /api/h5/reports/{reportId}/retry`，未超上限时重新投递任务）

---

## 五、智能分析配置与 AI 洞察接口（提高任务，仅限 `ADMIN` 配置；洞察按数据范围授权）

> 安全红线：API Key 仅存库，**任何响应一律脱敏**（只回 `hasApiKey` 与 `apiKeyTail` 尾4位），绝不写入日志或代码仓库；PUT 时 `apiKey` 留空表示保留原值。AI 未启用/无 Key/调用失败时，所有洞察接口返回 `available=false` 与原因，前端回退静态文案，绝不阻断主流程。所有 AI 输出强制「支持性、非诊断」语气并附免责声明。

### 8.1 LLM 提供商管理（多提供商，`/api/admin/llm-providers`）
* `GET /api/admin/llm-providers` — 列表（脱敏）：`[{ id, name, baseUrl, hasApiKey, apiKeyTail, enabled, updatedAt }]`
* `POST /api/admin/llm-providers` — 新增：`{ name, baseUrl, apiKey, enabled }`（name/baseUrl 必填）
* `PUT /api/admin/llm-providers/{id}` — 修改：`{ name?, baseUrl?, apiKey?, enabled? }`（apiKey 留空保留原值）
* `DELETE /api/admin/llm-providers/{id}` — 删除（若正被报告/群体功能路由引用 → 400，提示先切换）
* `GET /api/admin/llm-providers/{id}/models` — **自动从上游拉取模型列表**：调 `{baseUrl}/models`（Bearer Key），返回 `{ ok, models: ["qwen-plus", ...], error }`；失败 ok=false + 友好 error（不含 Key）。

### 8.1b AI 功能路由配置（`/api/admin/llm-config`，单行）
* `GET /api/admin/llm-config` — 读取路由（脱敏，附提供商名）：
```json
{
  "reportInsightEnabled": true, "reportProviderId": 1, "reportProviderName": "阿里百炼",
  "reportModel": "qwen-plus", "reportTemperature": 0.70, "reportMaxTokens": 800,
  "cohortInsightEnabled": true, "cohortProviderId": 2, "cohortProviderName": "DeepSeek",
  "cohortModel": "deepseek-chat", "cohortTemperature": 0.70, "cohortMaxTokens": 800,
  "timeoutSeconds": 30, "updatedAt": "..."
}
```
* `PUT /api/admin/llm-config` — 保存路由：上述字段（providerId 可空=该功能不绑定）。启用某功能时要求对应 providerId 存在且 enabled、model 非空，否则 400。temperature 0~2、maxTokens 1~4096、timeoutSeconds 5~120。
* `POST /api/admin/llm-config/test` — 测试连接：`{ providerId, model }`，用该提供商发极小 prompt，返回 `{ ok, latencyMs, model, reply, error }`。

### 8.2 报告个性化解读（REQ07 提高）
* `GET /api/h5/reports/{reportId}/ai-interpretation` — 学生读取本人报告的 AI 解读（校验 `report.userId==currentUserId`，否则 403）
* `GET /api/admin/reports/{reportId}/ai-interpretation` — 管理员读取授权活动下报告的 AI 解读（越权 403）
  * 仅 `taskStatus=SUCCESS` 可生成；按维度分+区间构造 prompt（不含姓名等 PII），结果按 `report:{id}` 缓存。
  * 响应：
```json
{
  "available": true,
  "content": "……支持性、非诊断的个性化解读……",
  "model": "qwen-plus",
  "generatedAt": "2026-11-24 20:05:00",
  "disclaimer": "本解读由 AI 生成，仅供自我觉察参考，不构成医学或心理诊断；如有困扰请联系学校心理健康教育中心或专业机构。",
  "reason": null
}
```
  * 未启用/无 Key/失败：`available=false`、`content=null`、`reason="AI 解读未启用"` 等，前端回退静态 `feedbackText`。

### 8.3 活动群体洞察（REQ08 提高）
* `GET /api/admin/activities/{id}/ai-insight` — 管理员获取活动群体趋势洞察（校验活动数据范围，越权 403）
  * 基于统计聚合（完成率、区间分布、维度均分，**不含个体 PII**）构造 prompt，按 `activity:{id}` 缓存。
  * 响应结构同 8.2（`available/content/model/generatedAt/disclaimer/reason`）。
