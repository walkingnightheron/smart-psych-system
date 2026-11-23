CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    real_name VARCHAR(64) NOT NULL,
    role VARCHAR(20) NOT NULL,
    dept_class VARCHAR(128),
    phone VARCHAR(32),
    status TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS questionnaire (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(128) NOT NULL,
    description CLOB,
    scoring_rule_json CLOB,
    latest_version INT NOT NULL DEFAULT 0,
    creator_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS questionnaire_question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    questionnaire_id BIGINT NOT NULL,
    question_no INT NOT NULL,
    stem VARCHAR(500) NOT NULL,
    question_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE',
    dimension_code VARCHAR(64) NOT NULL DEFAULT 'DEFAULT',
    is_reverse TINYINT NOT NULL DEFAULT 0,
    required TINYINT NOT NULL DEFAULT 1,
    options_json CLOB NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_q_sort ON questionnaire_question (questionnaire_id, question_no);

CREATE TABLE IF NOT EXISTS questionnaire_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    questionnaire_id BIGINT NOT NULL,
    version_num INT NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    description CLOB,
    questions_snapshot CLOB NOT NULL,
    scoring_rule_snapshot CLOB NOT NULL,
    published_by BIGINT NOT NULL,
    published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_q_ver UNIQUE (questionnaire_id, version_num)
);

CREATE TABLE IF NOT EXISTS assessment_activity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(128) NOT NULL,
    questionnaire_version_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    owner_id BIGINT NOT NULL,
    authorized_admin_ids CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_owner ON assessment_activity (owner_id);

CREATE TABLE IF NOT EXISTS activity_participant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_act_user UNIQUE (activity_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_user_act ON activity_participant (user_id, activity_id);

CREATE TABLE IF NOT EXISTS answer_sheet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    questionnaire_version_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_submit_once UNIQUE (activity_id, user_id)
);

CREATE TABLE IF NOT EXISTS answer_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sheet_id BIGINT NOT NULL,
    question_no INT NOT NULL,
    selected_opt_code VARCHAR(32) NOT NULL,
    raw_score DECIMAL(8,2) NOT NULL,
    dimension_code VARCHAR(64) NOT NULL,
    CONSTRAINT uk_sheet_q UNIQUE (sheet_id, question_no)
);

CREATE TABLE IF NOT EXISTS assessment_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sheet_id BIGINT NOT NULL UNIQUE,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    questionnaire_version_id BIGINT NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    task_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_score DECIMAL(8,2),
    dimension_scores_json CLOB,
    level_code VARCHAR(64),
    level_name VARCHAR(64),
    feedback_text CLOB,
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    last_error VARCHAR(500),
    generated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_act_status_level ON assessment_report (activity_id, task_status, level_code);
CREATE INDEX IF NOT EXISTS idx_user_report ON assessment_report (user_id);
