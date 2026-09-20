package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.question.Question;
import io.github.vihuynh72.brownie.core.question.QuestionCandidateOption;
import io.github.vihuynh72.brownie.core.question.QuestionNotFoundException;
import io.github.vihuynh72.brownie.core.question.QuestionReason;
import io.github.vihuynh72.brownie.core.question.QuestionRepository;
import io.github.vihuynh72.brownie.core.question.QuestionStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** JDBC persistence for questions, the same {@code ?::jsonb}/RETURNING/{@link TenantContext} shape {@code JdbcCompilationRepository} already establishes. */
@Repository
class JdbcQuestionRepository implements QuestionRepository {

    private static final String COLUMNS =
            "id, workspace_id, document_id, generation_run_id, attempt_fencing_token, field_id, reason, candidates, status,"
                    + " answer_value, answered_by_user_id, answered_at, created_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcQuestionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Question create(
            long workspaceId,
            long userId,
            long documentId,
            Long generationRunId,
            Long attemptFencingToken,
            String fieldId,
            QuestionReason reason,
            List<QuestionCandidateOption> candidates) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO question (workspace_id, document_id, generation_run_id, attempt_fencing_token, field_id, reason, candidates)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                RETURNING\
                """ + " " + COLUMNS,
                this::mapQuestion,
                workspaceId,
                documentId,
                generationRunId,
                attemptFencingToken,
                fieldId,
                reason.name(),
                toJson(candidates));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Question> find(long workspaceId, long userId, long questionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM question WHERE workspace_id = ? AND id = ?", this::mapQuestion, workspaceId, questionId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Question> findOpenForDocument(long workspaceId, long userId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM question WHERE workspace_id = ? AND document_id = ? AND status = 'OPEN' ORDER BY created_at, id",
                this::mapQuestion,
                workspaceId,
                documentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Question> findAllForDocument(long workspaceId, long userId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM question WHERE workspace_id = ? AND document_id = ? ORDER BY created_at, id",
                this::mapQuestion,
                workspaceId,
                documentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Question> findAllForRun(long workspaceId, long userId, long generationRunId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM question WHERE workspace_id = ? AND generation_run_id = ? ORDER BY created_at, id",
                this::mapQuestion,
                workspaceId,
                generationRunId);
    }

    @Override
    @Transactional
    public Question answer(long workspaceId, long userId, long questionId, String answerValue) {
        // A question is reached by its own id, not through its document, so
        // the document's trash state is checked here: a document in the
        // trash accepts no change, and an answer is a change.
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        try {
            return jdbcTemplate.queryForObject(
                    """
                    UPDATE question
                    SET status = 'ANSWERED', answer_value = ?, answered_by_user_id = ?, answered_at = now()
                    WHERE workspace_id = ? AND id = ? AND status = 'OPEN'
                      AND EXISTS (
                          SELECT 1 FROM document d
                          WHERE d.workspace_id = question.workspace_id
                            AND d.id = question.document_id
                            AND d.trashed_at IS NULL)
                    RETURNING\
                    """ + " " + COLUMNS,
                    this::mapQuestion,
                    answerValue,
                    userId,
                    workspaceId,
                    questionId);
        } catch (EmptyResultDataAccessException e) {
            throw new QuestionNotFoundException(questionId);
        }
    }

    private Question mapQuestion(ResultSet rs, int rowNum) throws SQLException {
        Long answeredByUserId = (Long) rs.getObject("answered_by_user_id");
        return new Question(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("document_id"),
                (Long) rs.getObject("generation_run_id"),
                (Long) rs.getObject("attempt_fencing_token"),
                rs.getString("field_id"),
                QuestionReason.valueOf(rs.getString("reason")),
                fromJson(rs.getString("candidates")),
                QuestionStatus.valueOf(rs.getString("status")),
                rs.getString("answer_value"),
                answeredByUserId,
                rs.getObject("answered_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private String toJson(List<QuestionCandidateOption> candidates) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (QuestionCandidateOption candidate : candidates) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("value", candidate.value());
            entry.put("evidenceSpanIds", candidate.evidenceSpanIds());
            encoded.add(entry);
        }
        try {
            return objectMapper.writeValueAsString(encoded);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize question candidates.", e);
        }
    }

    private List<QuestionCandidateOption> fromJson(String json) {
        try {
            Object decoded = objectMapper.readValue(json, Object.class);
            if (!(decoded instanceof List<?> rawList)) {
                throw malformed("candidates must be a JSON array");
            }
            List<QuestionCandidateOption> candidates = new ArrayList<>();
            for (Object rawEntry : rawList) {
                Map<String, Object> entry = objectMap(rawEntry, "question candidate");
                if (!entry.keySet().equals(Set.of("value", "evidenceSpanIds"))) {
                    throw malformed("unexpected properties in a stored question candidate");
                }
                if (!(entry.get("value") instanceof String value)) {
                    throw malformed("expected question candidate property value to be a string");
                }
                if (!(entry.get("evidenceSpanIds") instanceof List<?> rawSpanIds)) {
                    throw malformed("expected question candidate property evidenceSpanIds to be an array");
                }
                List<Long> spanIds = new ArrayList<>();
                for (Object rawSpanId : rawSpanIds) {
                    if (!(rawSpanId instanceof Number number)) {
                        throw malformed("expected question candidate evidenceSpanIds entries to be integers");
                    }
                    spanIds.add(number.longValue());
                }
                candidates.add(new QuestionCandidateOption(value, spanIds));
            }
            return candidates;
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize stored question candidates.", e);
        }
    }

    private static Map<String, Object> objectMap(Object value, String description) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw malformed("expected " + description + " to be an object");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw malformed("expected " + description + " keys to be strings");
            }
            mapped.put(key, entry.getValue());
        }
        return mapped;
    }

    private static IllegalStateException malformed(String detail) {
        return new IllegalStateException("Stored question data is invalid: " + detail);
    }
}
