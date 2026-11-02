package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.api.generation.GenerationOrchestrationService;
import io.github.vihuynh72.brownie.api.generation.GenerationOrchestrationService.GenerationRunView;
import io.github.vihuynh72.brownie.core.generation.GenerationRun;
import io.github.vihuynh72.brownie.core.generation.GenerationRunRepository;
import io.github.vihuynh72.brownie.core.job.CommandReceipt;
import io.github.vihuynh72.brownie.core.job.CommandReceiptStatus;
import io.github.vihuynh72.brownie.core.job.Job;
import io.github.vihuynh72.brownie.core.job.JobCommandRepository;
import io.github.vihuynh72.brownie.core.job.JobCommandType;
import io.github.vihuynh72.brownie.core.job.JobState;
import io.github.vihuynh72.brownie.core.mcp.McpAppliedRunRepository;
import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import io.github.vihuynh72.brownie.core.question.Question;
import io.github.vihuynh72.brownie.core.question.QuestionReason;
import io.github.vihuynh72.brownie.core.question.QuestionService;
import io.github.vihuynh72.brownie.core.question.QuestionStatus;
import io.github.vihuynh72.brownie.core.revision.PatchProposalRepository;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.source.DocumentSourceService;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dedupe contract of {@code propose_fill}: while a run for the same
 * document and source artifact is still going, a call joins it; once it
 * is done, a call starts a fresh one. A JSON-RPC request id is never
 * consulted -- clients reuse ids across sessions, so an id cannot carry
 * idempotency. The polling interval's one behavioural observation --
 * a still-running poll waits before it asks again -- is pinned here too.
 */
class McpFillOrchestrationDedupeTest {

    private static final long WORKSPACE = 1;
    private static final long USER = 2;
    private static final long DOCUMENT = 3;
    private static final long ARTIFACT = 4;

    private final GenerationOrchestrationService orchestration = mock(GenerationOrchestrationService.class);
    private final GenerationRunRepository runRepository = mock(GenerationRunRepository.class);
    private final JobCommandRepository jobRepository = mock(JobCommandRepository.class);
    private final QuestionService questionService = mock(QuestionService.class);
    private final RevisionService revisionService = mock(RevisionService.class);
    private final McpFillOrchestration fill = new McpFillOrchestration(
            orchestration, runRepository, jobRepository,
            mock(io.github.vihuynh72.brownie.core.job.JobEventRepository.class), questionService,
            mock(PatchProposalRepository.class), mock(McpAppliedRunRepository.class),
            revisionService, mock(TemplateService.class), mock(DocumentSourceService.class),
            new McpSettings("http://localhost:8081"));

    private final McpCaller caller = new McpCaller(USER, WORKSPACE, 9, "chat", Set.of(McpScope.FILL));

    @Test
    void callsWhileARunIsInFlightShareTheRun() {
        GenerationRun run = run(8, 55);
        Job waiting = job(55, JobState.WAITING_FOR_INPUT);
        // The first call finds nothing in flight and starts the run; the
        // second finds it waiting on its question and joins it.
        when(orchestration.listRuns(WORKSPACE, USER, DOCUMENT))
                .thenReturn(List.of())
                .thenReturn(List.of(new GenerationRunView(run, waiting, null)));
        when(orchestration.startExtraction(anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
                .thenReturn(receipt(55));
        when(runRepository.findByJob(WORKSPACE, USER, 55)).thenReturn(Optional.of(run));
        when(jobRepository.find(WORKSPACE, USER, 55)).thenReturn(Optional.of(waiting));
        when(orchestration.openQuestions(WORKSPACE, USER, DOCUMENT, 55)).thenReturn(List.of(openQuestion()));

        Map<String, Object> first = fill.proposeFill(caller, DOCUMENT, ARTIFACT);
        Map<String, Object> second = fill.proposeFill(caller, DOCUMENT, ARTIFACT);

        assertThat(first.get("state")).isEqualTo("QUESTIONS");
        assertThat(second.get("state")).isEqualTo("QUESTIONS");
        assertThat(second.get("run_id")).isEqualTo(first.get("run_id"));
        // One run was started, not one per call.
        verify(orchestration, times(1)).startExtraction(anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void aCallAfterTheRunFinishedStartsAFreshOne() {
        GenerationRun first = run(8, 55);
        GenerationRun second = run(9, 56);
        Job dead = job(55, JobState.DEAD);
        when(orchestration.listRuns(WORKSPACE, USER, DOCUMENT))
                .thenReturn(List.of())
                .thenReturn(List.of(new GenerationRunView(first, dead, null)));
        when(orchestration.startExtraction(anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
                .thenReturn(receipt(55))
                .thenReturn(receipt(56));
        when(runRepository.findByJob(WORKSPACE, USER, 55)).thenReturn(Optional.of(first));
        when(runRepository.findByJob(WORKSPACE, USER, 56)).thenReturn(Optional.of(second));
        when(jobRepository.find(WORKSPACE, USER, 55)).thenReturn(Optional.of(dead));
        when(jobRepository.find(WORKSPACE, USER, 56)).thenReturn(Optional.of(job(56, JobState.DEAD)));

        Map<String, Object> firstAnswer = fill.proposeFill(caller, DOCUMENT, ARTIFACT);
        Map<String, Object> secondAnswer = fill.proposeFill(caller, DOCUMENT, ARTIFACT);

        // A terminal run does not join: the second call started a new one.
        assertThat(firstAnswer.get("run_id")).isEqualTo(8L);
        assertThat(secondAnswer.get("run_id")).isEqualTo(9L);
        verify(orchestration, times(2)).startExtraction(anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void aStillRunningPollWaitsHalfASecondBeforeAskingAgain() {
        GenerationRun run = run(8, 55);
        Job queued = job(55, JobState.QUEUED);
        when(orchestration.listRuns(WORKSPACE, USER, DOCUMENT))
                .thenReturn(List.of(new GenerationRunView(run, queued, null)));
        AtomicInteger polls = new AtomicInteger();
        // First look is still queued, so the loop must sleep; the second
        // look finds it dead and answers. A 150 ms nap would land this
        // test well under the half-second mark it now asserts.
        when(jobRepository.find(WORKSPACE, USER, 55)).thenAnswer(invocation -> {
            polls.incrementAndGet();
            return Optional.of(polls.get() == 1 ? queued : job(55, JobState.FAILED));
        });

        long started = System.nanoTime();
        Map<String, Object> answer = fill.proposeFill(caller, DOCUMENT, ARTIFACT);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(answer.get("state")).isEqualTo("FAILED");
        assertThat(polls.get()).isEqualTo(2);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(450);
        verify(orchestration, never()).startExtraction(anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    private static GenerationRun run(long id, long jobId) {
        return new GenerationRun(id, WORKSPACE, DOCUMENT, 10, 11, 12, ARTIFACT, jobId,
                "hash", "model", "prompt", USER, OffsetDateTime.now());
    }

    private static Job job(long id, JobState state) {
        return new Job(id, WORKSPACE, USER, null, null, null, null, state,
                0, OffsetDateTime.now(), null, null, 0, null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static CommandReceipt receipt(long jobId) {
        return new CommandReceipt(UUID.randomUUID(), WORKSPACE, USER, null, jobId, null,
                CommandReceiptStatus.ACCEPTED, OffsetDateTime.now());
    }

    private static Question openQuestion() {
        return new Question(30, WORKSPACE, DOCUMENT, 8L, 1L, "meeting.date", QuestionReason.MISSING_REQUIRED,
                List.of(), QuestionStatus.OPEN, null, null, null, OffsetDateTime.now());
    }
}
