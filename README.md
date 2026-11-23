# 智慧心理健康评估系统

面向学校心理健康教育中心的 Web 全栈教学实训项目（三周 72 学时）。一个仓库包含 Spring Boot 后端、Vue 3 PC 管理端和 UniApp H5 参与端，覆盖 REQ01–REQ10：问卷草稿、不可变版本、活动名单/状态/时间窗、动态作答、事务与 409 防重、异步计分报告、ECharts 统计、热点缓存和受控重试。

> 所有账号、问卷、答卷和反馈建议均为**教学模拟数据**，只用于软件工程功能验证，不作医学或临床诊断。教师正式问卷与样例可后续通过配置化问卷草稿和新版本零业务代码接入，历史活动仍保留原快照。

## 1. 核心特性

- **历史可追溯**：草稿与已发布快照分表；活动、答卷、报告固定关联版本 ID 和规则版本。
- **参与受控**：JWT + ADMIN/STUDENT 分端 + 管理员 owner/授权范围 + 学生名单 + 状态机/时间窗。
- **一人一卷**：答卷主表、全部明细、PENDING 报告同事务落库；`uk_submit_once` 在并发下兜底，409 返回原记录 IDs。
- **配置化计分**：正反向转换、维度 SUM/AVG、总分 SUM/AVG/SUM_DIMENSIONS、开闭结果区间；H5 不暴露选项分值与反向标记。
- **可靠异步**：AFTER_COMMIT 后 RabbitMQ 投递（demo 为本地线程池）；独立事务记录投递错误；按 sheet 幂等、最多重试 3 次。
- **准确统计**：3 人 2 卷 = 66.67%，空名单 = 0.00%；完成率看有效答卷，PENDING/FAILED 不进入分数分布和维度均值。
- **双模式复现**：无中间件可运行的 H2 demo，以及 MySQL/Redis/RabbitMQ 完整 default 模式。

## 2. 仓库目录结构

```text
.
├─ backend/                         # Spring Boot 3.3.5 后端（Java 17 / Maven / JDBC）
│  ├─ src/main/java/com/psych/assessment/
│  │  ├─ controller/               # REST 接口
│  │  ├─ service/                  # 问卷、活动、提交、报告与统计
│  │  ├─ security/                 # JWT、拦截器、数据范围校验
│  │  ├─ scoring/                  # 配置校验与计分引擎
│  │  ├─ mq/                       # 事务后事件、RabbitMQ/本地异步、幂等重试
│  │  ├─ cache/                    # Redis 读穿与内存降级、指标观测
│  │  ├─ repository/               # 参数化 JDBC 数据访问
│  │  ├─ entity/  dto/  common/  config/
│  ├─ src/main/resources/
│  │  ├─ application.yml            # default 模式（MySQL/Redis/RabbitMQ）
│  │  ├─ application-demo.yml       # H2 + async + 自动种子
│  │  └─ sql/schema-h2.sql          # 与 MySQL 9 表对应的 H2 结构
│  └─ src/test/java/com/psych/assessment/
│     ├─ ScoringEngineSevenSamplesTest.java
│     ├─ SystemAcceptanceIntegrationTest.java
│     └─ HealthControllerTest.java
├─ pc-web/                          # Vue 3 + Vite + Element Plus + Pinia + ECharts
│  └─ src/views/                    # 登录、工作台、问卷、活动、统计
├─ h5-app/                          # UniApp（Vue 3）H5 参与端
│  └─ src/pages/                    # 登录、活动、填写、记录、报告
├─ docs/                            # 交付设计、测试、部署和接口文档
├─ init.sql                         # MySQL 8.0+：9 张核心表
├─ seed.sql                         # 7 账号、模拟卷 v1、3 人 2 卷种子数据
├─ docker-compose.yml               # MySQL 8.0 / Redis 7 / RabbitMQ 3.13
├─ .env.example                     # 环境变量模板（占位值，不是密钥）
└─ SYSTEM_BLUEPRINT.md.md           # 顶层需求与架构蓝图
```

## 3. 技术栈与服务端口

| 组件 | 当前实现 | 默认端口 / 地址 |
| --- | --- | --- |
| backend | Java 17、Spring Boot 3.3.5、Maven、Spring JDBC、JWT、BCrypt | `8080`，健康检查 `http://localhost:8080/api/health` |
| pc-web | Vue 3、Vite 6、Element Plus、Pinia、Axios、ECharts | `5173`，登录 `http://localhost:5173/login` |
| h5-app | UniApp、Vue 3、Vite 5 | `5174`，登录 `http://localhost:5174/#/pages/login/login` |
| MySQL | 8.0、utf8mb4、InnoDB | `3306`，默认库 `psych_assessment` |
| Redis | 7，热点快照/活动缓存；默认 1800s TTL | `6379` |
| RabbitMQ | 3.13 Management | AMQP `5672`，管理台 `15672` |

PC/H5 开发环境均把 `/api` 代理到 `http://localhost:8080`。后端 CORS 白名单包含本机 5173/5174。实际编译部署要求以 `backend/pom.xml` 的 Java 17 为准，早期蓝图中的 JDK 21 仅为初始建议。

## 4. 模式 A：`demo` 零外部中间件快速启动

适用于队友干净电脑和课堂演示。无需 Docker、MySQL、Redis、RabbitMQ；需要 **JDK 17、Maven、Node 18+、npm**。

### 4.1 终端 1：后端

```bash
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=demo"
```

后端自动完成：

1. 启动 H2 内存数据库（MySQL 兼容模式）并创建 9 张表。
2. 在空库注入 7 个模拟账号、1 份模拟卷 v1、2 个活动、3 人名单与 2 份预置答卷/报告。
3. 使用本地异步线程池消费报告任务；无 Redis 时降级内存缓存，工作台显示真实运行后端。
4. 开放 `http://localhost:8080/api/health`，预期 `data.status=UP`。

### 4.2 终端 2、3：双前端

```bash
# PC 管理端
cd pc-web
npm install
npm run dev

# 另开终端启动 H5 参与端
cd h5-app
npm install
npm run dev:h5
```

可执行 JAR 方式（构建时需要 Maven，构建完成后复制 JAR 运行只需 JDK 17）：

```bash
cd backend
mvn clean package
java -jar target/psych-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

**演示重置：** H2 数据不持久化。重启 demo 后端即恢复初始 3 人 2 卷 = 66.67%，适合多位成员依次答辩。

## 5. 模式 B：`default` 完整中间件启动

适用于真实 MySQL 持久化、Redis 缓存和 RabbitMQ 队列联通验证。

### 5.1 准备中间件和数据库

使用 Docker Compose（推荐）：

```bash
# 在仓库根目录
docker compose up -d
docker compose ps
```

首次初始化 MySQL 命名卷时，Compose 会按顺序挂载执行 `init.sql` 和 `seed.sql`。**后续启动不会自动重导 SQL**；使用旧 volume 时应自行确认库中已有 9 表和种子数据。

也可以使用本机 MySQL 8.0+ / Redis / RabbitMQ，在 MySQL 客户端中导入：

```sql
CREATE DATABASE IF NOT EXISTS psych_assessment
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE psych_assessment;
SOURCE ./init.sql;
SOURCE ./seed.sql;
```

### 5.2 配置后端环境变量

复制 `.env.example` 为 `.env` 并替换所有占位口令和随机 `JWT_SECRET`，不要提交 `.env`。

至少核对：`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST/PORT/PASSWORD`、`RABBIT_HOST/PORT/USERNAME/PASSWORD`、`JWT_SECRET`。完整模式默认 `MQ_TRANSPORT=rabbit`、`MQ_LISTENER_AUTO_STARTUP=true`，default 不自动 seed。

**根目录 `.env` 会被 Docker Compose 读取，但不会被 `mvn spring-boot:run` 自动加载。** 后端进程所需变量必须在同一终端显式设置：

```bash
# Bash 示例：请替换尖括号中的值
export DB_PASSWORD='<你的MySQL密码>'
export JWT_SECRET='<至少32字节的随机密钥>'
# 其他非默认变量同样通过 export 设置

# PowerShell 使用：
# $env:DB_PASSWORD = '<你的MySQL密码>'
# $env:JWT_SECRET = '<至少32字节的随机密钥>'
```

### 5.3 启动应用

```bash
cd backend
mvn spring-boot:run

# PC、H5 与模式 A 相同
cd ../pc-web && npm install && npm run dev
cd ../h5-app && npm install && npm run dev:h5
```

跨目录启动前端时，请在两个独立终端执行，不要等待前一条 `npm run dev` 结束。RabbitMQ 管理台位于 `http://localhost:15672`，拓扑为 `psych.report.exchange` / `psych.report.generate.queue` / routing key `psych.report.generate`。

详细的双模式复现、变量清单、故障排除与重置注意事项见 **[04 部署复现手册与答辩分工指南](docs/04_部署复现手册与答辩分工指南.md)**。

## 6. 模拟测试账号

统一测试口令：**`123456`**，仅用于本地教学，生产环境严禁沿用。

| 账号 | 角色 | 预置数据与测试用途 |
| --- | --- | --- |
| `admin01` | ADMIN | 活动 1、2 负责人；配置 v2、活动管理、统计、缓存/MQ 演示 |
| `admin02` | ADMIN | 被授权查看活动 1；验证非 owner 但可访问详情、统计和报告 |
| `admin03` | ADMIN | 未获活动 1、2 授权；验证 403 数据范围隔离 |
| `20260001` | STUDENT | 活动 1 已交卷，9.00 GOOD；本人记录、缓存和跨用户 403 |
| `20260002` | STUDENT | 活动 1 已交卷，17.00 MODERATE；维度报告与权限 |
| `20260003` | STUDENT | 活动 1 名单内待填写；第三人提交、完成率 100%、409 防重、报告三态 |
| `20260004` | STUDENT | 活动 1 名单外；空活动列表与名单外详情/提交 403 |

活动 1 初始为 3 人名单、2 份有效答卷，完成率 **66.67%**；`20260003` 六题全选 A 后，教学规则结果为 **12.00 MILD**，管理员统计更新为 **3/3 = 100.00%**。

## 7. 自动化验收测试

在 `backend` 目录执行：

```bash
cd backend
mvn test
```

单独运行计分或全链路集成测试：

```bash
mvn -Dtest=ScoringEngineSevenSamplesTest test
mvn -Dtest=SystemAcceptanceIntegrationTest test
```

测试组成：

| 测试类 | 执行次数 | 覆盖重点 |
| --- | ---: | --- |
| `ScoringEngineSevenSamplesTest` | 16 | 7 组边界样例、正反向、维度、AVG/SUM_DIMENSIONS、开闭区间、非法作答、seed 口令哈希 |
| `SystemAcceptanceIntegrationTest` | 22 | 401/403、非法草稿、快照隔离、状态/名单/时间窗、事务回滚、9 并发 409、统计、缓存、MQ 故障/幂等/重试 |
| `HealthControllerTest` | 1 | 健康检查 HTTP 200、统一响应和 UP |
| **合计** | **39** | — |

仓库中已有 Surefire 执行报告（集成测试报告对应 **2026-11-23 18:47**，Java 17）：**39 次执行，0 失败，0 错误，0 跳过**。证据位于 `backend/target/surefire-reports/` 下三份 `.txt` 与三份 `TEST-*.xml`。`target/` 被忽略属于正常现象；在新电脑执行 `mvn test` 会重新生成报告。

集成测试使用 **demo + H2 + 内存缓存 + 本地 async**，不把该结果夸大成真实 MySQL/Redis/RabbitMQ 压测或前端 E2E。完整中间件联通与双端人工演示步骤见部署手册。

## 8. 交付文档导航

| 文档 | 内容与用途 |
| --- | --- |
| [01 需求追踪与架构设计](docs/01_需求追踪与架构设计.md) | ADMIN/STUDENT 边界、核心 Mermaid 业务流程、REQ01–REQ10 到类/方法/页面/测试的矩阵、交付清单映射 |
| [02 数据模型与双端交互设计](docs/02_数据模型与双端交互设计.md) | 9 表 ER、完整数据字典、唯一约束与索引、无物理外键及快照隔离、事务/REQUIRES_NEW、PC/H5 交互、权限安全表 |
| [03 测试验收与缺陷回归记录](docs/03_测试验收与缺陷回归记录.md) | 7 组固定样例手算对照、8 大验收场景、39 次实际报告、4 个真实缺陷的复现/修复/回归闭环、复测命令 |
| [04 部署复现手册与答辩分工指南](docs/04_部署复现手册与答辩分工指南.md) | 双模式启动、环境变量配置、7 账号、5 分钟演示、MQ 故障脚本、6 人分工与答辩问答 |
| [API Contract](docs/API_CONTRACT.md) | 三端统一接口、Result、401/403/409 语义、请求响应契约 |
| [项目案例资料](docs/项目案例资料.docx) | 配套课程案例材料 |

## 9. 关键业务与运维接口

- 认证：`POST /api/auth/login`、`GET /api/auth/me`、`PUT /api/auth/profile`
- PC 管理：`/api/admin/questionnaires`、`/api/admin/questionnaire-versions`、`/api/admin/activities`、`/api/admin/activities/{id}/statistics`、`/api/admin/reports/{reportId}`
- H5 参与：`/api/h5/activities`、`/api/h5/activities/{id}/detail`、`/api/h5/activities/{id}/submit`、`/api/h5/records`、`/api/h5/reports/{reportId}`
- 观测运维：`GET /api/admin/cache/metrics`、`DELETE /api/admin/cache/evict`、`GET /api/admin/mq/status`、`POST /api/admin/mq/fault`

除 `/api/auth/login` 与 `/api/health` 外，接口统一使用：

```http
Authorization: Bearer <jwt_token>
```

详细请求/响应以 `docs/API_CONTRACT.md` 与当前 Controller/DTO 为准。

## 10. 安全与使用边界

- `seed.sql` 仅包含教学账号与模拟答案，统一弱口令不得用于生产。
- `.env`、本地 application 配置和密钥文件已被 `.gitignore` 排除；真实部署必须显式注入随机 JWT 密钥和非默认中间件凭据。
- H5 填写接口选项仅返回 `optCode/label/sort`，不返回 `score/isReverse/dimensionCode`；最终资格和计分均在服务端复核。
- 系统没有声明物理外键，依靠逻辑 ID、数据库唯一约束、服务层关系校验和事务共同维护一致性；不要绕过服务层直接无审计改库。
- 本地 demo 的内存降级和 async 是教学复现能力，不等同于生产高可用。真实部署还需补充 HTTPS、密钥托管、最小权限、限流审计和敏感数据治理。
