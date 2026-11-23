package com.psych.assessment.repository;

import com.psych.assessment.entity.QuestionnaireQuestion;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class QuestionnaireQuestionRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<QuestionnaireQuestion> ROW_MAPPER = (rs, rowNum) -> QuestionnaireQuestion.builder()
            .id(rs.getLong("id"))
            .questionnaireId(rs.getLong("questionnaire_id"))
            .questionNo(rs.getInt("question_no"))
            .stem(rs.getString("stem"))
            .questionType(rs.getString("question_type"))
            .dimensionCode(rs.getString("dimension_code"))
            .isReverse(rs.getInt("is_reverse"))
            .required(rs.getInt("required"))
            .optionsJson(rs.getString("options_json"))
            .build();

    public List<QuestionnaireQuestion> findByQuestionnaireId(Long questionnaireId) {
        return jdbcTemplate.query(
                "SELECT * FROM questionnaire_question WHERE questionnaire_id = ? ORDER BY question_no ASC",
                ROW_MAPPER,
                questionnaireId
        );
    }

    public int countByQuestionnaireId(Long questionnaireId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM questionnaire_question WHERE questionnaire_id = ?",
                Integer.class,
                questionnaireId
        );
        return cnt == null ? 0 : cnt;
    }

    public void deleteByQuestionnaireId(Long questionnaireId) {
        jdbcTemplate.update("DELETE FROM questionnaire_question WHERE questionnaire_id = ?", questionnaireId);
    }

    public void insertAll(Long questionnaireId, List<QuestionnaireQuestion> questions) {
        for (QuestionnaireQuestion q : questions) {
            jdbcTemplate.update(
                    "INSERT INTO questionnaire_question (questionnaire_id, question_no, stem, question_type, dimension_code, is_reverse, required, options_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    questionnaireId,
                    q.getQuestionNo(),
                    q.getStem(),
                    q.getQuestionType(),
                    q.getDimensionCode(),
                    q.getIsReverse(),
                    q.getRequired(),
                    q.getOptionsJson()
            );
        }
    }
}
