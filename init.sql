-- 智慧心理健康评估系统 数据库初始化脚本 (MySQL 8.0+)
-- 说明：仅包含表结构定义；教学模拟账号与样例数据见 seed.sql

SET NAMES utf8mb4;

-- 1. 用户表 (REQ01: 区分角色 ADMIN / STUDENT)
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE COMMENT '学号/工号',
    password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt加密密码',
    real_name VARCHAR(64) NOT NULL,
    role VARCHAR(20) NOT NULL COMMENT 'ADMIN-管理人员, STUDENT-参与者',
    dept_class VARCHAR(128) COMMENT '学院/班级(个人资料维护)',
    phone VARCHAR(32),
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- 2. 问卷草稿主表 (REQ02: 供PC端日常编辑修改与回读)
CREATE TABLE IF NOT EXISTS questionnaire (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL UNIQUE COMMENT '问卷唯一编码',
    title VARCHAR(128) NOT NULL,
    description TEXT,
    scoring_rule_json JSON COMMENT '草稿态计分规则与区间配置(维度定义、SUM/AVG公式、结果区间)',
    latest_version INT NOT NULL DEFAULT 0 COMMENT '当前最新已发布版本号(0表示未发布)',
    creator_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='问卷编辑主表';

-- 3. 问卷草稿题目与选项表 (REQ02: 错误可精确校验到具体题目/选项/分值)
CREATE TABLE IF NOT EXISTS questionnaire_question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    questionnaire_id BIGINT NOT NULL,
    question_no INT NOT NULL COMMENT '题号/显示顺序',
    stem VARCHAR(500) NOT NULL COMMENT '题干',
    question_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE' COMMENT '基础为SINGLE单选，预留MULTI扩展',
    dimension_code VARCHAR(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '归属维度编码(如STRESS/MOOD/SLEEP/SOCIAL)',
    is_reverse TINYINT NOT NULL DEFAULT 0 COMMENT '是否反向计分标识(0正向 1反向)',
    required TINYINT NOT NULL DEFAULT 1 COMMENT '是否必答(1必答 0选答)',
    options_json JSON NOT NULL COMMENT '选项数组: [{"optCode":"A","label":"从不","score":1,"sort":1}, ...]',
    INDEX idx_q_sort (questionnaire_id, question_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='问卷编辑态题目表';

-- 4. 问卷与计分规则不可变版本快照表 (REQ03 & REQ07)
CREATE TABLE IF NOT EXISTS questionnaire_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    questionnaire_id BIGINT NOT NULL,
    version_num INT NOT NULL COMMENT '版本号: 1, 2, 3...',
    rule_version VARCHAR(32) NOT NULL COMMENT '规则版本标识，如 R-2026.1',
    title VARCHAR(128) NOT NULL,
    description TEXT,
    questions_snapshot JSON NOT NULL COMMENT '冻结的完整题目与选项分值结构',
    scoring_rule_snapshot JSON NOT NULL COMMENT '冻结的计分规则: 维度定义、总分公式(SUM/AVG)、区间定义',
    published_by BIGINT NOT NULL,
    published_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_q_ver (questionnaire_id, version_num)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='问卷及计分规则不可变版本表(只读快照)';

-- 5. 评估活动表 (REQ04: 绑定固定问卷版本ID，状态机 DRAFT -> PUBLISHED -> CLOSED)
CREATE TABLE IF NOT EXISTS assessment_activity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(128) NOT NULL,
    questionnaire_version_id BIGINT NOT NULL COMMENT '固定绑定发布时的questionnaire_version.id',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT-草稿, PUBLISHED-已发布, CLOSED-已关闭',
    start_time DATETIME NOT NULL COMMENT '开放开始时间',
    end_time DATETIME NOT NULL COMMENT '开放结束时间',
    owner_id BIGINT NOT NULL COMMENT '负责管理员ID(REQ01数据权限范围校验)',
    authorized_admin_ids JSON COMMENT '额外授权可查看的管理员ID列表',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评估活动表';

-- 6. 活动目标名单表 (REQ04 & REQ05 & REQ08: 参与资格核验 + 完成率分母)
CREATE TABLE IF NOT EXISTS activity_participant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL COMMENT '目标学生user_id',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_act_user (activity_id, user_id),
    INDEX idx_user_act (user_id, activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='活动参与名单表';

-- 7. 有效答卷主表 (REQ06: 唯一索引硬防并发重复提交，捕获冲突返回HTTP 409 + 原记录ID)
CREATE TABLE IF NOT EXISTS answer_sheet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    questionnaire_version_id BIGINT NOT NULL COMMENT '冗余当时作答的问卷版本ID，双重追溯',
    user_id BIGINT NOT NULL,
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_submit_once (activity_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='有效答卷主表';

-- 8. 答卷作答明细表 (REQ06: 与主表同事务原子写入，任一非法整单回滚)
CREATE TABLE IF NOT EXISTS answer_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sheet_id BIGINT NOT NULL,
    question_no INT NOT NULL,
    selected_opt_code VARCHAR(32) NOT NULL COMMENT '所选选项编码',
    raw_score DECIMAL(8,2) NOT NULL COMMENT '按当时快照与正反向规则锁定的该题得分',
    dimension_code VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_sheet_q (sheet_id, question_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='答卷作答明细表';

-- 9. 评估报告与异步任务表 (REQ07 & REQ08 & REQ10: 天然消费幂等 + 状态可查 + 受控重试)
CREATE TABLE IF NOT EXISTS assessment_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sheet_id BIGINT NOT NULL UNIQUE COMMENT '唯一绑定一份答卷，保证MQ重复消费天然幂等',
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    questionnaire_version_id BIGINT NOT NULL,
    rule_version VARCHAR(32) NOT NULL COMMENT '追溯计分规则版本',
    task_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING-待生成, SUCCESS-成功, FAILED-失败',
    total_score DECIMAL(8,2) COMMENT '总分(SUCCESS时写入)',
    dimension_scores_json JSON COMMENT '各维度得分明细',
    level_code VARCHAR(64) COMMENT '区间编码(REQ08统计分布仅统计task_status=SUCCESS记录)',
    level_name VARCHAR(64) COMMENT '结果区间名称',
    feedback_text TEXT COMMENT '评估反馈建议',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '当前已重试次数',
    max_retries INT NOT NULL DEFAULT 3 COMMENT '最大允许重试上限',
    last_error VARCHAR(500) COMMENT '最近一次MQ投递或计算失败原因',
    generated_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_act_status_level (activity_id, task_status, level_code),
    INDEX idx_user_report (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评估报告及异步任务状态表';

-- 10. LLM 提供商表（多行，每行独立 baseUrl/apiKey；Key 仅存库、响应脱敏、绝不入库 git）
CREATE TABLE IF NOT EXISTS llm_provider (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL COMMENT '提供商显示名',
    base_url VARCHAR(255) NOT NULL COMMENT 'OpenAI 兼容 Base URL',
    api_key VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'API Key（仅存库，响应脱敏）',
    enabled TINYINT NOT NULL DEFAULT 1,
    updated_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM 提供商表';

-- 11. AI 功能路由配置（单行；报告解读/群体洞察各自绑定提供商与模型）
CREATE TABLE IF NOT EXISTS llm_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    report_insight_enabled TINYINT NOT NULL DEFAULT 1,
    report_provider_id BIGINT,
    report_model VARCHAR(128) NOT NULL DEFAULT '',
    report_temperature DECIMAL(3,2) NOT NULL DEFAULT 0.70,
    report_max_tokens INT NOT NULL DEFAULT 800,
    report_fallback_provider_id BIGINT,
    report_fallback_model VARCHAR(128) NOT NULL DEFAULT '',
    cohort_insight_enabled TINYINT NOT NULL DEFAULT 1,
    cohort_provider_id BIGINT,
    cohort_model VARCHAR(128) NOT NULL DEFAULT '',
    cohort_temperature DECIMAL(3,2) NOT NULL DEFAULT 0.70,
    cohort_max_tokens INT NOT NULL DEFAULT 800,
    cohort_fallback_provider_id BIGINT,
    cohort_fallback_model VARCHAR(128) NOT NULL DEFAULT '',
    fallback_enabled TINYINT NOT NULL DEFAULT 0,
    timeout_seconds INT NOT NULL DEFAULT 30,
    updated_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 功能路由配置表';

-- 12. AI 洞察缓存表（按 cache_key 缓存，避免重复调用 LLM）
CREATE TABLE IF NOT EXISTS ai_insight_cache (
    cache_key VARCHAR(128) PRIMARY KEY COMMENT 'report:{id} 或 activity:{id}',
    content TEXT NOT NULL,
    model VARCHAR(128),
    generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 洞察缓存表';

INSERT INTO llm_config (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM llm_config WHERE id = 1);

-- 13. 管理操作审计日志
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT,
    operator_username VARCHAR(64),
    operator_real_name VARCHAR(64),
    action_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id BIGINT,
    summary VARCHAR(255),
    detail_json TEXT,
    ip_address VARCHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_operator (operator_id),
    INDEX idx_audit_resource (resource_type, resource_id),
    INDEX idx_audit_action (action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理操作审计日志';
