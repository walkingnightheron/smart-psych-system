package com.psych.assessment.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerItem {
    private Long id;
    private Long sheetId;
    private Integer questionNo;
    private String selectedOptCode;
    private BigDecimal rawScore;
    private String dimensionCode;
}
