package com.psych.assessment.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class TimeStatusHelper {

    public static final DateTimeFormatter STANDARD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeStatusHelper() {
    }

    public static String computeTimeWindowStatus(LocalDateTime startTime, LocalDateTime endTime, LocalDateTime now) {
        if (startTime != null && now.isBefore(startTime)) {
            return "NOT_STARTED";
        }
        if (endTime != null && now.isAfter(endTime)) {
            return "EXPIRED";
        }
        return "OPEN";
    }

    public static String computeActivityStatusText(String status, LocalDateTime startTime, LocalDateTime endTime, LocalDateTime now) {
        if ("DRAFT".equals(status)) {
            return "草稿待发布";
        }
        if ("CLOSED".equals(status)) {
            return "活动已关闭";
        }
        String tw = computeTimeWindowStatus(startTime, endTime, now);
        if ("NOT_STARTED".equals(tw)) {
            return "活动未开始";
        }
        if ("EXPIRED".equals(tw)) {
            return "活动已截止";
        }
        return "开放填写中";
    }

    public static String computeStudentStatusText(
            String status,
            LocalDateTime startTime,
            LocalDateTime endTime,
            boolean submitted,
            LocalDateTime now) {
        if (submitted) {
            return "已提交答卷";
        }
        return computeActivityStatusText(status, startTime, endTime, now);
    }

    public static boolean canStudentSubmit(
            String status,
            LocalDateTime startTime,
            LocalDateTime endTime,
            boolean submitted,
            LocalDateTime now) {
        if (submitted) {
            return false;
        }
        if (!"PUBLISHED".equals(status)) {
            return false;
        }
        return "OPEN".equals(computeTimeWindowStatus(startTime, endTime, now));
    }

    public static void validateActivityOpenForSubmit(
            String status,
            LocalDateTime startTime,
            LocalDateTime endTime,
            LocalDateTime now) {
        if ("DRAFT".equals(status)) {
            throw new BizException(400, "活动尚未发布，暂不可参与作答");
        }
        if ("CLOSED".equals(status)) {
            throw new BizException(400, "活动已关闭，停止接收答卷");
        }
        if (!"PUBLISHED".equals(status)) {
            throw new BizException(400, "活动当前状态不可提交: " + status);
        }
        String tw = computeTimeWindowStatus(startTime, endTime, now);
        if ("NOT_STARTED".equals(tw)) {
            throw new BizException(400, "活动未开始，开放时间为 " + format(startTime));
        }
        if ("EXPIRED".equals(tw)) {
            throw new BizException(400, "活动已截止，截止时间为 " + format(endTime));
        }
    }

    public static String format(LocalDateTime dt) {
        return dt == null ? null : dt.format(STANDARD_FORMATTER);
    }
}
