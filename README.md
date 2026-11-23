# 智慧心理健康评估系统

面向学校心理健康教育中心的问卷评估系统（教学实训项目，三周 72 学时）。前后端与 H5 端放在同一仓库，三端共用一套业务规则。

## 仓库结构

```text
backend/   Spring Boot 3 后端（Java 17）
pc-web/    Vue 3 + Vite 管理端
h5-app/    UniApp（Vue 3）H5 参与端
docs/      设计资料与项目案例文档
```

## 技术栈与端口

| 端 | 技术 | 端口 | 启动方式 |
| --- | --- | --- | --- |
| backend | Spring Boot 3.3 / Java 17 / Maven | 8080 | `mvn spring-boot:run` |
| pc-web | Vue 3 / Vite / Element Plus / Pinia / Axios | 5173 | `npm run dev` |
| h5-app | UniApp H5 / Vue 3 / Vite | 5174 | `npm run dev:h5` |

两个前端在开发环境均将 `/api` 代理到 `http://localhost:8080`，后端已配置 CORS。

## 快速开始

```bash
# 后端
cd backend
mvn spring-boot:run
# 健康检查：GET http://localhost:8080/api/health

# 管理端
cd pc-web
npm install
npm run dev

# H5 端
cd h5-app
npm install
npm run dev:h5
```

## 当前进度

已完成：

- 三端骨架、路由与页面占位（工作台、问卷、活动、统计 / 登录、活动、填写、记录、报告）
- 后端统一返回体 `Result`、业务异常、全局异常处理、参数校验、CORS
- 前端 Axios 与 uni.request 封装、Token 透传、401/403 提示
- 后端单元测试与三端构建验证

待实现（按 REQ 推进）：

- REQ01 登录鉴权与数据范围
- REQ02–REQ04 问卷、版本、活动与数据库
- REQ05–REQ08 填写、提交事务、计分报告与统计
- REQ09 Redis 缓存、REQ10 RabbitMQ 异步报告

> 骨架阶段不包含数据库、Redis、RabbitMQ 依赖与连接配置，实现对应需求时再加入。

## 功能需求（REQ）

REQ01 身份与个人信息 · REQ02 问卷配置 · REQ03 问卷发布与版本 · REQ04 活动管理 · REQ05 活动查询与填写 · REQ06 提交与个人记录 · REQ07 计分与报告 · REQ08 结果统计 · REQ09 热点缓存 · REQ10 异步报告

## 说明

数据、账号与问卷均为教学模拟内容，结果仅用于软件功能验证，不作医学诊断。交付包不包含真实密码、密钥和个人敏感数据。
