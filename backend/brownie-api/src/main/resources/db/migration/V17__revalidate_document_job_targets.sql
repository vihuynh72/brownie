-- A document-targeted job pins the immutable revision it was created for.
-- The queue row alone can prove that a caller repeated its own target fields,
-- but it cannot prove that the document still selects that revision. Recheck
-- the concrete document pointer while the job row is locked so a result from
-- an older revision cannot become a successful completion.
CREATE OR REPLACE FUNCTION public.worker_complete(
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

    IF v_job.resource_type = 'document' THEN
        -- Hold a row lock through terminalization. A revision edit uses a
        -- stronger document lock, so it cannot advance the pointer after
        -- this check but before the job becomes terminal.
        PERFORM 1
        FROM public.document d
        WHERE d.workspace_id = v_job.workspace_id
          AND d.id = v_job.resource_id
          AND d.current_revision_id = v_job.resource_version
        FOR SHARE;
        IF NOT FOUND THEN
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
                'The document target is no longer current.');
            RETURN 'STALE_TARGET';
        END IF;
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
