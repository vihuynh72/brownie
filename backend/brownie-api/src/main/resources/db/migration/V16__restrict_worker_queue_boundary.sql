-- The worker credential is allowed to coordinate one leased queue attempt,
-- not to query or mutate tenant tables directly. Queue mutations therefore
-- cross this explicit routine boundary under the table-owning migration role.

CREATE OR REPLACE FUNCTION public.current_workspace_user_id()
RETURNS BIGINT
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog
AS $$
DECLARE
    configured_user_id TEXT;
BEGIN
    -- A custom setting is only actor context when it came from the API
    -- database login. A worker can set arbitrary custom settings too.
    IF session_user <> 'brownie_api' THEN
        RETURN NULL;
    END IF;

    configured_user_id := current_setting('app.current_user_id', true);
    IF configured_user_id IS NULL OR configured_user_id = '' THEN
        RETURN NULL;
    END IF;

    RETURN configured_user_id::BIGINT;
END;
$$;

REVOKE ALL ON FUNCTION public.current_workspace_user_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.current_workspace_user_id() TO brownie_api;

-- The helper is intentionally not callable by the worker. Every caller has
-- already locked the job row, so allocating one next event sequence is safe
-- without exposing a standalone event-insert capability.
CREATE FUNCTION public.worker_append_job_event(
    p_workspace_id BIGINT,
    p_job_id BIGINT,
    p_event_type TEXT,
    p_safe_message TEXT,
    p_progress_current INTEGER DEFAULT NULL,
    p_progress_total INTEGER DEFAULT NULL
) RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_event_id BIGINT;
    v_state TEXT;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker queue routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    SELECT j.state
    INTO v_state
    FROM public.job j
    WHERE j.workspace_id = p_workspace_id
      AND j.id = p_job_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'A queue event requires an existing job.';
    END IF;

    INSERT INTO public.job_event (
        workspace_id,
        job_id,
        sequence,
        event_type,
        state,
        safe_message,
        progress_current,
        progress_total
    )
    VALUES (
        p_workspace_id,
        p_job_id,
        (SELECT COALESCE(MAX(e.sequence), 0) + 1 FROM public.job_event e WHERE e.job_id = p_job_id),
        p_event_type,
        v_state,
        p_safe_message,
        p_progress_current,
        p_progress_total
    )
    RETURNING id INTO v_event_id;

    INSERT INTO public.outbox_event (
        delivery_key,
        workspace_id,
        job_id,
        job_event_id,
        event_type
    )
    VALUES (
        gen_random_uuid(),
        p_workspace_id,
        p_job_id,
        v_event_id,
        p_event_type
    );
END;
$$;

CREATE FUNCTION public.worker_claim_next(
    p_worker_id TEXT,
    p_lease_millis BIGINT,
    p_max_attempts INTEGER,
    p_recovery_batch_size INTEGER
) RETURNS SETOF public.job
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job public.job%ROWTYPE;
    v_now TIMESTAMPTZ;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker queue routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_worker_id IS NULL OR char_length(btrim(p_worker_id)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION 'A worker id must contain one to 128 non-blank characters.';
    END IF;
    IF p_lease_millis IS NULL OR p_lease_millis < 1 THEN
        RAISE EXCEPTION 'A lease duration must be at least one millisecond.';
    END IF;
    IF p_max_attempts IS NULL OR p_max_attempts < 1 THEN
        RAISE EXCEPTION 'The maximum attempt count must be positive.';
    END IF;
    IF p_recovery_batch_size IS NULL OR p_recovery_batch_size NOT BETWEEN 1 AND 256 THEN
        RAISE EXCEPTION 'The recovery batch size must be between one and 256.';
    END IF;

    -- Lock first, then read the clock. A time predicate evaluated before a
    -- row-lock wait is not evidence that the lease remains live afterward.
    FOR v_job IN
        SELECT j.*
        FROM public.job j
        WHERE j.state = 'CANCEL_REQUESTED'
        ORDER BY j.lease_expires_at, j.id
        FOR UPDATE SKIP LOCKED
        LIMIT p_recovery_batch_size
    LOOP
        v_now := clock_timestamp();
        IF v_job.lease_expires_at IS NULL
                OR v_job.lease_expires_at <= v_now
                OR v_job.deadline_at <= v_now THEN
            UPDATE public.job j
            SET state = 'CANCELLED',
                lease_owner = NULL,
                lease_expires_at = NULL,
                updated_at = v_now
            WHERE j.id = v_job.id
            RETURNING j.* INTO v_job;
            PERFORM public.worker_append_job_event(
                v_job.workspace_id,
                v_job.id,
                'CANCELLED',
                'Cancellation completed after an expired lease.');
        END IF;
    END LOOP;

    FOR v_job IN
        SELECT j.*
        FROM public.job j
        WHERE j.state IN ('QUEUED', 'LEASED', 'WAITING_FOR_INPUT')
        ORDER BY j.deadline_at, j.id
        FOR UPDATE SKIP LOCKED
        LIMIT p_recovery_batch_size
    LOOP
        v_now := clock_timestamp();
        IF v_job.deadline_at <= v_now THEN
            UPDATE public.job j
            SET state = 'DEAD',
                lease_owner = NULL,
                lease_expires_at = NULL,
                updated_at = v_now
            WHERE j.id = v_job.id
            RETURNING j.* INTO v_job;
            PERFORM public.worker_append_job_event(
                v_job.workspace_id,
                v_job.id,
                'RELEASED',
                'Job deadline reached before work completed.');
        END IF;
    END LOOP;

    FOR v_job IN
        SELECT j.*
        FROM public.job j
        WHERE j.state = 'LEASED'
        ORDER BY j.lease_expires_at, j.id
        FOR UPDATE SKIP LOCKED
        LIMIT p_recovery_batch_size
    LOOP
        v_now := clock_timestamp();
        IF v_job.lease_expires_at <= v_now AND v_job.attempt_count >= p_max_attempts THEN
            UPDATE public.job j
            SET state = 'DEAD',
                lease_owner = NULL,
                lease_expires_at = NULL,
                updated_at = v_now
            WHERE j.id = v_job.id
            RETURNING j.* INTO v_job;
            PERFORM public.worker_append_job_event(
                v_job.workspace_id,
                v_job.id,
                'RELEASED',
                'Retry limit reached after an expired lease.');
        END IF;
    END LOOP;

    FOR v_job IN
        SELECT j.*
        FROM public.job j
        WHERE (j.state = 'QUEUED' AND j.available_at <= clock_timestamp())
           OR (j.state = 'LEASED' AND j.lease_expires_at <= clock_timestamp())
        ORDER BY COALESCE(j.lease_expires_at, j.available_at), j.id
        FOR UPDATE SKIP LOCKED
        LIMIT p_recovery_batch_size
    LOOP
        v_now := clock_timestamp();
        IF v_job.deadline_at > v_now
                AND v_job.cancellation_requested_at IS NULL
                AND (
                    (v_job.state = 'QUEUED' AND v_job.available_at <= v_now)
                    OR (
                        v_job.state = 'LEASED'
                        AND v_job.lease_expires_at <= v_now
                        AND v_job.attempt_count < p_max_attempts
                    )
                ) THEN
            UPDATE public.job j
            SET state = 'LEASED',
                lease_owner = p_worker_id,
                lease_expires_at = LEAST(
                    v_job.deadline_at,
                    v_now + (p_lease_millis * interval '1 millisecond')
                ),
                fencing_token = v_job.fencing_token + 1,
                attempt_count = v_job.attempt_count + 1,
                updated_at = v_now
            WHERE j.id = v_job.id
            RETURNING j.* INTO v_job;

            PERFORM public.worker_append_job_event(
                v_job.workspace_id,
                v_job.id,
                'LEASED',
                'Work claimed by a worker.');
            RETURN NEXT v_job;
            RETURN;
        END IF;
    END LOOP;

    RETURN;
END;
$$;

CREATE FUNCTION public.worker_heartbeat(
    p_job_id BIGINT,
    p_worker_id TEXT,
    p_fencing_token BIGINT,
    p_lease_millis BIGINT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job public.job%ROWTYPE;
    v_now TIMESTAMPTZ;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker queue routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_lease_millis IS NULL OR p_lease_millis < 1 THEN
        RAISE EXCEPTION 'A lease duration must be at least one millisecond.';
    END IF;

    SELECT j.* INTO v_job
    FROM public.job j
    WHERE j.id = p_job_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    v_now := clock_timestamp();
    IF v_job.state <> 'LEASED'
            OR v_job.cancellation_requested_at IS NOT NULL
            OR v_job.lease_owner IS DISTINCT FROM p_worker_id
            OR v_job.fencing_token IS DISTINCT FROM p_fencing_token
            OR v_job.lease_expires_at IS NULL
            OR v_job.lease_expires_at <= v_now
            OR v_job.deadline_at <= v_now THEN
        RETURN FALSE;
    END IF;

    UPDATE public.job j
    SET lease_expires_at = LEAST(
            v_job.deadline_at,
            v_now + (p_lease_millis * interval '1 millisecond')
        ),
        updated_at = v_now
    WHERE j.id = v_job.id;
    RETURN TRUE;
END;
$$;

CREATE FUNCTION public.worker_get_live_lease(
    p_job_id BIGINT,
    p_worker_id TEXT,
    p_fencing_token BIGINT
) RETURNS SETOF public.job
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job public.job%ROWTYPE;
    v_now TIMESTAMPTZ;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker queue routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    SELECT j.* INTO v_job
    FROM public.job j
    WHERE j.id = p_job_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN;
    END IF;

    v_now := clock_timestamp();
    IF v_job.state = 'LEASED'
            AND v_job.cancellation_requested_at IS NULL
            AND v_job.lease_owner = p_worker_id
            AND v_job.fencing_token = p_fencing_token
            AND v_job.lease_expires_at > v_now
            AND v_job.deadline_at > v_now THEN
        RETURN NEXT v_job;
    END IF;
END;
$$;

CREATE FUNCTION public.worker_record_progress(
    p_job_id BIGINT,
    p_worker_id TEXT,
    p_fencing_token BIGINT,
    p_safe_message TEXT,
    p_progress_current INTEGER,
    p_progress_total INTEGER
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job public.job%ROWTYPE;
    v_now TIMESTAMPTZ;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker queue routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    SELECT j.* INTO v_job
    FROM public.job j
    WHERE j.id = p_job_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    v_now := clock_timestamp();
    IF v_job.state <> 'LEASED'
            OR v_job.cancellation_requested_at IS NOT NULL
            OR v_job.lease_owner IS DISTINCT FROM p_worker_id
            OR v_job.fencing_token IS DISTINCT FROM p_fencing_token
            OR v_job.lease_expires_at IS NULL
            OR v_job.lease_expires_at <= v_now
            OR v_job.deadline_at <= v_now THEN
        RETURN FALSE;
    END IF;

    PERFORM public.worker_append_job_event(
        v_job.workspace_id,
        v_job.id,
        'PROGRESS',
        p_safe_message,
        p_progress_current,
        p_progress_total);
    RETURN TRUE;
END;
$$;

CREATE FUNCTION public.worker_release(
    p_job_id BIGINT,
    p_worker_id TEXT,
    p_fencing_token BIGINT,
    p_next_state TEXT,
    p_available_at TIMESTAMPTZ,
    p_safe_message TEXT,
    p_max_attempts INTEGER
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job public.job%ROWTYPE;
    v_now TIMESTAMPTZ;
    v_message TEXT;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker queue routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_max_attempts IS NULL OR p_max_attempts < 1 THEN
        RAISE EXCEPTION 'The maximum attempt count must be positive.';
    END IF;
    IF p_next_state NOT IN ('QUEUED', 'WAITING_FOR_INPUT', 'DEAD') THEN
        RAISE EXCEPTION 'A worker release must target QUEUED, WAITING_FOR_INPUT, or DEAD.';
    END IF;
    IF (p_next_state = 'QUEUED' AND p_available_at IS NULL)
            OR (p_next_state <> 'QUEUED' AND p_available_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Only a requeued job may provide an availability time.';
    END IF;

    SELECT j.* INTO v_job
    FROM public.job j
    WHERE j.id = p_job_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'LOST_LEASE';
    END IF;

    v_now := clock_timestamp();
    IF v_job.state = 'CANCEL_REQUESTED' THEN
        IF v_job.lease_owner = p_worker_id
                AND v_job.fencing_token = p_fencing_token
                AND v_job.lease_expires_at > v_now
                AND v_job.deadline_at > v_now THEN
            UPDATE public.job j
            SET state = 'CANCELLED',
                lease_owner = NULL,
                lease_expires_at = NULL,
                updated_at = v_now
            WHERE j.id = v_job.id
            RETURNING j.* INTO v_job;
            PERFORM public.worker_append_job_event(
                v_job.workspace_id,
                v_job.id,
                'CANCELLED',
                'Cancellation completed by the worker.');
            RETURN 'CANCELLED';
        END IF;
        RETURN 'CANCELLATION_REQUESTED';
    END IF;
    IF v_job.state = 'CANCELLED' THEN
        RETURN 'CANCELLATION_REQUESTED';
    END IF;
    IF v_job.state <> 'LEASED'
            OR v_job.cancellation_requested_at IS NOT NULL
            OR v_job.lease_owner IS DISTINCT FROM p_worker_id
            OR v_job.fencing_token IS DISTINCT FROM p_fencing_token
            OR v_job.lease_expires_at IS NULL
            OR v_job.lease_expires_at <= v_now
            OR v_job.deadline_at <= v_now THEN
        RETURN 'LOST_LEASE';
    END IF;

    IF p_next_state = 'QUEUED' AND v_job.attempt_count >= p_max_attempts THEN
        UPDATE public.job j
        SET state = 'DEAD',
            lease_owner = NULL,
            lease_expires_at = NULL,
            updated_at = v_now
        WHERE j.id = v_job.id
        RETURNING j.* INTO v_job;
        PERFORM public.worker_append_job_event(
            v_job.workspace_id,
            v_job.id,
            'RELEASED',
            'Retry limit reached before requeueing work.');
        RETURN 'RELEASED';
    END IF;

    UPDATE public.job j
    SET state = p_next_state,
        available_at = CASE WHEN p_next_state = 'QUEUED' THEN p_available_at ELSE j.available_at END,
        lease_owner = NULL,
        lease_expires_at = NULL,
        updated_at = v_now
    WHERE j.id = v_job.id
    RETURNING j.* INTO v_job;

    v_message := p_safe_message;
    PERFORM public.worker_append_job_event(
        v_job.workspace_id,
        v_job.id,
        'RELEASED',
        v_message);
    RETURN 'RELEASED';
END;
$$;

CREATE FUNCTION public.worker_complete(
    p_job_id BIGINT,
    p_worker_id TEXT,
    p_fencing_token BIGINT,
    p_resource_type TEXT,
    p_resource_id BIGINT,
    p_resource_version BIGINT,
    p_final_state TEXT,
    p_safe_message TEXT
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job public.job%ROWTYPE;
    v_now TIMESTAMPTZ;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker queue routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_final_state NOT IN ('SUCCEEDED', 'FAILED') THEN
        RAISE EXCEPTION 'A worker completion must target SUCCEEDED or FAILED.';
    END IF;

    SELECT j.* INTO v_job
    FROM public.job j
    WHERE j.id = p_job_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'LOST_LEASE';
    END IF;

    v_now := clock_timestamp();
    IF v_job.state = 'CANCEL_REQUESTED' THEN
        IF v_job.lease_owner = p_worker_id
                AND v_job.fencing_token = p_fencing_token
                AND v_job.lease_expires_at > v_now
                AND v_job.deadline_at > v_now THEN
            UPDATE public.job j
            SET state = 'CANCELLED',
                lease_owner = NULL,
                lease_expires_at = NULL,
                updated_at = v_now
            WHERE j.id = v_job.id
            RETURNING j.* INTO v_job;
            PERFORM public.worker_append_job_event(
                v_job.workspace_id,
                v_job.id,
                'CANCELLED',
                'Cancellation completed by the worker.');
        END IF;
        RETURN 'CANCELLATION_REQUESTED';
    END IF;
    IF v_job.state = 'CANCELLED' THEN
        RETURN 'CANCELLATION_REQUESTED';
    END IF;
    IF v_job.resource_type IS DISTINCT FROM p_resource_type
            OR v_job.resource_id IS DISTINCT FROM p_resource_id
            OR v_job.resource_version IS DISTINCT FROM p_resource_version THEN
        RETURN 'STALE_TARGET';
    END IF;
    IF v_job.state <> 'LEASED'
            OR v_job.cancellation_requested_at IS NOT NULL
            OR v_job.lease_owner IS DISTINCT FROM p_worker_id
            OR v_job.fencing_token IS DISTINCT FROM p_fencing_token
            OR v_job.lease_expires_at IS NULL
            OR v_job.lease_expires_at <= v_now
            OR v_job.deadline_at <= v_now THEN
        RETURN 'LOST_LEASE';
    END IF;

    UPDATE public.job j
    SET state = p_final_state,
        lease_owner = NULL,
        lease_expires_at = NULL,
        updated_at = v_now
    WHERE j.id = v_job.id
    RETURNING j.* INTO v_job;
    PERFORM public.worker_append_job_event(
        v_job.workspace_id,
        v_job.id,
        'COMPLETED',
        p_safe_message);
    RETURN 'COMPLETED';
END;
$$;

CREATE FUNCTION public.worker_record_staged_output(
    p_job_id BIGINT,
    p_worker_id TEXT,
    p_fencing_token BIGINT,
    p_output_kind TEXT,
    p_object_key TEXT,
    p_sha256 TEXT,
    p_byte_count BIGINT,
    p_expires_at TIMESTAMPTZ
) RETURNS SETOF public.job_staged_output
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job public.job%ROWTYPE;
    v_output public.job_staged_output%ROWTYPE;
    v_now TIMESTAMPTZ;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker queue routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    SELECT j.* INTO v_job
    FROM public.job j
    WHERE j.id = p_job_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN;
    END IF;

    v_now := clock_timestamp();
    IF p_expires_at IS NULL OR p_expires_at <= v_now THEN
        RAISE EXCEPTION 'A staged output expiry must be after the database clock.';
    END IF;
    IF v_job.state <> 'LEASED'
            OR v_job.cancellation_requested_at IS NOT NULL
            OR v_job.lease_owner IS DISTINCT FROM p_worker_id
            OR v_job.fencing_token IS DISTINCT FROM p_fencing_token
            OR v_job.lease_expires_at IS NULL
            OR v_job.lease_expires_at <= v_now
            OR v_job.deadline_at <= v_now THEN
        RETURN;
    END IF;

    INSERT INTO public.job_staged_output (
        workspace_id,
        job_id,
        worker_id,
        fencing_token,
        output_kind,
        object_key,
        sha256,
        byte_count,
        expires_at
    )
    VALUES (
        v_job.workspace_id,
        v_job.id,
        p_worker_id,
        p_fencing_token,
        p_output_kind,
        p_object_key,
        p_sha256,
        p_byte_count,
        p_expires_at
    )
    ON CONFLICT (job_id, worker_id, fencing_token, output_kind) DO NOTHING
    RETURNING * INTO v_output;

    IF FOUND THEN
        PERFORM public.worker_append_job_event(
            v_job.workspace_id,
            v_job.id,
            'STAGED_OUTPUT_RECORDED',
            'Temporary output recorded.');
        RETURN NEXT v_output;
        RETURN;
    END IF;

    SELECT o.* INTO v_output
    FROM public.job_staged_output o
    WHERE o.job_id = p_job_id
      AND o.worker_id = p_worker_id
      AND o.fencing_token = p_fencing_token
      AND o.output_kind = p_output_kind;
    IF FOUND THEN
        RETURN NEXT v_output;
    END IF;
END;
$$;

-- Existing installations may have inherited earlier broad grants. Remove
-- them from both existing objects and future migration-created objects.
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM brownie_worker;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM brownie_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE brownie_migration IN SCHEMA public
    REVOKE ALL PRIVILEGES ON TABLES FROM brownie_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE brownie_migration IN SCHEMA public
    REVOKE ALL PRIVILEGES ON SEQUENCES FROM brownie_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE brownie_migration IN SCHEMA public
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

DROP POLICY IF EXISTS job_worker_select ON public.job;
DROP POLICY IF EXISTS job_worker_update ON public.job;
DROP POLICY IF EXISTS job_event_worker_select ON public.job_event;
DROP POLICY IF EXISTS job_event_worker_insert ON public.job_event;
DROP POLICY IF EXISTS outbox_event_worker_insert ON public.outbox_event;
DROP POLICY IF EXISTS job_staged_output_worker_select ON public.job_staged_output;
DROP POLICY IF EXISTS job_staged_output_worker_insert ON public.job_staged_output;
DROP POLICY IF EXISTS job_staged_output_worker_update ON public.job_staged_output;

-- Earlier member policies predate the API-only actor-context boundary. Keep
-- their row predicates, but make their intended database role explicit.
ALTER POLICY workspace_owner_or_member ON public.workspace TO brownie_api;
ALTER POLICY workspace_insert_own ON public.workspace TO brownie_api;
ALTER POLICY workspace_member_own ON public.workspace_member TO brownie_api;
ALTER POLICY workspace_member_insert_own ON public.workspace_member TO brownie_api;

ALTER POLICY artifact_member_select ON public.artifact TO brownie_api;
ALTER POLICY artifact_member_insert ON public.artifact TO brownie_api;
ALTER POLICY artifact_member_update ON public.artifact TO brownie_api;

ALTER POLICY extraction_version_member_select ON public.extraction_version TO brownie_api;
ALTER POLICY extraction_version_member_insert ON public.extraction_version TO brownie_api;
ALTER POLICY pdf_extraction_version_member_select ON public.pdf_extraction_version TO brownie_api;
ALTER POLICY pdf_extraction_version_member_insert ON public.pdf_extraction_version TO brownie_api;
ALTER POLICY plain_text_extraction_version_member_select ON public.plain_text_extraction_version TO brownie_api;
ALTER POLICY plain_text_extraction_version_member_insert ON public.plain_text_extraction_version TO brownie_api;

ALTER POLICY source_snapshot_member_select ON public.source_snapshot TO brownie_api;
ALTER POLICY source_snapshot_member_insert ON public.source_snapshot TO brownie_api;
ALTER POLICY source_span_member_select ON public.source_span TO brownie_api;
ALTER POLICY source_span_member_insert ON public.source_span TO brownie_api;

ALTER POLICY template_member_select ON public.template TO brownie_api;
ALTER POLICY template_member_insert ON public.template TO brownie_api;
ALTER POLICY template_member_update ON public.template TO brownie_api;
ALTER POLICY template_version_member_select ON public.template_version TO brownie_api;
ALTER POLICY template_version_member_insert ON public.template_version TO brownie_api;
ALTER POLICY template_version_member_update ON public.template_version TO brownie_api;
ALTER POLICY rule_revision_member_select ON public.rule_revision TO brownie_api;
ALTER POLICY rule_revision_member_insert ON public.rule_revision TO brownie_api;

ALTER POLICY job_member_select ON public.job TO brownie_api;
ALTER POLICY job_member_insert ON public.job TO brownie_api;
ALTER POLICY job_member_request_cancellation ON public.job TO brownie_api;
ALTER POLICY job_member_resume ON public.job TO brownie_api;
ALTER POLICY idempotency_record_actor_select ON public.idempotency_record TO brownie_api;
ALTER POLICY idempotency_record_actor_insert ON public.idempotency_record TO brownie_api;
ALTER POLICY command_receipt_actor_select ON public.command_receipt TO brownie_api;
ALTER POLICY command_receipt_actor_insert ON public.command_receipt TO brownie_api;
ALTER POLICY job_event_member_select ON public.job_event TO brownie_api;
ALTER POLICY job_event_member_insert ON public.job_event TO brownie_api;
ALTER POLICY outbox_event_member_select ON public.outbox_event TO brownie_api;
ALTER POLICY outbox_event_member_insert ON public.outbox_event TO brownie_api;
ALTER POLICY job_staged_output_member_select ON public.job_staged_output TO brownie_api;

ALTER POLICY document_member_select ON public.document TO brownie_api;
ALTER POLICY document_member_insert ON public.document TO brownie_api;
ALTER POLICY document_revision_member_select ON public.document_revision TO brownie_api;
ALTER POLICY document_revision_member_insert ON public.document_revision TO brownie_api;
ALTER POLICY document_command_receipt_actor_select ON public.document_command_receipt TO brownie_api;
ALTER POLICY document_command_receipt_actor_insert ON public.document_command_receipt TO brownie_api;

REVOKE ALL ON FUNCTION public.worker_append_job_event(BIGINT, BIGINT, TEXT, TEXT, INTEGER, INTEGER) FROM PUBLIC, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_claim_next(TEXT, BIGINT, INTEGER, INTEGER) FROM PUBLIC, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_heartbeat(BIGINT, TEXT, BIGINT, BIGINT) FROM PUBLIC, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_get_live_lease(BIGINT, TEXT, BIGINT) FROM PUBLIC, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_record_progress(BIGINT, TEXT, BIGINT, TEXT, INTEGER, INTEGER) FROM PUBLIC, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_release(BIGINT, TEXT, BIGINT, TEXT, TIMESTAMPTZ, TEXT, INTEGER) FROM PUBLIC, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_complete(BIGINT, TEXT, BIGINT, TEXT, BIGINT, BIGINT, TEXT, TEXT) FROM PUBLIC, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_record_staged_output(BIGINT, TEXT, BIGINT, TEXT, TEXT, TEXT, BIGINT, TIMESTAMPTZ) FROM PUBLIC, brownie_worker;

GRANT EXECUTE ON FUNCTION public.worker_claim_next(TEXT, BIGINT, INTEGER, INTEGER) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_heartbeat(BIGINT, TEXT, BIGINT, BIGINT) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_get_live_lease(BIGINT, TEXT, BIGINT) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_record_progress(BIGINT, TEXT, BIGINT, TEXT, INTEGER, INTEGER) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_release(BIGINT, TEXT, BIGINT, TEXT, TIMESTAMPTZ, TEXT, INTEGER) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_complete(BIGINT, TEXT, BIGINT, TEXT, BIGINT, BIGINT, TEXT, TEXT) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_record_staged_output(BIGINT, TEXT, BIGINT, TEXT, TEXT, TEXT, BIGINT, TIMESTAMPTZ) TO brownie_worker;
