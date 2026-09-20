-- Housekeeping for uploaded and generated files that nobody will ever come
-- back to. Until now each of these was only noticed when someone happened
-- to touch the file again: an upload abandoned halfway, a scan cut short by
-- a crash, a rejected file whose bytes were kept, a generated file whose
-- owner was never written because the step after it failed.
--
-- An earlier attempt at a system-wide sweep was reverted (see the artifact
-- table's own migration): a row-level security carve-out wide enough for a
-- sweep is wide enough for any member to read other workspaces' rows. The
-- worker login has no table access and reaches these rows only through the
-- routines below, which is the separate, single-purpose database identity
-- that attempt was missing. The worker receives artifact ids and opaque
-- object keys, never a filename.

-- When a rejected file's stored bytes were removed. The row itself stays
-- as a small record that an upload happened and why it was refused.
ALTER TABLE artifact ADD COLUMN payload_removed_at TIMESTAMPTZ;

-- When the scan that is running now began. A scan can start long after the
-- upload was finalized (the scanner was down, the person came back the next
-- day), so "this scan has been running too long" has to be measured from
-- here; measured from finalization, a late retry would look stuck from its
-- first second and be pulled out from under the request performing it.
ALTER TABLE artifact ADD COLUMN scan_started_at TIMESTAMPTZ;

CREATE INDEX artifact_payload_pending_removal_idx
    ON artifact (id) WHERE status = 'REJECTED' AND payload_removed_at IS NULL;

-- Moves stale files to the state they should already be in, and returns
-- how many changed:
--   an upload still UPLOADING after p_abandoned_after_millis was abandoned;
--   a file still SCANNING long after a scan can take was orphaned by a
--     crash and goes back to QUARANTINED, where completing the upload again
--     rescans it;
--   a file still QUARANTINED after p_abandoned_after_millis was never
--     rescanned by anyone and is refused.
CREATE FUNCTION public.worker_expire_stale_uploads(
    p_abandoned_after_millis BIGINT,
    p_stuck_scan_after_millis BIGINT,
    p_limit INTEGER
) RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_now TIMESTAMPTZ;
    v_changed INTEGER := 0;
    v_count INTEGER;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_abandoned_after_millis IS NULL OR p_abandoned_after_millis < 60000 THEN
        RAISE EXCEPTION 'An upload cannot be called abandoned before one minute has passed.';
    END IF;
    IF p_stuck_scan_after_millis IS NULL OR p_stuck_scan_after_millis < 60000 THEN
        RAISE EXCEPTION 'A scan cannot be called stuck before one minute has passed.';
    END IF;
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 512 THEN
        RAISE EXCEPTION 'The sweep batch size must be between one and 512.';
    END IF;

    v_now := clock_timestamp();

    UPDATE public.artifact a
    SET status = 'REJECTED', rejection_reason = 'EXPIRED_ABANDONED_UPLOAD'
    WHERE a.id IN (
        SELECT c.id FROM public.artifact c
        WHERE c.status = 'UPLOADING'
          AND c.created_at <= v_now - make_interval(secs => p_abandoned_after_millis / 1000.0)
        ORDER BY c.id
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_changed := v_changed + v_count;

    UPDATE public.artifact a
    SET status = 'QUARANTINED', scan_started_at = NULL
    WHERE a.id IN (
        SELECT c.id FROM public.artifact c
        WHERE c.status = 'SCANNING'
          AND COALESCE(c.scan_started_at, c.finalized_at, c.created_at)
              <= v_now - make_interval(secs => p_stuck_scan_after_millis / 1000.0)
        ORDER BY c.id
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_changed := v_changed + v_count;

    UPDATE public.artifact a
    SET status = 'REJECTED', rejection_reason = 'EXPIRED_QUARANTINE'
    WHERE a.id IN (
        SELECT c.id FROM public.artifact c
        WHERE c.status = 'QUARANTINED'
          AND COALESCE(c.finalized_at, c.created_at) <= v_now - make_interval(secs => p_abandoned_after_millis / 1000.0)
        ORDER BY c.id
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_changed := v_changed + v_count;

    RETURN v_changed;
END;
$$;

-- Hands out rejected files whose bytes are still stored and old enough to
-- remove. Before that, a ready file nothing refers to for longer than
-- p_unreferenced_after_millis is refused as well: it is either an upload
-- that was never attached to anything, or a generated file whose compile,
-- validation or template activation failed after the file was stored.
-- "Refers to" means a source snapshot, a template version or example, a
-- baseline render, a compilation, a validation manifest, an export receipt
-- or a published job output; what was merely extracted from a file is its
-- own derived data and is removed with it.
CREATE FUNCTION public.worker_collect_removable_payloads(
    p_rejected_after_millis BIGINT,
    p_unreferenced_after_millis BIGINT,
    p_limit INTEGER
) RETURNS TABLE (
    artifact_id BIGINT,
    workspace_id BIGINT,
    blob_key TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_now TIMESTAMPTZ;
    v_orphan_ids BIGINT[];
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_rejected_after_millis IS NULL OR p_rejected_after_millis < 0 THEN
        RAISE EXCEPTION 'The age after which a rejected file is removed must not be negative.';
    END IF;
    IF p_unreferenced_after_millis IS NULL OR p_unreferenced_after_millis < 3600000 THEN
        RAISE EXCEPTION 'A file cannot be called unreferenced before one hour has passed.';
    END IF;
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 512 THEN
        RAISE EXCEPTION 'The sweep batch size must be between one and 512.';
    END IF;

    v_now := clock_timestamp();

    SELECT COALESCE(array_agg(c.id), '{}') INTO v_orphan_ids
    FROM (
        SELECT a.id
        FROM public.artifact a
        WHERE a.status = 'READY'
          AND COALESCE(a.finalized_at, a.created_at) <= v_now - make_interval(secs => p_unreferenced_after_millis / 1000.0)
          AND NOT EXISTS (SELECT 1 FROM public.source_snapshot x WHERE x.workspace_id = a.workspace_id AND x.artifact_id = a.id)
          AND NOT EXISTS (SELECT 1 FROM public.template_version x WHERE x.workspace_id = a.workspace_id AND x.source_artifact_id = a.id)
          AND NOT EXISTS (SELECT 1 FROM public.template_example x WHERE x.workspace_id = a.workspace_id AND x.source_artifact_id = a.id)
          AND NOT EXISTS (SELECT 1 FROM public.template_baseline_render x
                          WHERE x.workspace_id = a.workspace_id AND a.id IN (x.docx_artifact_id, x.pdf_artifact_id))
          AND NOT EXISTS (SELECT 1 FROM public.document_compilation x
                          WHERE x.workspace_id = a.workspace_id AND a.id IN (x.docx_artifact_id, x.pdf_artifact_id))
          AND NOT EXISTS (SELECT 1 FROM public.validation_manifest x
                          WHERE x.workspace_id = a.workspace_id AND a.id IN (x.docx_artifact_id, x.pdf_artifact_id))
          AND NOT EXISTS (SELECT 1 FROM public.export_receipt x
                          WHERE x.workspace_id = a.workspace_id AND a.id IN (x.docx_artifact_id, x.pdf_artifact_id))
          AND NOT EXISTS (SELECT 1 FROM public.job_output_artifact x WHERE x.workspace_id = a.workspace_id AND x.artifact_id = a.id)
        ORDER BY a.id
        LIMIT p_limit
        FOR UPDATE OF a SKIP LOCKED
    ) c;

    IF array_length(v_orphan_ids, 1) > 0 THEN
        DELETE FROM public.extraction_version x WHERE x.artifact_id = ANY (v_orphan_ids);
        DELETE FROM public.pdf_extraction_version x WHERE x.artifact_id = ANY (v_orphan_ids);
        DELETE FROM public.plain_text_extraction_version x WHERE x.artifact_id = ANY (v_orphan_ids);
        UPDATE public.artifact a
        SET status = 'REJECTED', rejection_reason = 'UNREFERENCED_EXPIRED'
        WHERE a.id = ANY (v_orphan_ids);
    END IF;

    RETURN QUERY
    SELECT a.id, a.workspace_id, a.blob_key
    FROM public.artifact a
    WHERE a.status = 'REJECTED'
      AND a.payload_removed_at IS NULL
      AND (a.id = ANY (v_orphan_ids)
           OR COALESCE(a.finalized_at, a.created_at) <= v_now - make_interval(secs => p_rejected_after_millis / 1000.0))
    ORDER BY a.id
    LIMIT p_limit
    FOR UPDATE OF a SKIP LOCKED;
END;
$$;

-- Records that a rejected file's stored bytes are gone.
CREATE FUNCTION public.worker_mark_payload_removed(
    p_artifact_id BIGINT,
    p_blob_key TEXT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_marked BOOLEAN;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    UPDATE public.artifact a
    SET payload_removed_at = clock_timestamp()
    WHERE a.id = p_artifact_id
      AND a.blob_key = p_blob_key
      AND a.status = 'REJECTED'
      AND a.payload_removed_at IS NULL
    RETURNING TRUE INTO v_marked;
    RETURN COALESCE(v_marked, FALSE);
END;
$$;

REVOKE ALL ON FUNCTION public.worker_expire_stale_uploads(BIGINT, BIGINT, INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_collect_removable_payloads(BIGINT, BIGINT, INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_mark_payload_removed(BIGINT, TEXT) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_expire_stale_uploads(BIGINT, BIGINT, INTEGER) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_collect_removable_payloads(BIGINT, BIGINT, INTEGER) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_mark_payload_removed(BIGINT, TEXT) TO brownie_worker;
