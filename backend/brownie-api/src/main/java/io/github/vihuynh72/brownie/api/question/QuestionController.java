package io.github.vihuynh72.brownie.api.question;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.question.Question;
import io.github.vihuynh72.brownie.core.question.QuestionCandidateOption;
import io.github.vihuynh72.brownie.core.question.QuestionService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/** Answers one previously detected question -- see {@code GenerationController#findQuestions} for how one is first surfaced. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/questions")
class QuestionController {

    private final QuestionService questionService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    QuestionController(
            QuestionService questionService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.questionService = questionService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping("/{questionId}/answer")
    QuestionResponse answer(
            @PathVariable long workspaceId,
            @PathVariable long questionId,
            @RequestBody AnswerQuestionRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        if (request.answerValue() == null || request.answerValue().isBlank()) {
            throw new QuestionRequestValidationException("answerValue must not be blank.");
        }
        Question answered = questionService.answer(workspaceId, userId, questionId, request.answerValue());
        return QuestionResponse.from(answered);
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated principal has no recorded identity for issuer/subject " + issuer + "/" + subject))
                .id();
    }

    record AnswerQuestionRequest(String answerValue) {
    }

    record QuestionCandidateOptionResponse(String value, List<Long> evidenceSpanIds) {
        static QuestionCandidateOptionResponse from(QuestionCandidateOption candidate) {
            return new QuestionCandidateOptionResponse(candidate.value(), candidate.evidenceSpanIds());
        }
    }

    record QuestionResponse(
            long id,
            String fieldId,
            String reason,
            List<QuestionCandidateOptionResponse> candidates,
            String status,
            String answerValue,
            OffsetDateTime createdAt) {
        static QuestionResponse from(Question question) {
            return new QuestionResponse(
                    question.id(),
                    question.fieldId(),
                    question.reason().name(),
                    question.candidates().stream().map(QuestionCandidateOptionResponse::from).toList(),
                    question.status().name(),
                    question.answerValue(),
                    question.createdAt());
        }
    }
}
