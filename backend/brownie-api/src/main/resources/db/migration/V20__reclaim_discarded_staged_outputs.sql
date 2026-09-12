-- A database record is the durable source of truth for whether a temporary
-- object may attach. Cleanup deletes only records already made ineligible.
ALTER TABLE job_staged_output
    ADD COLUMN cleaned_at TIMESTAMPTZ;

-- A response can be lost after the attachment transaction commits. Allow the
-- same attempt to recover its already-attached metadata so it can converge on
-- the existing artifact rather than creating another logical result.
CREATE OR REPLACE FUNCTION public.worker_record_staged_output(
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

    SELECT o.* INTO v_output
    FROM public.job_staged_output o
    WHERE o.job_id = p_job_id
      AND o.worker_id = p_worker_id
      AND o.fencing_token = p_fencing_token
      AND o.output_kind = p_output_kind
    FOR UPDATE;
    IF FOUND AND v_job.state = 'SUCCEEDED' AND v_output.state = 'ATTACHED' THEN
        RETURN NEXT v_output;
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

CREATE FUNCTION public.worker_collect_discarded_staged_outputs(
    p_limit INTEGER
) RETURNS SETOF public.job_staged_output
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_now TIMESTAMPTZ;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker queue routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 512 THEN
        RAISE EXCEPTION 'Cleanup batch size must be between one and 512.';
    END IF;

    v_now := clock_timestamp();
    UPDATE public.job_staged_output o
    SET state = 'DISCARDED'
    WHERE o.state IN ('STAGED', 'VERIFIED')
      AND o.expires_at <= v_now;

    RETURN QUERY
    SELECT o.*
    FROM public.job_staged_output o
    WHERE o.state = 'DISCARDED'
      AND o.cleaned_at IS NULL
    ORDER BY o.id
    LIMIT p_limit
    FOR UPDATE SKIP LOCKED;
END;
$$;

CREATE FUNCTION public.worker_mark_discarded_staged_output_cleaned(
    p_staged_output_id BIGINT,
    p_object_key TEXT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_marked BOOLEAN;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker queue routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    UPDATE public.job_staged_output o
    SET cleaned_at = clock_timestamp()
    WHERE o.id = p_staged_output_id
      AND o.object_key = p_object_key
      AND o.state = 'DISCARDED'
      AND o.cleaned_at IS NULL
    RETURNING TRUE INTO v_marked;
    RETURN COALESCE(v_marked, FALSE);
END;
$$;

REVOKE ALL ON FUNCTION public.worker_record_staged_output(BIGINT, TEXT, BIGINT, TEXT, TEXT, TEXT, BIGINT, TIMESTAMPTZ)
    FROM PUBLIC, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_collect_discarded_staged_outputs(INTEGER)
    FROM PUBLIC, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_mark_discarded_staged_output_cleaned(BIGINT, TEXT)
    FROM PUBLIC, brownie_worker;

GRANT EXECUTE ON FUNCTION public.worker_record_staged_output(BIGINT, TEXT, BIGINT, TEXT, TEXT, TEXT, BIGINT, TIMESTAMPTZ)
    TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_collect_discarded_staged_outputs(INTEGER)
    TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_mark_discarded_staged_output_cleaned(BIGINT, TEXT)
    TO brownie_worker;
