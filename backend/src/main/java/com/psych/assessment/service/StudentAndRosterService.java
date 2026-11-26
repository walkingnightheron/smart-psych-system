package com.psych.assessment.service;

import com.psych.assessment.cache.CacheService;
import com.psych.assessment.common.BizException;
import com.psych.assessment.dto.XlsxDtos.CreateStudentRequest;
import com.psych.assessment.dto.XlsxDtos.ParticipantImportResultVO;
import com.psych.assessment.dto.XlsxDtos.ResetPasswordResultVO;
import com.psych.assessment.dto.XlsxDtos.ResetStudentPasswordRequest;
import com.psych.assessment.dto.XlsxDtos.StudentImportResultVO;
import com.psych.assessment.dto.XlsxDtos.StudentRosterItemVO;
import com.psych.assessment.dto.XlsxDtos.UpdateStudentRequest;
import com.psych.assessment.dto.XlsxDtos.XlsxErrorPayload;
import com.psych.assessment.dto.XlsxDtos.XlsxRowError;
import com.psych.assessment.entity.AssessmentActivity;
import com.psych.assessment.entity.SysUser;
import com.psych.assessment.repository.ActivityParticipantRepository;
import com.psych.assessment.repository.AnswerSheetRepository;
import com.psych.assessment.repository.AssessmentActivityRepository;
import com.psych.assessment.repository.SysUserRepository;
import com.psych.assessment.security.AccessControlService;
import com.psych.assessment.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.psych.assessment.service.XlsxTemplateAndCodec.MAX_STUDENT_ROWS;
import static com.psych.assessment.service.XlsxTemplateAndCodec.SHEET_STUDENTS;
import static com.psych.assessment.service.XlsxTemplateAndCodec.buildHeaderIndex;
import static com.psych.assessment.service.XlsxTemplateAndCodec.findColumn;
import static com.psych.assessment.service.XlsxTemplateAndCodec.findSheet;
import static com.psych.assessment.service.XlsxTemplateAndCodec.isRowEmpty;
import static com.psych.assessment.service.XlsxTemplateAndCodec.textAt;

/**
 * 学生名册查询、学生批量导入与活动目标名单导入（契约 7.4 / 7.5）。
 *
 * <p>初始密码规则：新建 STUDENT 账号密码 = 学号后 6 位（不足 6 位取完整学号），BCrypt 加密存储；
 * 任何更新场景绝不重置密码。全量错误收集，任何错误零落库。
 */
@Service
@RequiredArgsConstructor
public class StudentAndRosterService {

    public static final String STUDENT_PASSWORD_HINT = "新建学生初始密码为学号后6位（不足6位取完整学号）";
    public static final String PARTICIPANT_PASSWORD_HINT = "自动新建学生的初始密码为学号后6位（不足6位取完整学号）";
    public static final String RESET_CUSTOM_PASSWORD_HINT = "密码已重置为自定义密码";
    public static final String RESET_DERIVED_PASSWORD_HINT = "密码已重置为学号后6位（不足6位取完整学号）";

    private static final java.util.regex.Pattern WHOLE_FLOAT_USERNAME =
            java.util.regex.Pattern.compile("^(\\d+)\\.0+$");

    private final SysUserRepository userRepository;
    private final ActivityParticipantRepository participantRepository;
    private final AnswerSheetRepository sheetRepository;
    private final AssessmentActivityRepository activityRepository;
    private final AccessControlService accessControlService;
    private final CacheService cacheService;
    private final PasswordEncoder passwordEncoder;
    private final XlsxTemplateAndCodec codec;
    private final AuditLogService auditLogService;

    /**
     * 初始密码规则（契约 7.4）：学号后 6 位，不足 6 位取完整学号。
     */
    public static String deriveInitialPassword(String username) {
        String u = username.trim();
        return u.length() > 6 ? u.substring(u.length() - 6) : u;
    }

    // ==================================================================
    // 7.4 学生名册查询
    // ==================================================================

    public List<StudentRosterItemVO> listStudents(String keyword, String deptClass) {
        List<SysUser> students = userRepository.searchStudents(keyword, deptClass);
        List<Long> ids = students.stream().map(SysUser::getId).collect(Collectors.toList());
        Map<Long, Long> activityCounts = participantRepository.countGroupByUserIds(ids);
        Map<Long, Long> sheetCounts = sheetRepository.countGroupByUserIds(ids);
        return students.stream()
                .map(u -> StudentRosterItemVO.builder()
                        .id(u.getId())
                        .username(u.getUsername())
                        .realName(u.getRealName())
                        .deptClass(u.getDeptClass())
                        .phone(u.getPhone())
                        .status(u.getStatus())
                        .createdAt(u.getCreatedAt())
                        .participatedActivityCount(activityCounts.getOrDefault(u.getId(), 0L))
                        .submittedSheetCount(sheetCounts.getOrDefault(u.getId(), 0L))
                        .build())
                .collect(Collectors.toList());
    }

    // ==================================================================
    // 7.4 单个学生账号管理（新增 / 修改 / 启停 / 重置密码 / 删除）
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public StudentRosterItemVO createStudent(CreateStudentRequest req) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        String username = trimToNull(req.getUsername());
        if (username == null) {
            throw new BizException(400, "学号[username]不能为空");
        }
        if (username.length() > 64) {
            throw new BizException(400, "学号[username]长度不能超过64个字符");
        }
        if (username.contains(".")) {
            throw new BizException(400, "学号[" + username + "]不能包含小数点，请填写整数或字母数字组合的学号");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new BizException(400, "学号[" + username + "]已存在");
        }
        String realName = trimToNull(req.getRealName());
        if (realName == null) {
            throw new BizException(400, "姓名[realName]不能为空");
        }
        if (realName.length() > 64) {
            throw new BizException(400, "姓名[realName]长度不能超过64个字符");
        }
        String deptClass = trimToNull(req.getDeptClass());
        if (deptClass != null && deptClass.length() > 128) {
            throw new BizException(400, "学院班级[deptClass]长度不能超过128个字符");
        }
        String phone = trimToNull(req.getPhone());
        if (phone != null && phone.length() > 32) {
            throw new BizException(400, "手机号[phone]长度不能超过32个字符");
        }
        String rawPassword = resolveStudentPassword(req.getPassword(), username);
        Long newId = userRepository.insert(SysUser.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .realName(realName)
                .role("STUDENT")
                .deptClass(deptClass)
                .phone(phone)
                .status(1)
                .build());
        SysUser created = userRepository.findById(newId)
                .orElseThrow(() -> new BizException(500, "学生账号创建失败: " + username));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("username", username);
        detail.put("realName", realName);
        detail.put("deptClass", deptClass);
        auditLogService.recordSuccess(AuditLogService.ACT_STUDENT_CREATE,
                AuditLogService.RES_STUDENT, newId, "新建学生[" + username + "]", detail);
        return buildRosterItem(created);
    }

    @Transactional(rollbackFor = Exception.class)
    public StudentRosterItemVO updateStudent(Long id, UpdateStudentRequest req) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        SysUser user = requireStudent(id, "仅允许修改学生账号");
        String realName = trimToNull(req.getRealName());
        if (realName == null) {
            throw new BizException(400, "姓名[realName]不能为空");
        }
        if (realName.length() > 64) {
            throw new BizException(400, "姓名[realName]长度不能超过64个字符");
        }
        String deptClass = trimToNull(req.getDeptClass());
        if (deptClass != null && deptClass.length() > 128) {
            throw new BizException(400, "学院班级[deptClass]长度不能超过128个字符");
        }
        String phone = trimToNull(req.getPhone());
        if (phone != null && phone.length() > 32) {
            throw new BizException(400, "手机号[phone]长度不能超过32个字符");
        }
        Integer status = req.getStatus();
        if (status != null && status != 0 && status != 1) {
            throw new BizException(400, "账号状态[status]仅允许0(禁用)或1(正常)");
        }
        userRepository.updateStudentFull(id, realName, deptClass, phone,
                status == null ? user.getStatus() : status);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("username", user.getUsername());
        detail.put("realName", realName);
        detail.put("deptClass", deptClass);
        auditLogService.recordSuccess(AuditLogService.ACT_STUDENT_UPDATE,
                AuditLogService.RES_STUDENT, id, "修改学生[" + user.getUsername() + "]", detail);
        return reloadRosterItem(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public StudentRosterItemVO updateStudentStatus(Long id, Integer status) {
        requireStudent(id, "仅允许操作学生账号");
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(400, "账号状态[status]仅允许0(禁用)或1(正常)");
        }
        userRepository.updateStatus(id, status);
        auditLogService.recordSuccess(AuditLogService.ACT_STUDENT_STATUS,
                AuditLogService.RES_STUDENT, id, (status == 1 ? "启用学生[" : "禁用学生[") + id + "]", null);
        return reloadRosterItem(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public ResetPasswordResultVO resetStudentPassword(Long id, ResetStudentPasswordRequest req) {
        SysUser user = requireStudent(id, "仅允许操作学生账号");
        String customPassword = trimToNull(req == null ? null : req.getPassword());
        String rawPassword = resolveStudentPassword(customPassword, user.getUsername());
        String hint = customPassword == null ? RESET_DERIVED_PASSWORD_HINT : RESET_CUSTOM_PASSWORD_HINT;
        userRepository.updatePasswordHash(id, passwordEncoder.encode(rawPassword));
        // 审计只记录用户名与重置策略，绝不记录明文/密文密码
        auditLogService.recordSuccess(AuditLogService.ACT_STUDENT_RESET_PASSWORD,
                AuditLogService.RES_STUDENT, id, "重置学生[" + user.getUsername() + "]密码", null);
        return ResetPasswordResultVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .passwordRuleHint(hint)
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteStudent(Long id) {
        requireStudent(id, "禁止删除管理员账号");
        long sheetCount = sheetRepository.countByUserId(id);
        if (sheetCount > 0) {
            throw new BizException(400, "该学生已存在 " + sheetCount
                    + " 份历史答卷/评估报告，为保障历史统计与复核完整性，禁止物理删除，请改为「禁用账号」");
        }
        long nonDraftActCount = participantRepository.countNonDraftActivitiesByUserId(id);
        if (nonDraftActCount > 0) {
            throw new BizException(400, "该学生已被纳入 " + nonDraftActCount
                    + " 个已发布或已关闭活动的目标名单，删除将破坏活动完成率分母，请改为「禁用账号」");
        }
        List<Long> draftActIds = participantRepository.findDraftActivityIdsByUserId(id);
        participantRepository.deleteByUserId(id);
        userRepository.deleteById(id);
        for (Long actId : draftActIds) {
            cacheService.evictKey(CacheService.activityKey(actId));
        }
        auditLogService.recordSuccess(AuditLogService.ACT_STUDENT_DELETE,
                AuditLogService.RES_STUDENT, id, "删除学生[" + id + "]", null);
    }

    private SysUser requireStudent(Long id, String nonStudentMessage) {
        SysUser user = userRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "学生账号不存在: id=" + id));
        if (!"STUDENT".equals(user.getRole())) {
            throw new BizException(400, nonStudentMessage);
        }
        return user;
    }

    private StudentRosterItemVO reloadRosterItem(Long id) {
        return buildRosterItem(userRepository.findById(id)
                .orElseThrow(() -> new BizException(500, "学生账号读取失败: id=" + id)));
    }

    private StudentRosterItemVO buildRosterItem(SysUser user) {
        Long userId = user.getId();
        long activityCount = participantRepository.countGroupByUserIds(List.of(userId))
                .getOrDefault(userId, 0L);
        long sheetCount = sheetRepository.countGroupByUserIds(List.of(userId))
                .getOrDefault(userId, 0L);
        return StudentRosterItemVO.builder()
                .id(userId)
                .username(user.getUsername())
                .realName(user.getRealName())
                .deptClass(user.getDeptClass())
                .phone(user.getPhone())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .participatedActivityCount(activityCount)
                .submittedSheetCount(sheetCount)
                .build();
    }

    /**
     * 自定义密码（4~64位）优先，留空回退为学号后 6 位（契约 7.4）。
     */
    private String resolveStudentPassword(String customPassword, String username) {
        String trimmed = trimToNull(customPassword);
        if (trimmed == null) {
            return deriveInitialPassword(username);
        }
        if (trimmed.length() < 4 || trimmed.length() > 64) {
            throw new BizException(400, "自定义密码长度须为4~64个字符");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ==================================================================
    // 7.4 学生名册 XLSX 批量导入
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public StudentImportResultVO importStudents(MultipartFile file, boolean updateExisting) {
        List<XlsxRowError> errors = new ArrayList<>();
        StudentSheetData data = readStudentSheet(file, errors);

        List<StudentRow> toCreate = new ArrayList<>();
        List<StudentRow> toUpdate = new ArrayList<>();
        List<StudentRow> toSkip = new ArrayList<>();

        for (StudentRow row : data.rows) {
            if (row.username == null) {
                continue; // 学号本身非法，已记录错误
            }
            SysUser existing = userRepository.findByUsername(row.username).orElse(null);
            if (existing != null && !"STUDENT".equals(existing.getRole())) {
                errors.add(error(data.sheetName, row.excelRow, "学号", "该账号为管理员角色，禁止作为学生导入"));
                continue;
            }
            if (existing == null) {
                if (row.realName.isEmpty()) {
                    errors.add(error(data.sheetName, row.excelRow, "姓名", "新建学生时姓名不能为空"));
                    continue;
                }
                toCreate.add(row);
            } else if (updateExisting && (!row.realName.isEmpty() || !row.deptClass.isEmpty() || !row.phone.isEmpty())) {
                toUpdate.add(row);
            } else {
                toSkip.add(row);
            }
        }

        if (!errors.isEmpty()) {
            auditLogService.recordFailure(AuditLogService.ACT_XLSX_IMPORT,
                    AuditLogService.RES_STUDENT, null, "学生名册XLSX导入校验失败(" + errors.size() + "处错误)", null);
            throw new BizException(
                    400,
                    "XLSX校验未通过，共发现 " + errors.size() + " 处错误，请修正后重新上传",
                    XlsxErrorPayload.of(errors));
        }

        for (StudentRow row : toCreate) {
            userRepository.insert(SysUser.builder()
                    .username(row.username)
                    .passwordHash(passwordEncoder.encode(deriveInitialPassword(row.username)))
                    .realName(row.realName)
                    .role("STUDENT")
                    .deptClass(row.deptClass.isEmpty() ? null : row.deptClass)
                    .phone(row.phone.isEmpty() ? null : row.phone)
                    .status(1)
                    .build());
        }
        for (StudentRow row : toUpdate) {
            // 仅更新非空资料，绝不重置密码
            userRepository.updateStudentSelective(
                    userRepository.findByUsername(row.username)
                            .orElseThrow(() -> new BizException(500, "学生账号读取失败: " + row.username))
                            .getId(),
                    row.realName,
                    row.deptClass,
                    row.phone);
        }

        StudentImportResultVO result = StudentImportResultVO.builder()
                .totalRows(data.rows.size())
                .createdCount(toCreate.size())
                .updatedCount(toUpdate.size())
                .skippedCount(toSkip.size())
                .passwordRuleHint(STUDENT_PASSWORD_HINT)
                .build();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("totalRows", result.getTotalRows());
        detail.put("createdCount", result.getCreatedCount());
        detail.put("updatedCount", result.getUpdatedCount());
        detail.put("skippedCount", result.getSkippedCount());
        auditLogService.recordSuccess(AuditLogService.ACT_XLSX_IMPORT,
                AuditLogService.RES_STUDENT, null, "学生名册XLSX导入", detail);
        return result;
    }

    // ==================================================================
    // 7.5 评估活动目标名单 XLSX 导入
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public ParticipantImportResultVO importActivityParticipants(
            Long activityId, MultipartFile file, String mode, boolean autoCreateMissing) {
        AssessmentActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new BizException(404, "评估活动不存在: id=" + activityId));
        Long adminId = AuthContext.requireUserId();
        // 数据范围越权 -> 403
        accessControlService.checkAdminActivityAccess(activity, adminId);

        if (!"DRAFT".equals(activity.getStatus())) {
            throw new BizException(400, "仅草稿(DRAFT)状态的活动允许导入目标名单，当前状态为: " + activity.getStatus());
        }
        String normalizedMode = mode == null || mode.isBlank() ? "APPEND" : mode.trim().toUpperCase();
        if (!"APPEND".equals(normalizedMode) && !"REPLACE".equals(normalizedMode)) {
            throw new BizException(400, "名单导入模式[mode]仅支持APPEND或REPLACE");
        }

        List<XlsxRowError> errors = new ArrayList<>();
        StudentSheetData data = readStudentSheet(file, errors);

        Map<String, Long> userIdByUsername = new LinkedHashMap<>();
        Map<String, StudentRow> rowsToCreate = new LinkedHashMap<>();
        for (StudentRow row : data.rows) {
            if (row.username == null) {
                continue;
            }
            SysUser existing = userRepository.findByUsername(row.username).orElse(null);
            if (existing != null) {
                if (!"STUDENT".equals(existing.getRole())) {
                    errors.add(error(data.sheetName, row.excelRow, "学号", "该账号为管理员角色，禁止导入活动目标名单"));
                    continue;
                }
                userIdByUsername.putIfAbsent(row.username, existing.getId());
            } else if (autoCreateMissing) {
                if (row.realName.isEmpty()) {
                    errors.add(error(data.sheetName, row.excelRow, "姓名", "自动创建学生账号时姓名不能为空"));
                    continue;
                }
                rowsToCreate.putIfAbsent(row.username, row);
            } else {
                errors.add(error(data.sheetName, row.excelRow, "学号",
                        "学号[" + row.username + "]在系统中不存在，请先导入学生名册或勾选自动创建"));
            }
        }

        if (!errors.isEmpty()) {
            auditLogService.recordFailure(AuditLogService.ACT_ACTIVITY_ROSTER_IMPORT,
                    AuditLogService.RES_ACTIVITY, activityId,
                    "活动名单XLSX导入校验失败(" + errors.size() + "处错误)", null);
            throw new BizException(
                    400,
                    "XLSX校验未通过，共发现 " + errors.size() + " 处错误，请修正后重新上传",
                    XlsxErrorPayload.of(errors));
        }

        // 1) 先创建缺失的学生账号（初始密码 = 学号后 6 位）
        int createdUserCount = 0;
        for (StudentRow row : rowsToCreate.values()) {
            Long newId = userRepository.insert(SysUser.builder()
                    .username(row.username)
                    .passwordHash(passwordEncoder.encode(deriveInitialPassword(row.username)))
                    .realName(row.realName)
                    .role("STUDENT")
                    .deptClass(row.deptClass.isEmpty() ? null : row.deptClass)
                    .phone(row.phone.isEmpty() ? null : row.phone)
                    .status(1)
                    .build());
            userIdByUsername.put(row.username, newId);
            createdUserCount++;
        }

        // 2) 按文件行序收集去重后的目标学生 ID
        LinkedHashSet<Long> fileUserIds = new LinkedHashSet<>();
        for (StudentRow row : data.rows) {
            Long uid = row.username == null ? null : userIdByUsername.get(row.username);
            if (uid != null) {
                fileUserIds.add(uid);
            }
        }

        // 3) APPEND 合并原有名单去重 / REPLACE 全量替换
        Set<Long> oldUserIds = new LinkedHashSet<>(participantRepository.findUserIdsByActivityId(activityId));
        int addedCount = 0;
        int alreadyInRosterCount = 0;
        for (Long uid : fileUserIds) {
            if (oldUserIds.contains(uid)) {
                alreadyInRosterCount++;
            } else {
                addedCount++;
            }
        }
        LinkedHashSet<Long> finalUserIds = new LinkedHashSet<>();
        if ("APPEND".equals(normalizedMode)) {
            finalUserIds.addAll(oldUserIds);
        }
        finalUserIds.addAll(fileUserIds);

        participantRepository.replaceParticipants(activityId, finalUserIds);
        // 名单变化立即失效活动缓存（REQ09）
        cacheService.evictKey(CacheService.activityKey(activityId));

        ParticipantImportResultVO result = ParticipantImportResultVO.builder()
                .activityId(activityId)
                .mode(normalizedMode)
                .totalRows(data.rows.size())
                .createdUserCount(createdUserCount)
                .addedCount(addedCount)
                .alreadyInRosterCount(alreadyInRosterCount)
                .currentParticipantCount(finalUserIds.size())
                .passwordRuleHint(PARTICIPANT_PASSWORD_HINT)
                .build();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("mode", normalizedMode);
        detail.put("addedCount", addedCount);
        detail.put("createdUserCount", createdUserCount);
        detail.put("currentParticipantCount", finalUserIds.size());
        auditLogService.recordSuccess(AuditLogService.ACT_ACTIVITY_ROSTER_IMPORT,
                AuditLogService.RES_ACTIVITY, activityId, "活动[" + activityId + "]目标名单XLSX导入", detail);
        return result;
    }

    // ==================================================================
    // 学生名单 Sheet 通用解析（学号/姓名/学院班级/手机号）
    // ==================================================================

    private StudentSheetData readStudentSheet(MultipartFile file, List<XlsxRowError> errors) {
        try (Workbook workbook = codec.validateAndOpen(file)) {
            DataFormatter formatter = new DataFormatter();
            Sheet sheet = findSheet(workbook, SHEET_STUDENTS);
            String sheetName = SHEET_STUDENTS;
            if (sheet == null) {
                if (workbook.getNumberOfSheets() == 0) {
                    throw new BizException(400, "文件中没有任何工作表");
                }
                sheet = workbook.getSheetAt(0);
                sheetName = sheet.getSheetName();
            }

            Map<String, Integer> header = buildHeaderIndex(sheet.getRow(0), formatter);
            Integer colUsername = findColumn(header, "学号");
            Integer colRealName = findColumn(header, "姓名");
            Integer colDeptClass = findColumn(header, "学院班级", "班级", "学院");
            Integer colPhone = findColumn(header, "手机号", "电话", "联系电话");
            if (colUsername == null) {
                throw new BizException(400, "工作表[" + sheetName + "]缺少核心表头列: 学号，请使用标准模板重新填写");
            }

            int lastCol = 0;
            for (Integer col : new Integer[]{colUsername, colRealName, colDeptClass, colPhone}) {
                if (col != null && col > lastCol) {
                    lastCol = col;
                }
            }

            List<StudentRow> rows = new ArrayList<>();
            Map<String, Integer> firstRowOfUsername = new HashMap<>();
            int dataRows = 0;

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (isRowEmpty(row, formatter, lastCol)) {
                    continue; // 空行自动跳过
                }
                dataRows++;
                if (dataRows > MAX_STUDENT_ROWS) {
                    throw new BizException(400, "名单行数超过上限(" + MAX_STUDENT_ROWS + "行)，请拆分文件后重新上传");
                }
                int excelRow = r + 1;
                StudentRow parsed = new StudentRow();
                parsed.excelRow = excelRow;

                String username = textAt(row, colUsername, formatter);
                if (username.isEmpty()) {
                    errors.add(error(sheetName, excelRow, "学号", "学号不能为空"));
                } else {
                    // Excel 数值列经公式（如 ÷11）计算后会产生小数，形如 2505224225.36364；
                    // 整数值（xxx.0）自动归一为整数，非整数小数直接拦截，避免脏学号入库。
                    java.util.regex.Matcher whole = WHOLE_FLOAT_USERNAME.matcher(username);
                    if (whole.matches()) {
                        username = whole.group(1);
                    }
                    if (username.contains(".")) {
                        errors.add(error(sheetName, excelRow, "学号",
                                "学号[" + username + "]包含小数点，学号应为整数或字母数字组合；"
                                        + "请将 Excel 中「学号」列设为文本格式后重新填写"));
                    } else if (username.length() > 64) {
                        errors.add(error(sheetName, excelRow, "学号", "学号长度不能超过64个字符"));
                    } else {
                        Integer firstRow = firstRowOfUsername.putIfAbsent(username, excelRow);
                        if (firstRow != null) {
                            errors.add(error(sheetName, excelRow, "学号",
                                    "学号[" + username + "]在文件内重复（首次出现于第" + firstRow + "行）"));
                        }
                        parsed.username = username;
                    }
                }

                parsed.realName = textAt(row, colRealName, formatter);
                if (parsed.realName.length() > 64) {
                    errors.add(error(sheetName, excelRow, "姓名", "姓名长度不能超过64个字符"));
                }
                parsed.deptClass = textAt(row, colDeptClass, formatter);
                if (parsed.deptClass.length() > 128) {
                    errors.add(error(sheetName, excelRow, "学院班级", "学院班级长度不能超过128个字符"));
                }
                parsed.phone = textAt(row, colPhone, formatter);
                if (parsed.phone.length() > 32) {
                    errors.add(error(sheetName, excelRow, "手机号", "手机号长度不能超过32个字符"));
                }
                rows.add(parsed);
            }

            if (rows.isEmpty()) {
                errors.add(error(sheetName, null, null, "未解析到任何有效数据行，请至少填写1名学生"));
            }
            return new StudentSheetData(sheetName, rows);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(400, "文件不是有效的 .xlsx 格式");
        }
    }

    private static XlsxRowError error(String sheet, Integer row, String column, String message) {
        return XlsxRowError.builder()
                .sheet(sheet)
                .row(row)
                .column(column)
                .message(message)
                .build();
    }

    private static class StudentRow {
        private int excelRow;
        private String username;
        private String realName = "";
        private String deptClass = "";
        private String phone = "";
    }

    private static class StudentSheetData {
        private final String sheetName;
        private final List<StudentRow> rows;

        StudentSheetData(String sheetName, List<StudentRow> rows) {
            this.sheetName = sheetName;
            this.rows = rows;
        }
    }
}
