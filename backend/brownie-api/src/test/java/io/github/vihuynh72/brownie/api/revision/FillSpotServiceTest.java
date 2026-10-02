package io.github.vihuynh72.brownie.api.revision;

import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentCommandType;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.revision.DocumentRepository;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.RevisionRestoreResult;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderRepository;
import io.github.vihuynh72.brownie.core.template.TemplateDerivationService;
import io.github.vihuynh72.brownie.core.template.TemplateLineageRepository;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Undo across a correction takes the form every new document starts from
 * back with the document, as the correction moved it on; that is a change to
 * the template, so it needs the right to change templates, as a correction
 * does. Without it, the document alone goes back. Every member holds the
 * OWNER role today, so whether the member has the right is passed in here.
 */
class FillSpotServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long DOCUMENT_ID = 5L;
    private static final long TEMPLATE_ID = 3L;
    private static final long VERSION_BEFORE = 40L;
    private static final long CORRECTED_VERSION = 41L;
    private static final CanonicalRequestHash HASH = new CanonicalRequestHash("0".repeat(64));

    private final RevisionService revisions = mock(RevisionService.class);
    private final TemplateLineageRepository lineage = mock(TemplateLineageRepository.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final FillSpotService service = new FillSpotService(revisions, mock(TemplateService.class),
            mock(TemplateDerivationService.class), lineage, mock(TemplateBaselineRenderRepository.class), mock(DocumentRepository.class),
            transactions);

    @Test
    void undoingACorrectionTakesTheFormBackOnlyForAMemberWhoMayChangeTemplates() {
        Document document = new Document(DOCUMENT_ID, WORKSPACE_ID, "Minutes", TEMPLATE_ID, VERSION_BEFORE, 12L, OffsetDateTime.now());
        DocumentRevision corrected = revision(11L, CORRECTED_VERSION, 10L);
        DocumentRevision undone = revision(12L, VERSION_BEFORE, 11L);
        when(transactions.execute(any())).thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(revisions.findDocument(WORKSPACE_ID, USER_ID, DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(revisions.restoreRevision(eq(WORKSPACE_ID), eq(USER_ID), any(), any(), eq(DOCUMENT_ID), eq(11L), eq(10L), any()))
                .thenReturn(new RevisionRestoreResult(new DocumentMutationResult(UUID.randomUUID(), DocumentCommandType.EDIT_CONTENT,
                        document, undone, HASH, OffsetDateTime.now(), false), List.of(), List.of()));
        when(revisions.findRevision(WORKSPACE_ID, USER_ID, DOCUMENT_ID, 11L)).thenReturn(Optional.of(corrected));

        service.restoreRevision(WORKSPACE_ID, USER_ID, new IdempotencyKey("undo-1"), HASH, DOCUMENT_ID, 11L, 10L, "Undo", false);

        verify(lineage, never()).stepCurrentVersion(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(lineage).recordDocumentVersionChanged(WORKSPACE_ID, USER_ID, DOCUMENT_ID, CORRECTED_VERSION, VERSION_BEFORE, 12L);

        clearInvocations(lineage);
        service.restoreRevision(WORKSPACE_ID, USER_ID, new IdempotencyKey("undo-2"), HASH, DOCUMENT_ID, 11L, 10L, "Undo", true);

        verify(lineage).stepCurrentVersion(WORKSPACE_ID, USER_ID, TEMPLATE_ID, CORRECTED_VERSION, VERSION_BEFORE, DOCUMENT_ID);
        verify(lineage).recordDocumentVersionChanged(WORKSPACE_ID, USER_ID, DOCUMENT_ID, CORRECTED_VERSION, VERSION_BEFORE, 12L);
    }

    private static DocumentRevision revision(long id, long templateVersionId, Long parentRevisionId) {
        return new DocumentRevision(id, WORKSPACE_ID, DOCUMENT_ID, templateVersionId, (int) id, parentRevisionId, DocumentContent.empty(),
                "a".repeat(64), USER_ID, "Undo", OffsetDateTime.now(), null, null);
    }
}
