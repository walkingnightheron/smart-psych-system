package com.psych.assessment.repository;

import com.psych.assessment.entity.LlmConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * llm_config 全库单行路由配置（固定 id=1），upsert 兼容 MySQL 与 H2（先 UPDATE，无行则显式 INSERT id=1）。
 */
@Repository
@RequiredArgsConstructor
public class LlmConfigRepository {

    public static final long SINGLETON_ID = 1L;

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<LlmConfig> ROW_MAPPER = (rs, rowNum) -> LlmConfig.builder()
            .id(rs.getLong("id"))
            .reportInsightEnabled(rs.getInt("report_insight_enabled"))
            .reportProviderId((Long) rs.getObject("report_provider_id"))
            .reportModel(rs.getString("report_model"))
            .reportTemperature(rs.getBigDecimal("report_temperature"))
            .reportMaxTokens(rs.getInt("report_max_tokens"))
            .cohortInsightEnabled(rs.getInt("cohort_insight_enabled"))
            .cohortProviderId((Long) rs.getObject("cohort_provider_id"))
            .cohortModel(rs.getString("cohort_model"))
            .cohortTemperature(rs.getBigDecimal("cohort_temperature"))
            .cohortMaxTokens(rs.getInt("cohort_max_tokens"))
            .timeoutSeconds(rs.getInt("timeout_seconds"))
            .updatedBy((Long) rs.getObject("updated_by"))
            .createdAt(JdbcHelper.getLocalDateTime(rs, "created_at"))
            .updatedAt(JdbcHelper.getLocalDateTime(rs, "updated_at"))
            .build();

    public Optional<LlmConfig> findSingle() {
        List<LlmConfig> list = jdbcTemplate.query(
                "SELECT * FROM llm_config WHERE id = ?",
                ROW_MAPPER,
                SINGLETON_ID
        );
        return list.stream().findFirst();
    }

    public void upsert(LlmConfig cfg) {
        int updated = jdbcTemplate.update(
                "UPDATE llm_config SET report_insight_enabled = ?, report_provider_id = ?, report_model = ?, report_temperature = ?, report_max_tokens = ?, cohort_insight_enabled = ?, cohort_provider_id = ?, cohort_model = ?, cohort_temperature = ?, cohort_max_tokens = ?, timeout_seconds = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                cfg.getReportInsightEnabled(),
                cfg.getReportProviderId(),
                cfg.getReportModel(),
                cfg.getReportTemperature(),
                cfg.getReportMaxTokens(),
                cfg.getCohortInsightEnabled(),
                cfg.getCohortProviderId(),
                cfg.getCohortModel(),
                cfg.getCohortTemperature(),
                cfg.getCohortMaxTokens(),
                cfg.getTimeoutSeconds(),
                cfg.getUpdatedBy(),
                SINGLETON_ID
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO llm_config (id, report_insight_enabled, report_provider_id, report_model, report_temperature, report_max_tokens, cohort_insight_enabled, cohort_provider_id, cohort_model, cohort_temperature, cohort_max_tokens, timeout_seconds, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    SINGLETON_ID,
                    cfg.getReportInsightEnabled(),
                    cfg.getReportProviderId(),
                    cfg.getReportModel(),
                    cfg.getReportTemperature(),
                    cfg.getReportMaxTokens(),
                    cfg.getCohortInsightEnabled(),
                    cfg.getCohortProviderId(),
                    cfg.getCohortModel(),
                    cfg.getCohortTemperature(),
                    cfg.getCohortMaxTokens(),
                    cfg.getTimeoutSeconds(),
                    cfg.getUpdatedBy()
            );
        }
    }
}
