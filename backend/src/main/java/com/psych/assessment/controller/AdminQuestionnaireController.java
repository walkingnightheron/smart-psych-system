package com.psych.assessment.controller;

import com.psych.assessment.common.Result;
import com.psych.assessment.dto.QuestionnaireDtos.PublishVersionRequest;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireDetailVO;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireListItemVO;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireUpsertRequest;
import com.psych.assessment.dto.QuestionnaireDtos.QuestionnaireVersionVO;
import com.psych.assessment.service.QuestionnaireService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminQuestionnaireController {

    private final QuestionnaireService questionnaireService;

    @GetMapping("/questionnaires")
    public Result<List<QuestionnaireListItemVO>> listQuestionnaires() {
        return Result.ok(questionnaireService.listQuestionnaires());
    }

    @GetMapping("/questionnaires/{id}")
    public Result<QuestionnaireDetailVO> getQuestionnaire(@PathVariable("id") Long id) {
        return Result.ok(questionnaireService.getQuestionnaireDraft(id));
    }

    @PostMapping("/questionnaires")
    public Result<QuestionnaireDetailVO> createQuestionnaire(@RequestBody QuestionnaireUpsertRequest request) {
        return Result.ok(questionnaireService.createQuestionnaire(request));
    }

    @PutMapping("/questionnaires/{id}")
    public Result<QuestionnaireDetailVO> updateQuestionnaire(
            @PathVariable("id") Long id,
            @RequestBody QuestionnaireUpsertRequest request) {
        return Result.ok(questionnaireService.updateQuestionnaireDraft(id, request));
    }

    @PostMapping("/questionnaires/{id}/publish")
    public Result<QuestionnaireVersionVO> publishVersion(
            @PathVariable("id") Long id,
            @RequestBody(required = false) PublishVersionRequest request) {
        return Result.ok(questionnaireService.publishVersion(id, request));
    }

    @GetMapping("/questionnaires/{id}/versions")
    public Result<List<QuestionnaireVersionVO>> listQuestionnaireVersions(@PathVariable("id") Long id) {
        return Result.ok(questionnaireService.listVersionsByQuestionnaire(id));
    }

    @GetMapping("/questionnaire-versions")
    public Result<List<QuestionnaireVersionVO>> listAllPublishedVersions() {
        return Result.ok(questionnaireService.listAllPublishedVersions());
    }

    @GetMapping("/questionnaire-versions/{versionId}")
    public Result<QuestionnaireVersionVO> getVersionSnapshot(@PathVariable("versionId") Long versionId) {
        return Result.ok(questionnaireService.getVersionSnapshotCached(versionId));
    }
}
