package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.detail.DetailLearning;
import io.github.vihuynh72.brownie.core.detail.DetailLearningRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The what-it-wrote record for a learn run, owner-scoped like {@code saved_detail}. */
@Repository
class JdbcDetailLearningRepository implements DetailLearningRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    JdbcDetailLearningRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public long record(long ownerUserId, long documentId, List<DetailLearning.WrittenKey> writes) {
        TenantContext.setCurrentUser(jdbcTemplate, ownerUserId);
        StringBuilder writesJson = new StringBuilder("[");
        for (int i = 0; i < writes.size(); i++) {
            DetailLearning.WrittenKey write = writes.get(i);
            if (i > 0) {
                writesJson.append(',');
            }
            writesJson.append("{\"key\":").append(json(write.key()))
                    .append(",\"previousValue\":").append(write.previousValue() == null ? "null" : json(write.previousValue()))
                    .append(",\"writtenValue\":").append(json(write.writtenValue()));
            writesJson.append('}');
        }
        writesJson.append(']');
        return jdbcTemplate.queryForObject(
                "INSERT INTO detail_learning (owner_user_id, document_id, writes) VALUES (?, ?, ?::jsonb) RETURNING id",
                Long.class, ownerUserId, documentId, writesJson.toString());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DetailLearning> find(long ownerUserId, long learningId) {
        TenantContext.setCurrentUser(jdbcTemplate, ownerUserId);
        List<DetailLearning> found = jdbcTemplate.query(
                "SELECT id, owner_user_id, document_id, writes, created_at FROM detail_learning WHERE owner_user_id = ? AND id = ?",
                (rs, rowNum) -> {
                    List<DetailLearning.WrittenKey> writes;
                    try {
                        writes = OBJECT_MAPPER.<List<java.util.Map<String, Object>>>readValue(
                                        rs.getString("writes"),
                                        OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class,
                                                OBJECT_MAPPER.getTypeFactory().constructMapType(java.util.Map.class, String.class, Object.class)))
                                .stream()
                                .map(write -> new DetailLearning.WrittenKey(
                                        (String) write.get("key"),
                                        (String) write.get("previousValue"),
                                        (String) write.get("writtenValue")))
                                .toList();
                    } catch (Exception e) {
                        throw new IllegalStateException("Could not read a learning's writes.", e);
                    }
                    return new DetailLearning(
                            rs.getLong("id"), rs.getLong("owner_user_id"), rs.getLong("document_id"),
                            writes, rs.getTimestamp("created_at").toInstant());
                },
                ownerUserId, learningId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

}
