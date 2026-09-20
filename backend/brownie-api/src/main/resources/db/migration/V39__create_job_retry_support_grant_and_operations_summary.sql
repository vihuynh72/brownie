-- Three small things an operator or a member needs once real people use
-- the system: a way to restart work that gave up, a way for a person to let
-- support in for a limited time, and one query that says how the system is
-- doing without showing anybody's content.

-- 1. Restarting a job that ended DEAD or FAILED.
--
-- The API login can only ever touch a job that is still active (its UPDATE
-- policies name those states), which is right for cancelling and resuming
-- and is why a restart has to be a routine. A restart is a new attempt, not
-- a continuation: the attempt counter starts again, the deadline is pushed
-- out, and manual_retry_count goes up by one, which is what gives the run
-- its own request allowance again in the usage ledger while the monthly
-- allowances still see every request ever made. It refuses a job whose
-- document has moved on or is in the trash, because the frozen inputs the
-- job would read no longer describe the document the result would land in.
CREATE FUNCTION retry_dead_job(
    p_workspace_id BIGINT,
    p_job_id BIGINT
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_job public.job%ROWTYPE;
    v_now TIMESTAMPTZ;
    v_event_id BIGINT;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor
    ) THEN
        RETURN 'NOT_FOUND';
    END IF;

    SELECT j.* INTO v_job
    FROM public.job j
    WHERE j.workspace_id = p_workspace_id AND j.id = p_job_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF v_job.state IN ('QUEUED', 'LEASED', 'WAITING_FOR_INPUT', 'CANCEL_REQUESTED') THEN
        RETURN 'ALREADY_ACTIVE';
    END IF;
    IF v_job.state NOT IN ('DEAD', 'FAILED') THEN
        RETURN 'NOT_RETRYABLE';
    END IF;

    IF v_job.resource_type = 'document' THEN
        -- The job first, then its document: the order the worker's own
        -- completion routine takes them in.
        PERFORM 1
        FROM public.document d
        WHERE d.workspace_id = v_job.workspace_id
          AND d.id = v_job.resource_id
          AND d.current_revision_id = v_job.resource_version
          AND d.trashed_at IS NULL
        FOR SHARE;
        IF NOT FOUND THEN
            RETURN 'STALE_TARGET';
        END IF;
    END IF;

    v_now := clock_timestamp();
    UPDATE public.job j
    SET state = 'QUEUED',
        attempt_count = 0,
        manual_retry_count = j.manual_retry_count + 1,
        available_at = v_now,
        deadline_at = v_now + interval '1 hour',
        cancellation_requested_at = NULL,
        lease_owner = NULL,
        lease_expires_at = NULL,
        updated_at = v_now
    WHERE j.id = v_job.id;

    INSERT INTO public.job_event (workspace_id, job_id, sequence, event_type, state, safe_message)
    VALUES (
        v_job.workspace_id,
        v_job.id,
        (SELECT COALESCE(MAX(e.sequence), 0) + 1 FROM public.job_event e WHERE e.job_id = v_job.id),
        'QUEUED',
        'QUEUED',
        'Started again by a member.')
    RETURNING id INTO v_event_id;
    INSERT INTO public.outbox_event (delivery_key, workspace_id, job_id, job_event_id, event_type)
    VALUES (gen_random_uuid(), v_job.workspace_id, v_job.id, v_event_id, 'QUEUED');

    PERFORM public.audit_append(
        v_job.workspace_id, v_actor, 'JOB_RETRIED', 'job', v_job.id,
        jsonb_build_object('manualRetryCount', v_job.manual_retry_count + 1, 'previousState', v_job.state));
    RETURN 'RETRIED';
END;
$$;

REVOKE ALL ON FUNCTION retry_dead_job(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION retry_dead_job(BIGINT, BIGINT) TO brownie_api;

-- 2. Support grants.
--
-- Whoever runs the service sees metadata: states, codes, timings. Reading a
-- workspace's content is different, and is allowed only while that
-- workspace's owner has said so, for a named scope and a bounded time. A
-- grant is a record of that permission: who gave it, for what, until when,
-- and when it was taken back. It holds no free text, so like the audit
-- record it needs no foreign key and can outlive the workspace as ids and
-- times. Nothing in the application reads content on support's behalf
-- today; support_grant_is_active below is the one question any such route
-- must ask before it does.
CREATE TABLE support_grant (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    granted_by_user_id BIGINT NOT NULL,
    scope TEXT NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by_user_id BIGINT,
    CONSTRAINT support_grant_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT support_grant_ids_positive CHECK (workspace_id > 0 AND granted_by_user_id > 0),
    CONSTRAINT support_grant_scope_known CHECK (scope IN ('METADATA', 'CONTENT')),
    -- Seven days at the very most, whatever the application asks for.
    CONSTRAINT support_grant_bounded CHECK (expires_at > granted_at AND expires_at <= granted_at + interval '7 days'),
    CONSTRAINT support_grant_revocation_shape CHECK ((revoked_at IS NULL) = (revoked_by_user_id IS NULL))
);

CREATE INDEX support_grant_workspace_idx ON support_grant (workspace_id, granted_at DESC, id DESC);

ALTER TABLE support_grant ENABLE ROW LEVEL SECURITY;

CREATE POLICY support_grant_member_select ON support_grant
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = support_grant.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

-- Only the workspace's owner, only in their own name, and only starting
-- now: the seven-day ceiling above is measured from granted_at, so a grant
-- the application could date into the future would be a grant of any
-- length. now() here is the same instant the column's default takes.
CREATE POLICY support_grant_owner_insert ON support_grant
    FOR INSERT TO brownie_api
    WITH CHECK (
        granted_by_user_id = current_workspace_user_id()
        AND revoked_at IS NULL
        AND granted_at = now()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = support_grant.workspace_id
              AND wm.user_id = current_workspace_user_id()
              AND wm.role = 'OWNER'
        )
    );

-- The only change a grant ever sees: an open one is taken back, once, by
-- the owner acting as themselves.
CREATE POLICY support_grant_owner_revoke ON support_grant
    FOR UPDATE TO brownie_api
    USING (
        revoked_at IS NULL
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = support_grant.workspace_id
              AND wm.user_id = current_workspace_user_id()
              AND wm.role = 'OWNER'
        )
    )
    WITH CHECK (
        revoked_at IS NOT NULL
        AND revoked_by_user_id = current_workspace_user_id()
    );

REVOKE UPDATE, DELETE, TRUNCATE ON support_grant FROM brownie_api, brownie_worker;
GRANT UPDATE (revoked_at, revoked_by_user_id) ON support_grant TO brownie_api;

-- Whether support may act in this workspace, for this scope, right now. A
-- CONTENT grant covers METADATA too. Internal until a route exists that
-- needs it: no runtime role may execute it.
CREATE FUNCTION support_grant_is_active(
    p_workspace_id BIGINT,
    p_scope TEXT
) RETURNS BOOLEAN
LANGUAGE sql
STABLE
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.support_grant g
        WHERE g.workspace_id = p_workspace_id
          AND g.revoked_at IS NULL
          AND g.granted_at <= clock_timestamp()
          AND g.expires_at > clock_timestamp()
          AND (g.scope = p_scope OR g.scope = 'CONTENT'))
$$;

REVOKE ALL ON FUNCTION support_grant_is_active(BIGINT, TEXT) FROM PUBLIC, brownie_api, brownie_worker;

-- 3. How the system is doing, as counts.
--
-- One row, numbers only, across every workspace: what is waiting, what is
-- stuck, what failed, what is owed, what was spent. It is for whoever
-- operates the database and is executable by no runtime role, because a
-- signed-in customer has no business seeing system-wide figures and the
-- application has no operator sign-in to put it behind.
CREATE FUNCTION operations_summary()
RETURNS TABLE (
    jobs_queued BIGINT,
    jobs_leased BIGINT,
    jobs_waiting_for_input BIGINT,
    jobs_dead_last_24h BIGINT,
    oldest_queued_job_age_seconds BIGINT,
    jobs_leased_past_lease BIGINT,
    trash_entries_open BIGINT,
    trash_entries_overdue BIGINT,
    deletions_failed BIGINT,
    deletions_awaiting_verification BIGINT,
    stored_objects_awaiting_removal BIGINT,
    stored_object_removal_max_attempts INTEGER,
    uploads_in_progress BIGINT,
    scans_running_over_15_minutes BIGINT,
    files_quarantined BIGINT,
    refused_files_with_bytes_still_stored BIGINT,
    model_requests_this_month BIGINT,
    model_cost_this_month_usd NUMERIC,
    model_reservations_open_over_30_minutes BIGINT,
    model_reservations_kept_unsettled_this_month BIGINT,
    support_grants_active BIGINT,
    audit_events_last_24h BIGINT
)
LANGUAGE sql
STABLE
SET search_path = pg_catalog, public
AS $$
    WITH month_start AS (
        SELECT date_trunc('month', clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS at
    )
    SELECT
        (SELECT count(*) FROM public.job WHERE state = 'QUEUED'),
        (SELECT count(*) FROM public.job WHERE state = 'LEASED'),
        (SELECT count(*) FROM public.job WHERE state = 'WAITING_FOR_INPUT'),
        (SELECT count(*) FROM public.job WHERE state = 'DEAD' AND updated_at > clock_timestamp() - interval '24 hours'),
        (SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - min(available_at))::BIGINT, 0)
           FROM public.job WHERE state = 'QUEUED' AND available_at <= clock_timestamp()),
        (SELECT count(*) FROM public.job WHERE state IN ('LEASED', 'CANCEL_REQUESTED') AND lease_expires_at <= clock_timestamp()),
        (SELECT count(*) FROM public.deletion_request WHERE state = 'TRASHED'),
        (SELECT count(*) FROM public.deletion_request WHERE state = 'TRASHED' AND purge_after <= clock_timestamp() - interval '1 hour'),
        (SELECT count(*) FROM public.deletion_request WHERE state = 'TRASHED' AND failed_purge_count > 0),
        (SELECT count(*) FROM public.deletion_request WHERE state = 'PURGED'),
        (SELECT count(*) FROM public.deletion_blob_task WHERE state = 'PENDING'),
        (SELECT COALESCE(max(attempt_count), 0) FROM public.deletion_blob_task WHERE state = 'PENDING'),
        (SELECT count(*) FROM public.artifact WHERE status = 'UPLOADING'),
        (SELECT count(*) FROM public.artifact WHERE status = 'SCANNING'
            AND COALESCE(scan_started_at, finalized_at, created_at) <= clock_timestamp() - interval '15 minutes'),
        (SELECT count(*) FROM public.artifact WHERE status = 'QUARANTINED'),
        (SELECT count(*) FROM public.artifact WHERE status = 'REJECTED' AND payload_removed_at IS NULL),
        (SELECT count(*) FROM public.model_usage u, month_start m WHERE u.created_at >= m.at),
        (SELECT COALESCE(sum(public.model_usage_counted_cost(u.state, u.reserved_cost_usd, u.actual_cost_usd)), 0)
           FROM public.model_usage u, month_start m WHERE u.created_at >= m.at),
        (SELECT count(*) FROM public.model_usage WHERE state = 'RESERVED' AND created_at <= clock_timestamp() - interval '30 minutes'),
        (SELECT count(*) FROM public.model_usage u, month_start m WHERE u.state = 'RETAINED' AND u.created_at >= m.at),
        (SELECT count(*) FROM public.support_grant
            WHERE revoked_at IS NULL AND granted_at <= clock_timestamp() AND expires_at > clock_timestamp()),
        (SELECT count(*) FROM public.audit_event WHERE occurred_at > clock_timestamp() - interval '24 hours')
$$;

REVOKE ALL ON FUNCTION operations_summary() FROM PUBLIC, brownie_api, brownie_worker;
