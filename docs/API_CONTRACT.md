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
