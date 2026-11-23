package com.psych.assessment.repository;

import com.psych.assessment.entity.AnswerItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class AnswerItemRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<AnswerItem> ROW_MAPPER = (rs, rowNum) -> AnswerItem.builder()
            .id(rs.getLong("id"))
            .sheetId(rs.getLong("sheet_id"))
            .questionNo(rs.getInt("question_no"))
            .selectedOptCode(rs.getString("selected_opt_code"))
            .rawScore(rs.getBigDecimal("raw_score"))
            .dimensionCode(rs.getString("dimension_code"))
            .build();

    public void insertOne(AnswerItem item) {
        jdbcTemplate.update(
                "INSERT INTO answer_item (sheet_id, question_no, selected_opt_code, raw_score, dimension_code) VALUES (?, ?, ?, ?, ?)",
                item.getSheetId(),
                item.getQuestionNo(),
                item.getSelectedOptCode(),
                item.getRawScore(),
                item.getDimensionCode()
        );
    }

    public List<AnswerItem> findBySheetId(Long sheetId) {
        return jdbcTemplate.query(
                "SELECT * FROM answer_item WHERE sheet_id = ? ORDER BY question_no ASC",
                ROW_MAPPER,
                sheetId
        );
    }
}
