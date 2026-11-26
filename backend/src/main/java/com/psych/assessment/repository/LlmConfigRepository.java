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
 *
 * <p>备用路由字段（report_fallback_provider_id/report_fallback_model、
 * cohort_fallback_provider_id/cohort_fallback_model、fallback_enabled）属于后置扩展列：
 * 读取时经 {@link JdbcHelper#getLongOpt}/{@link JdbcHelper#getStringOpt}/{@link JdbcHelper#getIntOpt}
 * 在列缺失时安全回退为 null（降级默认关闭）；写入时按列是否存在动态选择语句，
 * 保证 DDL 尚未补列的老库也能正常运行。
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
            .reportFallbackProviderId(JdbcHelper.getLongOpt(rs, "report_fallback_provider_id"))
            .reportFallbackModel(JdbcHelper.getStringOpt(rs, "report_fallback_model"))
            .cohortFallbackProviderId(JdbcHelper.getLongOpt(rs, "cohort_fallback_provider_id"))
            .cohortFallbackModel(JdbcHelper.getStringOpt(rs, "cohort_fallback_model"))
            .fallbackEnabled(JdbcHelper.getIntOpt(rs, "fallback_enabled"))
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
        if (hasFallbackColumns()) {
            upsertWithFallback(cfg);
        } else {
            upsertMinimal(cfg);
        }
    }

    private void upsertMinimal(LlmConfig cfg) {
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

    private void upsertWithFallback(LlmConfig cfg) {
        int updated = jdbcTemplate.update(
                "UPDATE llm_config SET report_insight_enabled = ?, report_provider_id = ?, report_model = ?, report_temperature = ?, report_max_tokens = ?, cohort_insight_enabled = ?, cohort_provider_id = ?, cohort_model = ?, cohort_temperature = ?, cohort_max_tokens = ?, report_fallback_provider_id = ?, report_fallback_model = ?, cohort_fallback_provider_id = ?, cohort_fallback_model = ?, fallback_enabled = ?, timeout_seconds = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
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
                cfg.getReportFallbackProviderId(),
                cfg.getReportFallbackModel(),
                cfg.getCohortFallbackProviderId(),
                cfg.getCohortFallbackModel(),
                cfg.getFallbackEnabled(),
                cfg.getTimeoutSeconds(),
                cfg.getUpdatedBy(),
                SINGLETON_ID
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO llm_config (id, report_insight_enabled, report_provider_id, report_model, report_temperature, report_max_tokens, cohort_insight_enabled, cohort_provider_id, cohort_model, cohort_temperature, cohort_max_tokens, report_fallback_provider_id, report_fallback_model, cohort_fallback_provider_id, cohort_fallback_model, fallback_enabled, timeout_seconds, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
                    cfg.getReportFallbackProviderId(),
                    cfg.getReportFallbackModel(),
                    cfg.getCohortFallbackProviderId(),
                    cfg.getCohortFallbackModel(),
                    cfg.getFallbackEnabled(),
                    cfg.getTimeoutSeconds(),
                    cfg.getUpdatedBy()
            );
        }
    }

    /**
     * 探测备用路由列是否已存在（每次实时探测，避免 DDL 后置补列时缓存陈旧）。
     * 列缺失时 COUNT(列) 抛异常 → 视为不存在，降级为最小编写。
     */
    private boolean hasFallbackColumns() {
        try {
            jdbcTemplate.queryForObject("SELECT COUNT(fallback_enabled) FROM llm_config", Integer.class);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
