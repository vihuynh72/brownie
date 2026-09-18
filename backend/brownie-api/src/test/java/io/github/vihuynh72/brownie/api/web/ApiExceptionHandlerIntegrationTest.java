package io.github.vihuynh72.brownie.api.web;

import io.github.vihuynh72.brownie.core.job.InvalidJobTransitionException;
import io.github.vihuynh72.brownie.core.job.JobNotFoundException;
import io.github.vihuynh72.brownie.core.job.JobState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Proves the actual, deployed error-response contract end to end, through
 * the real filter chain and the real {@link ApiExceptionHandler}, for every
 * case this module can currently produce. {@link ProbeController} exists
 * only in this test's own Spring context -- it is not shipped -- purely to
 * give an unmapped route, an unexpected exception, and a failed validation
 * something real to happen against; nothing about the exception-handling
 * contract itself is test-only. Every probe is made as a signed-in caller,
 * because every route that is not explicitly public requires a session and
 * an anonymous request would be answered with a plain 401 before any
 * handler ran. Runs against the disposable in-memory H2 database from the
 * {@code test} profile block in {@code application.yml} -- needed for the
 * JDBC repositories to wire up, though this test never touches a real
 * schema -- with Flyway excluded, since the migrations are
 * Postgres-specific SQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class ApiExceptionHandlerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void unmappedRouteReturnsEnrichedNotFound() throws Exception {
        MvcResult response = probe("/does-not-exist", null);
        Map<String, Object> body = body(response);

        assertThat(response.getResponse().getStatus()).isEqualTo(404);
        assertThat(body).containsEntry("code", "NOT_FOUND");
        assertThat(body.get("correlationId")).isNotNull();
        assertThat(response.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME)).isNotNull();
    }

    @Test
    void callerSuppliedCorrelationIdIsEchoedIntoTheErrorBody() throws Exception {
        MvcResult response = probe("/does-not-exist", "test-supplied-id-42");
        Map<String, Object> body = body(response);

        assertThat(response.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("test-supplied-id-42");
        assertThat(body).containsEntry("correlationId", "test-supplied-id-42");
    }

    @Test
    void unexpectedExceptionReturnsSafeGenericMessageNotTheRealOne() throws Exception {
        MvcResult response = probe("/probe/boom", null);
        Map<String, Object> body = body(response);

        assertThat(response.getResponse().getStatus()).isEqualTo(500);
        assertThat(body).containsEntry("code", "INTERNAL_ERROR");
        assertThat(body.get("correlationId")).isNotNull();
        assertThat(String.valueOf(body.get("detail"))).doesNotContain("the real secret failure reason");
    }

    @Test
    void failedValidationReportsTheAffectedField() throws Exception {
        MvcResult response = mockMvc.perform(post("/probe/validated")
                        .with(user("someone"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andReturn();
        Map<String, Object> body = body(response);

        assertThat(response.getResponse().getStatus()).isEqualTo(400);
        assertThat(body).containsEntry("code", "VALIDATION_FAILED");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> fields = (List<Map<String, String>>) body.get("fields");
        assertThat(fields).extracting(field -> field.get("field")).contains("name");
    }

    @Test
    void dataIntegrityViolationIsMappedToUnprocessableEntityNotAGeneric500() throws Exception {
        MvcResult response = probe("/probe/data-integrity-violation", null);
        Map<String, Object> body = body(response);

        assertThat(response.getResponse().getStatus()).isEqualTo(422);
        assertThat(body).containsEntry("code", "REFERENCED_DATA_UNAVAILABLE");
        assertThat(body.get("correlationId")).isNotNull();
    }

    @Test
    void jobNotFoundAndInvalidStateAreMappedToNotFoundAndConflict() throws Exception {
        MvcResult missing = probe("/probe/job-not-found", null);
        MvcResult conflict = probe("/probe/job-conflict", null);
        Map<String, Object> missingBody = body(missing);
        Map<String, Object> conflictBody = body(conflict);

        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(missingBody).containsEntry("code", "NOT_FOUND");
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflictBody).containsEntry("code", "CONFLICT");
    }

    private MvcResult probe(String path, String correlationId) throws Exception {
        MockHttpServletRequestBuilder builder = get(path).with(user("someone"));
        if (correlationId != null) {
            builder.header(CorrelationIdFilter.HEADER_NAME, correlationId);
        }
        return mockMvc.perform(builder).andReturn();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    @TestConfiguration
    static class ProbeControllerConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("the real secret failure reason");
        }

        @PostMapping("/probe/validated")
        String validated(@Valid @RequestBody ProbeRequest request) {
            return "ok";
        }

        @GetMapping("/probe/job-not-found")
        String jobNotFound() {
            throw new JobNotFoundException(901L);
        }

        @GetMapping("/probe/job-conflict")
        String jobConflict() {
            throw new InvalidJobTransitionException(JobState.CANCELLED, JobState.CANCELLED);
        }

        @GetMapping("/probe/data-integrity-violation")
        String dataIntegrityViolation() {
            throw new org.springframework.dao.DataIntegrityViolationException("simulated foreign key violation");
        }
    }

    record ProbeRequest(@NotBlank String name) {
    }
}
