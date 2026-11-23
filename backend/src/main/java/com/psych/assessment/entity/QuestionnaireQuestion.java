package com.psych.assessment.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionnaireQuestion {
    private Long id;
    private Long questionnaireId;
    private Integer questionNo;
    private String stem;
    private String questionType;
    private String dimensionCode;
    private Integer isReverse;
    private Integer required;
    private String optionsJson;
}
