-- An attached output is one immutable artifact for one logical job output.
-- The same temporary object key becomes durable only in the transaction that
-- records this relationship and terminal job state.
CREATE TABLE job_output_artifact (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    output_kind TEXT NOT NULL,
    staged_output_id BIGINT NOT NULL,
    artifact_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT job_output_artifact_job_fk
        FOREIGN KEY (workspace_id, job_id) REFERENCES job (workspace_id, id),
    CONSTRAINT job_output_artifact_staged_output_fk
        FOREIGN KEY (workspace_id, staged_output_id) REFERENCES job_staged_output (workspace_id, id),
    CONSTRAINT job_output_artifact_artifact_fk
        FOREIGN KEY (workspace_id, artifact_id) REFERENCES artifact (workspace_id, id),
    CONSTRAINT job_output_artifact_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT job_output_artifact_logical_key UNIQUE (job_id, output_kind),
    CONSTRAINT job_output_artifact_staged_output_key UNIQUE (staged_output_id),
    CONSTRAINT job_output_artifact_artifact_key UNIQUE (artifact_id),
    CONSTRAINT job_output_artifact_kind_format CHECK (
        output_kind ~ '^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$' AND char_length(output_kind) <= 100
    )
);

CREATE INDEX job_output_artifact_workspace_job_idx ON job_output_artifact (workspace_id, job_id);

ALTER TABLE job_output_artifact ENABLE ROW LEVEL SECURITY;

CREATE POLICY job_output_artifact_member_select ON job_output_artifact
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1
            FROM workspace_member wm
            WHERE wm.workspace_id = job_output_artifact.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

CREATE FUNCTION public.worker_publish_staged_output(
    p_job_id BIGINT,
    p_worker_id TEXT,
    p_fencing_token BIGINT,
    p_output_kind TEXT,
    p_detected_media_type TEXT
) RETURNS TABLE (
    outcome TEXT,
    artifact_id BIGINT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job public.job%ROWTYPE;
    v_output public.job_staged_output%ROWTYPE;
    v_artifact_id BIGINT;
    v_now TIMESTAMPTZ;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker queue routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_detected_media_type NOT IN ('DOCX', 'PDF', 'PLAIN_TEXT') THEN
        RAISE EXCEPTION 'A worker output must have a supported observed media type.';
    END IF;

    SELECT j.* INTO v_job
    FROM public.job j
    WHERE j.id = p_job_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'LOST_LEASE'::TEXT, NULL::BIGINT;
        RETURN;
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
        RETURN QUERY SELECT 'CANCELLATION_REQUESTED'::TEXT, NULL::BIGINT;
        RETURN;
    END IF;
    IF v_job.state = 'CANCELLED' THEN
        RETURN QUERY SELECT 'CANCELLATION_REQUESTED'::TEXT, NULL::BIGINT;
        RETURN;
    END IF;
    IF v_job.state = 'SUCCEEDED' THEN
        SELECT o.artifact_id INTO v_artifact_id
        FROM public.job_output_artifact o
        WHERE o.workspace_id = v_job.workspace_id
          AND o.job_id = v_job.id
          AND o.output_kind = p_output_kind;
        IF FOUND THEN
            RETURN QUERY SELECT 'ALREADY_PUBLISHED'::TEXT, v_artifact_id;
        ELSE
            RETURN QUERY SELECT 'LOST_LEASE'::TEXT, NULL::BIGINT;
        END IF;
        RETURN;
    END IF;
    IF v_job.state <> 'LEASED'
            OR v_job.cancellation_requested_at IS NOT NULL
            OR v_job.lease_owner IS DISTINCT FROM p_worker_id
            OR v_job.fencing_token IS DISTINCT FROM p_fencing_token
            OR v_job.lease_expires_at IS NULL
            OR v_job.lease_expires_at <= v_now
            OR v_job.deadline_at <= v_now THEN
        RETURN QUERY SELECT 'LOST_LEASE'::TEXT, NULL::BIGINT;
        RETURN;
    END IF;

    IF v_job.resource_type = 'document' THEN
        -- Hold a row lock through artifact attachment. A revision edit uses
        -- a stronger document lock, so it cannot advance the pointer after
        -- this check but before the result is made visible.
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
            RETURN QUERY SELECT 'STALE_TARGET'::TEXT, NULL::BIGINT;
            RETURN;
        END IF;
    END IF;

    SELECT o.* INTO v_output
    FROM public.job_staged_output o
    WHERE o.job_id = v_job.id
      AND o.worker_id = p_worker_id
      AND o.fencing_token = p_fencing_token
      AND o.output_kind = p_output_kind
    FOR UPDATE;
    IF NOT FOUND OR v_output.state = 'DISCARDED' OR v_output.expires_at <= v_now THEN
        IF FOUND AND v_output.state <> 'DISCARDED' THEN
            UPDATE public.job_staged_output
            SET state = 'DISCARDED'
            WHERE id = v_output.id;
        END IF;
        RETURN QUERY SELECT 'EXPIRED_OUTPUT'::TEXT, NULL::BIGINT;
        RETURN;
    END IF;
    IF v_output.state = 'ATTACHED' THEN
        SELECT o.artifact_id INTO v_artifact_id
        FROM public.job_output_artifact o
        WHERE o.workspace_id = v_job.workspace_id
          AND o.staged_output_id = v_output.id;
        IF FOUND THEN
            RETURN QUERY SELECT 'ALREADY_PUBLISHED'::TEXT, v_artifact_id;
        END IF;
        RETURN QUERY SELECT 'LOST_LEASE'::TEXT, NULL::BIGINT;
        RETURN;
    END IF;
    IF v_output.sha256 IS NULL OR v_output.byte_count IS NULL THEN
        UPDATE public.job_staged_output
        SET state = 'DISCARDED'
        WHERE id = v_output.id;
        RETURN QUERY SELECT 'EXPIRED_OUTPUT'::TEXT, NULL::BIGINT;
        RETURN;
    END IF;

    INSERT INTO public.artifact (
        workspace_id,
        blob_key,
        status,
        byte_count,
        sha256,
        detected_media_type,
        finalized_at
    )
    VALUES (
        v_job.workspace_id,
        v_output.object_key,
        'READY',
        v_output.byte_count,
        v_output.sha256,
        p_detected_media_type,
        v_now
    )
    RETURNING id INTO v_artifact_id;

    UPDATE public.job_staged_output
    SET state = 'ATTACHED',
        verified_at = v_now,
        attached_at = v_now
    WHERE id = v_output.id;

    INSERT INTO public.job_output_artifact (
        workspace_id,
        job_id,
        output_kind,
        staged_output_id,
        artifact_id
    )
    VALUES (
        v_job.workspace_id,
        v_job.id,
        v_output.output_kind,
        v_output.id,
        v_artifact_id
    );

    UPDATE public.job j
    SET state = 'SUCCEEDED',
        lease_owner = NULL,
        lease_expires_at = NULL,
        updated_at = v_now
    WHERE j.id = v_job.id
    RETURNING j.* INTO v_job;
    PERFORM public.worker_append_job_event(
        v_job.workspace_id,
        v_job.id,
        'COMPLETED',
        'Verified output published.');
    RETURN QUERY SELECT 'PUBLISHED'::TEXT, v_artifact_id;
END;
$$;

REVOKE ALL ON FUNCTION public.worker_publish_staged_output(BIGINT, TEXT, BIGINT, TEXT, TEXT)
    FROM PUBLIC, brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_publish_staged_output(BIGINT, TEXT, BIGINT, TEXT, TEXT)
    TO brownie_worker;
