-- What the application has asked the model provider to do, and what that
-- cost, one row per physical request. A row is written before the request
-- is sent (RESERVED, holding the most the request could cost) and closed
-- when the answer is in: SETTLED with what the provider actually billed,
-- or RETAINED at the full reservation when nobody can say what happened to
-- the request (a lost response, or a process that died mid-call), because
-- the provider may still charge for it.
--
-- Until now each of these figures lived in memory for the length of one
-- call. The cap on a run was therefore a cap on one attempt of one step of
-- a run, and the monthly allowance was not enforced at all. Here every
-- limit is checked against the rows themselves, inside the database and
-- under one lock, so two requests arriving together cannot both be the
-- one that still fits.
--
-- Rows carry ids, counts and amounts only. They have no foreign key to the
-- workspace, the person or the job: money already spent still counts
-- against the month after the workspace that spent it has been deleted,
-- and a job's rows go with its document.

-- How many times a person has restarted a job by hand. A run's requests are
-- counted per restart, so a restart gets a run's allowance again while the
-- monthly limits still see every request.
ALTER TABLE job ADD COLUMN manual_retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE job ADD CONSTRAINT job_manual_retry_count_nonnegative CHECK (manual_retry_count >= 0);

CREATE TABLE model_usage (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    requested_by_user_id BIGINT NOT NULL,
    purpose TEXT NOT NULL,
    job_id BIGINT,
    run_epoch INTEGER NOT NULL DEFAULT 0,
    model_name TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    rate_card TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'RESERVED',
    reserved_input_tokens INTEGER NOT NULL,
    reserved_output_tokens INTEGER NOT NULL,
    reserved_cost_usd NUMERIC(12, 6) NOT NULL,
    actual_input_tokens INTEGER,
    actual_output_tokens INTEGER,
    actual_cost_usd NUMERIC(12, 6),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ,
    CONSTRAINT model_usage_ids_positive CHECK (workspace_id > 0 AND requested_by_user_id > 0 AND (job_id IS NULL OR job_id > 0)),
    CONSTRAINT model_usage_purpose_known CHECK (purpose IN ('GENERATION', 'ASSIST')),
    -- A generation request belongs to a job; an Assist request is made on the person's own request and has none.
    CONSTRAINT model_usage_purpose_job_shape CHECK ((purpose = 'GENERATION') = (job_id IS NOT NULL)),
    CONSTRAINT model_usage_state_known CHECK (state IN ('RESERVED', 'SETTLED', 'RETAINED')),
    CONSTRAINT model_usage_text_not_blank CHECK (
        char_length(btrim(model_name)) BETWEEN 1 AND 200
        AND char_length(btrim(prompt_version)) BETWEEN 1 AND 200
        AND char_length(btrim(rate_card)) BETWEEN 1 AND 200
    ),
    CONSTRAINT model_usage_reserved_nonnegative CHECK (
        reserved_input_tokens >= 0 AND reserved_output_tokens >= 0 AND reserved_cost_usd >= 0 AND run_epoch >= 0
    ),
    CONSTRAINT model_usage_state_shape CHECK (
        (state = 'RESERVED' AND closed_at IS NULL AND actual_cost_usd IS NULL
            AND actual_input_tokens IS NULL AND actual_output_tokens IS NULL)
        -- The IS NOT NULL tests are not redundant: NULL >= 0 is NULL, and a
        -- CHECK that evaluates to NULL passes, so without them a settled row
        -- could carry no figures at all and count as nothing spent.
        OR (state = 'SETTLED' AND closed_at IS NOT NULL
            AND actual_cost_usd IS NOT NULL AND actual_cost_usd >= 0
            AND actual_input_tokens IS NOT NULL AND actual_input_tokens >= 0
            AND actual_output_tokens IS NOT NULL AND actual_output_tokens >= 0)
        OR (state = 'RETAINED' AND closed_at IS NOT NULL AND actual_cost_usd IS NULL
            AND actual_input_tokens IS NULL AND actual_output_tokens IS NULL)
    )
);

CREATE INDEX model_usage_workspace_month_idx ON model_usage (workspace_id, created_at);
CREATE INDEX model_usage_created_at_idx ON model_usage (created_at);
CREATE INDEX model_usage_job_idx ON model_usage (job_id, run_epoch) WHERE job_id IS NOT NULL;
CREATE INDEX model_usage_open_idx ON model_usage (created_at) WHERE state = 'RESERVED';

ALTER TABLE model_usage ENABLE ROW LEVEL SECURITY;

-- A member may read their own workspace's rows. Nobody writes the table
-- directly: a figure the spender could edit is not a ledger.
CREATE POLICY model_usage_member_select ON model_usage
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = model_usage.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON model_usage FROM brownie_api, brownie_worker;

-- What a row counts for against a limit: what was billed once that is
-- known, and the full reservation until then and whenever it never will be.
CREATE FUNCTION model_usage_counted_cost(p_state TEXT, p_reserved NUMERIC, p_actual NUMERIC)
RETURNS NUMERIC
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE WHEN p_state = 'SETTLED' THEN p_actual ELSE p_reserved END
$$;

-- Decides whether one more request fits, and records it if it does. Three
-- limits, earliest reached wins: this run (requests and dollars, counted
-- across every attempt and resume of the job since its last manual
-- restart), this workspace this calendar month, and everyone this calendar
-- month. Months are UTC so the boundary does not depend on who is asking.
-- The advisory lock serializes every reservation in the system; at two
-- concurrent paid requests that is nothing, and it is what makes the
-- global limit exact. Internal: no runtime role may execute it.
CREATE FUNCTION model_usage_reserve(
    p_workspace_id BIGINT,
    p_user_id BIGINT,
    p_purpose TEXT,
    p_job_id BIGINT,
    p_run_epoch INTEGER,
    p_model_name TEXT,
    p_prompt_version TEXT,
    p_rate_card TEXT,
    p_input_tokens INTEGER,
    p_output_tokens INTEGER,
    p_cost_usd NUMERIC,
    p_run_max_requests INTEGER,
    p_run_limit_usd NUMERIC,
    p_workspace_month_limit_usd NUMERIC,
    p_global_month_limit_usd NUMERIC
) RETURNS TABLE (
    outcome TEXT,
    usage_id BIGINT
)
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_now TIMESTAMPTZ;
    v_month_start TIMESTAMPTZ;
    v_run_requests INTEGER;
    v_run_cost NUMERIC;
    v_workspace_cost NUMERIC;
    v_global_cost NUMERIC;
BEGIN
    IF p_input_tokens IS NULL OR p_input_tokens < 0 OR p_output_tokens IS NULL OR p_output_tokens < 0
            OR p_cost_usd IS NULL OR p_cost_usd < 0 THEN
        RAISE EXCEPTION 'A reservation needs non-negative token and cost estimates.';
    END IF;
    IF p_run_max_requests IS NULL OR p_run_max_requests < 1
            OR p_run_limit_usd IS NULL OR p_run_limit_usd <= 0
            OR p_workspace_month_limit_usd IS NULL OR p_workspace_month_limit_usd <= 0
            OR p_global_month_limit_usd IS NULL OR p_global_month_limit_usd <= 0 THEN
        RAISE EXCEPTION 'Every usage limit must be positive.';
    END IF;

    PERFORM pg_advisory_xact_lock(7341001);
    -- Read once, after the wait for the lock, and used for both the month
    -- that is checked and the row that is written. The column's default is
    -- the transaction's start, which can be the month before.
    v_now := clock_timestamp();
    v_month_start := date_trunc('month', v_now AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';

    IF p_job_id IS NULL THEN
        v_run_requests := 0;
        v_run_cost := 0;
    ELSE
        SELECT count(*), COALESCE(sum(public.model_usage_counted_cost(u.state, u.reserved_cost_usd, u.actual_cost_usd)), 0)
        INTO v_run_requests, v_run_cost
        FROM public.model_usage u
        WHERE u.job_id = p_job_id AND u.run_epoch = p_run_epoch;
    END IF;
    IF v_run_requests >= p_run_max_requests OR v_run_cost + p_cost_usd > p_run_limit_usd THEN
        outcome := 'RUN_LIMIT';
        usage_id := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    SELECT COALESCE(sum(public.model_usage_counted_cost(u.state, u.reserved_cost_usd, u.actual_cost_usd)), 0)
    INTO v_workspace_cost
    FROM public.model_usage u
    WHERE u.workspace_id = p_workspace_id AND u.created_at >= v_month_start;
    IF v_workspace_cost + p_cost_usd > p_workspace_month_limit_usd THEN
        outcome := 'WORKSPACE_MONTH_LIMIT';
        usage_id := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    SELECT COALESCE(sum(public.model_usage_counted_cost(u.state, u.reserved_cost_usd, u.actual_cost_usd)), 0)
    INTO v_global_cost
    FROM public.model_usage u
    WHERE u.created_at >= v_month_start;
    IF v_global_cost + p_cost_usd > p_global_month_limit_usd THEN
        outcome := 'GLOBAL_MONTH_LIMIT';
        usage_id := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    INSERT INTO public.model_usage (
        workspace_id, requested_by_user_id, purpose, job_id, run_epoch, model_name, prompt_version, rate_card,
        reserved_input_tokens, reserved_output_tokens, reserved_cost_usd, created_at)
    VALUES (
        p_workspace_id, p_user_id, p_purpose, p_job_id, p_run_epoch, p_model_name, p_prompt_version, p_rate_card,
        p_input_tokens, p_output_tokens, p_cost_usd, v_now)
    RETURNING id INTO usage_id;
    outcome := 'RESERVED';
    RETURN NEXT;
END;
$$;

-- Closes a reservation exactly once. SETTLED needs the billed figures;
-- RETAINED takes none. Closing a row that is already closed changes
-- nothing and answers false, so a retried close is harmless. Internal.
CREATE FUNCTION model_usage_close(
    p_usage_id BIGINT,
    p_workspace_id BIGINT,
    p_state TEXT,
    p_input_tokens INTEGER,
    p_output_tokens INTEGER,
    p_cost_usd NUMERIC
) RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    -- A settlement that cannot say what was billed is an unknown outcome,
    -- and an unknown outcome keeps the amount that was held.
    IF p_state = 'SETTLED' AND (p_input_tokens IS NULL OR p_output_tokens IS NULL OR p_cost_usd IS NULL
            OR p_input_tokens < 0 OR p_output_tokens < 0 OR p_cost_usd < 0) THEN
        p_state := 'RETAINED';
    END IF;
    IF p_state = 'SETTLED' THEN
        UPDATE public.model_usage u
        SET state = 'SETTLED', closed_at = clock_timestamp(),
            actual_input_tokens = p_input_tokens, actual_output_tokens = p_output_tokens, actual_cost_usd = p_cost_usd
        WHERE u.id = p_usage_id AND u.workspace_id = p_workspace_id AND u.state = 'RESERVED';
    ELSIF p_state = 'RETAINED' THEN
        UPDATE public.model_usage u
        SET state = 'RETAINED', closed_at = clock_timestamp()
        WHERE u.id = p_usage_id AND u.workspace_id = p_workspace_id AND u.state = 'RESERVED';
    ELSE
        RAISE EXCEPTION 'A reservation is closed as SETTLED or RETAINED.';
    END IF;
    RETURN FOUND;
END;
$$;

REVOKE ALL ON FUNCTION model_usage_counted_cost(TEXT, NUMERIC, NUMERIC) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION model_usage_reserve(BIGINT, BIGINT, TEXT, BIGINT, INTEGER, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, INTEGER, NUMERIC, NUMERIC, NUMERIC)
    FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION model_usage_close(BIGINT, BIGINT, TEXT, INTEGER, INTEGER, NUMERIC) FROM PUBLIC, brownie_api, brownie_worker;

-- For the API: a request a person makes directly (Assist). The spender is
-- whoever the transaction says is acting, never a caller-supplied id.
CREATE FUNCTION reserve_member_model_usage(
    p_workspace_id BIGINT,
    p_model_name TEXT,
    p_prompt_version TEXT,
    p_rate_card TEXT,
    p_input_tokens INTEGER,
    p_output_tokens INTEGER,
    p_cost_usd NUMERIC,
    p_run_limit_usd NUMERIC,
    p_workspace_month_limit_usd NUMERIC,
    p_global_month_limit_usd NUMERIC
) RETURNS TABLE (
    outcome TEXT,
    usage_id BIGINT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor
    ) THEN
        outcome := 'NOT_PERMITTED';
        usage_id := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    RETURN QUERY
    SELECT r.outcome, r.usage_id
    FROM public.model_usage_reserve(
        p_workspace_id, v_actor, 'ASSIST', NULL, 0, p_model_name, p_prompt_version, p_rate_card,
        p_input_tokens, p_output_tokens, p_cost_usd, 1, p_run_limit_usd, p_workspace_month_limit_usd,
        p_global_month_limit_usd) r;
END;
$$;

CREATE FUNCTION close_member_model_usage(
    p_workspace_id BIGINT,
    p_usage_id BIGINT,
    p_state TEXT,
    p_input_tokens INTEGER,
    p_output_tokens INTEGER,
    p_cost_usd NUMERIC
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.model_usage u
        WHERE u.id = p_usage_id AND u.workspace_id = p_workspace_id
          AND u.requested_by_user_id = v_actor AND u.purpose = 'ASSIST'
    ) THEN
        RETURN FALSE;
    END IF;
    RETURN public.model_usage_close(p_usage_id, p_workspace_id, p_state, p_input_tokens, p_output_tokens, p_cost_usd);
END;
$$;

-- What a member may know about the allowance: what their own workspace has
-- used this month against its limit, and whether everyone's allowance is
-- used up. The global figure itself is never returned; how much other
-- people spent is not this person's to see.
CREATE FUNCTION member_model_usage_summary(
    p_workspace_id BIGINT,
    p_global_month_limit_usd NUMERIC,
    p_next_request_usd NUMERIC
) RETURNS TABLE (
    workspace_month_cost_usd NUMERIC,
    workspace_month_requests BIGINT,
    global_allowance_exhausted BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_month_start TIMESTAMPTZ;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor
    ) THEN
        RETURN;
    END IF;
    IF p_next_request_usd IS NULL OR p_next_request_usd <= 0 THEN
        RAISE EXCEPTION 'The next request''s cost must be positive.';
    END IF;
    v_month_start := date_trunc('month', clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';

    SELECT COALESCE(sum(public.model_usage_counted_cost(u.state, u.reserved_cost_usd, u.actual_cost_usd)), 0), count(*)
    INTO workspace_month_cost_usd, workspace_month_requests
    FROM public.model_usage u
    WHERE u.workspace_id = p_workspace_id AND u.created_at >= v_month_start;

    -- The same comparison a reservation makes, so "used up" here means
    -- exactly "the next request would be refused", not "the sum happens to
    -- equal the limit", which reservations almost never let it do.
    SELECT COALESCE(sum(public.model_usage_counted_cost(u.state, u.reserved_cost_usd, u.actual_cost_usd)), 0)
               + p_next_request_usd > p_global_month_limit_usd
    INTO global_allowance_exhausted
    FROM public.model_usage u
    WHERE u.created_at >= v_month_start;

    RETURN NEXT;
END;
$$;

-- For the worker: a request made on behalf of a job it holds. The job row
-- supplies the workspace, the person who asked for the run, and how many
-- times it has been restarted by hand; the worker supplies only estimates
-- and limits, and only while its lease on that job is live.
CREATE FUNCTION public.worker_reserve_model_usage(
    p_job_id BIGINT,
    p_worker_id TEXT,
    p_fencing_token BIGINT,
    p_model_name TEXT,
    p_prompt_version TEXT,
    p_rate_card TEXT,
    p_input_tokens INTEGER,
    p_output_tokens INTEGER,
    p_cost_usd NUMERIC,
    p_run_max_requests INTEGER,
    p_run_limit_usd NUMERIC,
    p_workspace_month_limit_usd NUMERIC,
    p_global_month_limit_usd NUMERIC
) RETURNS TABLE (
    outcome TEXT,
    usage_id BIGINT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job public.job%ROWTYPE;
    v_now TIMESTAMPTZ;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker usage routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    SELECT j.* INTO v_job FROM public.job j WHERE j.id = p_job_id;
    v_now := clock_timestamp();
    IF NOT FOUND
            OR v_job.state <> 'LEASED'
            OR v_job.cancellation_requested_at IS NOT NULL
            OR v_job.lease_owner IS DISTINCT FROM p_worker_id
            OR v_job.fencing_token IS DISTINCT FROM p_fencing_token
            OR v_job.lease_expires_at IS NULL
            OR v_job.lease_expires_at <= v_now
            OR v_job.deadline_at <= v_now THEN
        outcome := 'LOST_LEASE';
        usage_id := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    RETURN QUERY
    SELECT r.outcome, r.usage_id
    FROM public.model_usage_reserve(
        v_job.workspace_id, v_job.requested_by_user_id, 'GENERATION', v_job.id, v_job.manual_retry_count,
        p_model_name, p_prompt_version, p_rate_card, p_input_tokens, p_output_tokens, p_cost_usd,
        p_run_max_requests, p_run_limit_usd, p_workspace_month_limit_usd, p_global_month_limit_usd) r;
END;
$$;

-- Closing does not ask for a live lease: the money was spent whether or not
-- the lease survived the call, and recording that must never be refused.
CREATE FUNCTION public.worker_close_model_usage(
    p_job_id BIGINT,
    p_usage_id BIGINT,
    p_state TEXT,
    p_input_tokens INTEGER,
    p_output_tokens INTEGER,
    p_cost_usd NUMERIC
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_workspace_id BIGINT;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker usage routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    SELECT u.workspace_id INTO v_workspace_id
    FROM public.model_usage u
    WHERE u.id = p_usage_id AND u.job_id = p_job_id AND u.purpose = 'GENERATION';
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;
    RETURN public.model_usage_close(p_usage_id, v_workspace_id, p_state, p_input_tokens, p_output_tokens, p_cost_usd);
END;
$$;

-- A reservation nobody closed belongs to a process that died between
-- asking and recording. Nobody knows whether the provider served it, so it
-- is kept at its full amount rather than dropped. Returns how many.
CREATE FUNCTION public.worker_retain_stale_model_usage(
    p_older_than_millis BIGINT,
    p_limit INTEGER
) RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_retained INTEGER;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker usage routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    -- Longer than any single request can take, so a call still in flight is never closed under its caller.
    IF p_older_than_millis IS NULL OR p_older_than_millis < 600000 THEN
        RAISE EXCEPTION 'A reservation cannot be called stale before ten minutes have passed.';
    END IF;
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 512 THEN
        RAISE EXCEPTION 'The reconciliation batch size must be between one and 512.';
    END IF;

    UPDATE public.model_usage u
    SET state = 'RETAINED', closed_at = clock_timestamp()
    WHERE u.id IN (
        SELECT c.id FROM public.model_usage c
        WHERE c.state = 'RESERVED'
          AND c.created_at <= clock_timestamp() - make_interval(secs => p_older_than_millis / 1000.0)
        ORDER BY c.id
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED);
    GET DIAGNOSTICS v_retained = ROW_COUNT;
    RETURN v_retained;
END;
$$;

REVOKE ALL ON FUNCTION reserve_member_model_usage(BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, NUMERIC, NUMERIC, NUMERIC)
    FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION close_member_model_usage(BIGINT, BIGINT, TEXT, INTEGER, INTEGER, NUMERIC) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION member_model_usage_summary(BIGINT, NUMERIC, NUMERIC) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION reserve_member_model_usage(BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, NUMERIC, NUMERIC, NUMERIC) TO brownie_api;
GRANT EXECUTE ON FUNCTION close_member_model_usage(BIGINT, BIGINT, TEXT, INTEGER, INTEGER, NUMERIC) TO brownie_api;
GRANT EXECUTE ON FUNCTION member_model_usage_summary(BIGINT, NUMERIC, NUMERIC) TO brownie_api;

REVOKE ALL ON FUNCTION public.worker_reserve_model_usage(BIGINT, TEXT, BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, INTEGER, NUMERIC, NUMERIC, NUMERIC)
    FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_close_model_usage(BIGINT, BIGINT, TEXT, INTEGER, INTEGER, NUMERIC) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_retain_stale_model_usage(BIGINT, INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_reserve_model_usage(BIGINT, TEXT, BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, INTEGER, NUMERIC, NUMERIC, NUMERIC) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_close_model_usage(BIGINT, BIGINT, TEXT, INTEGER, INTEGER, NUMERIC) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_retain_stale_model_usage(BIGINT, INTEGER) TO brownie_worker;
