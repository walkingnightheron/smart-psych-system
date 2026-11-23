-- 智慧心理健康评估系统 教学模拟演示数据脚本 (seed.sql)
-- 说明：所有账号、问卷、答卷均为教学模拟内容，不作医学诊断。
-- 模拟账号统一测试口令：123456 （BCrypt Hash 见下，生产环境严禁使用弱口令）

SET NAMES utf8mb4;

-- 1. 模拟用户（3名管理员验证本人负责/授权范围/越权拦截；4名学生验证三人名单66.67%及名单外拦截）
-- 口令统一为 123456: $2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi -> 我们在应用启动或SQL中使用标准BCrypt
INSERT INTO sys_user (id, username, password_hash, real_name, role, dept_class, phone, status) VALUES
(1, 'admin01', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '林知夏（中心主任）', 'ADMIN', '心理健康教育中心', '13800000001', 1),
(2, 'admin02', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '周怀瑾（授权辅导员）', 'ADMIN', '计算机与软件学院', '13800000002', 1),
(3, 'admin03', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '陈未授权（其他院系）', 'ADMIN', '机械工程学院', '13800000003', 1),
(4, '20260001', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '苏晴（已交卷学生A）', 'STUDENT', '软件2542班', '13900000001', 1),
(5, '20260002', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '陆远（已交卷学生B）', 'STUDENT', '软件2542班', '13900000002', 1),
(6, '20260003', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '许星河（名单内待填写C）', 'STUDENT', '软件2542班', '13900000003', 1),
(7, '20260004', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '赵子墨（名单外学生D）', 'STUDENT', '网络2501班', '13900000004', 1)
ON DUPLICATE KEY UPDATE real_name = VALUES(real_name);

-- 2. 问卷草稿主表（含最新草稿修订，演示 v1 已发布活动不受草稿/v2 修改影响）
INSERT INTO questionnaire (id, code, title, description, scoring_rule_json, latest_version, creator_id) VALUES
(1, 'CAMPUS_PSYCH_2026', '大学生心理适应与压力评估问卷（教学模拟卷）',
 '面向高校学生的教学模拟心理适应评估问卷，涵盖学习压力、情绪状态、睡眠与作息三个维度，共6道单选题（含2道反向计分题）。结果仅供软件工程实训功能验证，不作医学诊断。',
 JSON_OBJECT(
   'ruleVersion', 'R-2026.1',
   'formula', 'SUM',
   'reverseMaxPlusMin', 5,
   'dimensions', JSON_ARRAY(
     JSON_OBJECT('code', 'STRESS', 'name', '学习压力', 'formula', 'SUM'),
     JSON_OBJECT('code', 'MOOD', 'name', '情绪状态', 'formula', 'SUM'),
     JSON_OBJECT('code', 'SLEEP', 'name', '睡眠与作息', 'formula', 'SUM')
   ),
   'levels', JSON_ARRAY(
     JSON_OBJECT('code', 'GOOD', 'name', '状态良好', 'min', 6, 'max', 10, 'minInclusive', true, 'maxInclusive', true, 'advice', '您近期整体心理适应与作息状态良好，建议继续保持规律作息与积极社交。'),
     JSON_OBJECT('code', 'MILD', 'name', '轻度压力', 'min', 11, 'max', 15, 'minInclusive', true, 'maxInclusive', true, 'advice', '您近期存在一定学习或生活压力，建议合理规划学习节奏，适当进行户外运动与倾诉。'),
     JSON_OBJECT('code', 'MODERATE', 'name', '中度困扰', 'min', 16, 'max', 20, 'minInclusive', true, 'maxInclusive', true, 'advice', '您近期情绪或睡眠受到较明显困扰，建议主动参与校园心理沙龙或预约中心老师沟通。'),
     JSON_OBJECT('code', 'HIGH', 'name', '重点关注', 'min', 21, 'max', 24, 'minInclusive', true, 'maxInclusive', true, 'advice', '您近期心理负荷较高，建议尽快联系辅导员或学校心理健康教育中心获取一对一支持。')
   )
 ),
 1, 1)
ON DUPLICATE KEY UPDATE title = VALUES(title);

-- 3. 问卷草稿题目表（6道单选题：Q1-Q2 STRESS，Q3-Q4 MOOD(Q4反向)，Q5-Q6 SLEEP(Q6反向)）
DELETE FROM questionnaire_question WHERE questionnaire_id = 1;
INSERT INTO questionnaire_question (id, questionnaire_id, question_no, stem, question_type, dimension_code, is_reverse, required, options_json) VALUES
(1, 1, 1, '近期面对课程任务与考核时，我感到难以负荷。', 'SINGLE', 'STRESS', 0, 1,
 JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1), JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2), JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3), JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4))),
(2, 1, 2, '课余时间我仍会反复担忧未完成的学习事项，难以放松。', 'SINGLE', 'STRESS', 0, 1,
 JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1), JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2), JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3), JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4))),
(3, 1, 3, '近期我容易感到烦躁、低落或提不起精神。', 'SINGLE', 'MOOD', 0, 1,
 JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1), JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2), JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3), JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4))),
(4, 1, 4, '遇到困难时，我能够保持平和心态并找到积极的排解方式。（反向计分）', 'SINGLE', 'MOOD', 1, 1,
 JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1), JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2), JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3), JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4))),
(5, 1, 5, '近期我存在入睡困难、夜间易醒或早醒的情况。', 'SINGLE', 'SLEEP', 0, 1,
 JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1), JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2), JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3), JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4))),
(6, 1, 6, '早晨醒来后我感到精力充沛，能够投入新一天学习。（反向计分）', 'SINGLE', 'SLEEP', 1, 1,
 JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1), JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2), JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3), JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4)));

-- 4. 已发布版本快照表 v1
INSERT INTO questionnaire_version (id, questionnaire_id, version_num, rule_version, title, description, questions_snapshot, scoring_rule_snapshot, published_by, published_at) VALUES
(1, 1, 1, 'R-2026.1', '大学生心理适应与压力评估问卷（教学模拟卷）',
 '面向高校学生的教学模拟心理适应评估问卷，涵盖学习压力、情绪状态、睡眠与作息三个维度，共6道单选题（含2道反向计分题）。',
 JSON_ARRAY(
   JSON_OBJECT('questionNo',1,'stem','近期面对课程任务与考核时，我感到难以负荷。','questionType','SINGLE','dimensionCode','STRESS','isReverse',0,'required',1,'options',JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1),JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2),JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3),JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4))),
   JSON_OBJECT('questionNo',2,'stem','课余时间我仍会反复担忧未完成的学习事项，难以放松。','questionType','SINGLE','dimensionCode','STRESS','isReverse',0,'required',1,'options',JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1),JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2),JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3),JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4))),
   JSON_OBJECT('questionNo',3,'stem','近期我容易感到烦躁、低落或提不起精神。','questionType','SINGLE','dimensionCode','MOOD','isReverse',0,'required',1,'options',JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1),JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2),JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3),JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4))),
   JSON_OBJECT('questionNo',4,'stem','遇到困难时，我能够保持平和心态并找到积极的排解方式。（反向计分）','questionType','SINGLE','dimensionCode','MOOD','isReverse',1,'required',1,'options',JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1),JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2),JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3),JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4))),
   JSON_OBJECT('questionNo',5,'stem','近期我存在入睡困难、夜间易醒或早醒的情况。','questionType','SINGLE','dimensionCode','SLEEP','isReverse',0,'required',1,'options',JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1),JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2),JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3),JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4))),
   JSON_OBJECT('questionNo',6,'stem','早晨醒来后我感到精力充沛，能够投入新一天学习。（反向计分）','questionType','SINGLE','dimensionCode','SLEEP','isReverse',1,'required',1,'options',JSON_ARRAY(JSON_OBJECT('optCode','A','label','从不','score',1,'sort',1),JSON_OBJECT('optCode','B','label','偶尔','score',2,'sort',2),JSON_OBJECT('optCode','C','label','经常','score',3,'sort',3),JSON_OBJECT('optCode','D','label','总是','score',4,'sort',4)))
 ),
 JSON_OBJECT(
   'ruleVersion', 'R-2026.1',
   'formula', 'SUM',
   'reverseMaxPlusMin', 5,
   'dimensions', JSON_ARRAY(
     JSON_OBJECT('code', 'STRESS', 'name', '学习压力', 'formula', 'SUM'),
     JSON_OBJECT('code', 'MOOD', 'name', '情绪状态', 'formula', 'SUM'),
     JSON_OBJECT('code', 'SLEEP', 'name', '睡眠与作息', 'formula', 'SUM')
   ),
   'levels', JSON_ARRAY(
     JSON_OBJECT('code', 'GOOD', 'name', '状态良好', 'min', 6, 'max', 10, 'minInclusive', true, 'maxInclusive', true, 'advice', '您近期整体心理适应与作息状态良好，建议继续保持规律作息与积极社交。'),
     JSON_OBJECT('code', 'MILD', 'name', '轻度压力', 'min', 11, 'max', 15, 'minInclusive', true, 'maxInclusive', true, 'advice', '您近期存在一定学习或生活压力，建议合理规划学习节奏，适当进行户外运动与倾诉。'),
     JSON_OBJECT('code', 'MODERATE', 'name', '中度困扰', 'min', 16, 'max', 20, 'minInclusive', true, 'maxInclusive', true, 'advice', '您近期情绪或睡眠受到较明显困扰，建议主动参与校园心理沙龙或预约中心老师沟通。'),
     JSON_OBJECT('code', 'HIGH', 'name', '重点关注', 'min', 21, 'max', 24, 'minInclusive', true, 'maxInclusive', true, 'advice', '您近期心理负荷较高，建议尽快联系辅导员或学校心理健康教育中心获取一对一支持。')
   )
 ),
 1, '2026-11-23 09:00:00')
ON DUPLICATE KEY UPDATE title = VALUES(title);

-- 5. 评估活动表（活动1：已发布、开放时间窗内、三人名单预置两份答卷=66.67%；活动2：草稿态演示状态流转）
INSERT INTO assessment_activity (id, title, questionnaire_version_id, status, start_time, end_time, owner_id, authorized_admin_ids) VALUES
(1, '2026秋季学期软件2542班心理适应普查', 1, 'PUBLISHED', '2026-11-01 00:00:00', '2027-12-31 23:59:59', 1, JSON_ARRAY(2)),
(2, '2026冬季期末考前压力摸底（草稿待发布）', 1, 'DRAFT', '2026-12-01 00:00:00', '2026-12-31 23:59:59', 1, JSON_ARRAY())
ON DUPLICATE KEY UPDATE title = VALUES(title);

-- 6. 活动参与名单（活动1共3名学生：user_id = 4, 5, 6；user_id = 7 不在名单内）
INSERT INTO activity_participant (id, activity_id, user_id) VALUES
(1, 1, 4),
(2, 1, 5),
(3, 1, 6),
(4, 2, 4),
(5, 2, 6)
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id);

-- 7. 预置两份有效答卷（REQ08 核验：三人名单、两份有效答卷 -> 完成数2，完成率66.67%）
-- 学生4 (苏晴)：Q1=A(1), Q2=B(2), Q3=A(1), Q4=D(反向:5-4=1), Q5=B(2), Q6=C(反向:5-3=2) => STRESS=3, MOOD=2, SLEEP=4, 总分=9 => GOOD (状态良好)
-- 学生5 (陆远)：Q1=C(3), Q2=C(3), Q3=B(2), Q4=B(反向:5-2=3), Q5=C(3), Q6=B(反向:5-2=3) => STRESS=6, MOOD=5, SLEEP=6, 总分=17 => MODERATE (中度困扰)
INSERT INTO answer_sheet (id, activity_id, questionnaire_version_id, user_id, submitted_at) VALUES
(1, 1, 1, 4, '2026-11-23 10:15:00'),
(2, 1, 1, 5, '2026-11-23 10:30:00')
ON DUPLICATE KEY UPDATE submitted_at = VALUES(submitted_at);

DELETE FROM answer_item WHERE sheet_id IN (1, 2);
INSERT INTO answer_item (sheet_id, question_no, selected_opt_code, raw_score, dimension_code) VALUES
(1, 1, 'A', 1.00, 'STRESS'),
(1, 2, 'B', 2.00, 'STRESS'),
(1, 3, 'A', 1.00, 'MOOD'),
(1, 4, 'D', 1.00, 'MOOD'),
(1, 5, 'B', 2.00, 'SLEEP'),
(1, 6, 'C', 2.00, 'SLEEP'),
(2, 1, 'C', 3.00, 'STRESS'),
(2, 2, 'C', 3.00, 'STRESS'),
(2, 3, 'B', 2.00, 'MOOD'),
(2, 4, 'B', 3.00, 'MOOD'),
(2, 5, 'C', 3.00, 'SLEEP'),
(2, 6, 'B', 3.00, 'SLEEP');

INSERT INTO assessment_report (id, sheet_id, activity_id, user_id, questionnaire_version_id, rule_version, task_status, total_score, dimension_scores_json, level_code, level_name, feedback_text, retry_count, max_retries, generated_at) VALUES
(1, 1, 1, 4, 1, 'R-2026.1', 'SUCCESS', 9.00, JSON_OBJECT('STRESS', 3.00, 'MOOD', 2.00, 'SLEEP', 4.00), 'GOOD', '状态良好', '您近期整体心理适应与作息状态良好，建议继续保持规律作息与积极社交。', 0, 3, '2026-11-23 10:15:02'),
(2, 2, 1, 5, 1, 'R-2026.1', 'SUCCESS', 17.00, JSON_OBJECT('STRESS', 6.00, 'MOOD', 5.00, 'SLEEP', 6.00), 'MODERATE', '中度困扰', '您近期情绪或睡眠受到较明显困扰，建议主动参与校园心理沙龙或预约中心老师沟通。', 0, 3, '2026-11-23 10:30:02')
ON DUPLICATE KEY UPDATE total_score = VALUES(total_score), task_status = VALUES(task_status);
