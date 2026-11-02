-- The free allowance a workspace open to anyone gets: a small limit of its
-- own each UTC day, and a shared monthly pool for all of them, so people
-- who were invited cannot be crowded out by strangers.

ALTER TABLE workspace ADD COLUMN open_allowance BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN workspace.open_allowance IS
    'Whether this workspace shares the open daily/monthly allowance strangers get, instead of the invited people''s budget.';
ALTER TABLE model_usage ADD COLUMN open_allowance BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN model_usage.open_allowance IS
    'True when this row was reserved on an open workspace -- the shared free monthly pool counts it even if the workspace''s own flag is later cleared.';

-- Set by the application at every sign-in, from the invitation list. It
-- only touches the signed-in person's own personal workspace, so a request
-- on someone's behalf can never change anyone else's allowance.
CREATE FUNCTION set_workspace_open_allowance(p_workspace_id BIGINT, p_open BOOLEAN) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    UPDATE public.workspace
    SET open_allowance = p_open
    WHERE id = p_workspace_id AND is_personal AND owner_user_id = public.current_workspace_user_id();
    RETURN FOUND;
END;
$$;

REVOKE ALL ON FUNCTION set_workspace_open_allowance(BIGINT, BOOLEAN) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION set_workspace_open_allowance(BIGINT, BOOLEAN) TO brownie_api;

-- The reserve function gains the open day and month limits: the day check
-- is the workspace's own spend since midnight UTC, the month check the
-- summed spend of every open workspace since the month's start.
DROP FUNCTION model_usage_reserve(BIGINT, BIGINT, TEXT, BIGINT, INTEGER, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, INTEGER, NUMERIC, NUMERIC, NUMERIC);
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
    p_global_month_limit_usd NUMERIC,
    p_open_day_limit_usd NUMERIC,
    p_open_month_limit_usd NUMERIC
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
    v_day_start TIMESTAMPTZ;
    v_run_requests INTEGER;
    v_run_cost NUMERIC;
    v_workspace_cost NUMERIC;
    v_workspace_day_cost NUMERIC;
    v_open_month_cost NUMERIC;
    v_global_cost NUMERIC;
    v_open BOOLEAN;
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
    IF (p_open_day_limit_usd IS NULL) <> (p_open_month_limit_usd IS NULL) THEN
        RAISE EXCEPTION 'Every usage limit must be positive.';
    END IF;
    IF p_open_day_limit_usd IS NOT NULL AND (p_open_day_limit_usd <= 0 OR p_open_month_limit_usd <= 0) THEN
        RAISE EXCEPTION 'Every usage limit must be positive.';
    END IF;

    PERFORM pg_advisory_xact_lock(7341001);
    -- Read once, after the wait for the lock, and used for both the month
    -- that is checked and the row that is written. The column's default is
    -- the transaction's start, which can be the month before.
    v_now := clock_timestamp();
    v_month_start := date_trunc('month', v_now AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
    v_day_start := date_trunc('day', v_now AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
    -- The workspace's own flag, read once under the lock and written on the
    -- row itself: clearing the flag later never takes this spend out of the
    -- shared pool below.
    SELECT COALESCE(w.open_allowance, false) INTO v_open
    FROM public.workspace w
    WHERE w.id = p_workspace_id;

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

    -- The open allowance, on top of the global cap: a day's own spend, then
    -- the month's shared spend across every workspace that is open.
    IF p_open_day_limit_usd IS NOT NULL AND v_open THEN
        SELECT COALESCE(sum(public.model_usage_counted_cost(u.state, u.reserved_cost_usd, u.actual_cost_usd)), 0)
        INTO v_workspace_day_cost
        FROM public.model_usage u
        WHERE u.workspace_id = p_workspace_id AND u.created_at >= v_day_start;
        IF v_workspace_day_cost + p_cost_usd > p_open_day_limit_usd THEN
            outcome := 'OPEN_DAY_LIMIT';
            usage_id := NULL;
            RETURN NEXT;
            RETURN;
        END IF;

        SELECT COALESCE(sum(public.model_usage_counted_cost(u.state, u.reserved_cost_usd, u.actual_cost_usd)), 0)
        INTO v_open_month_cost
        FROM public.model_usage u
        WHERE u.created_at >= v_month_start AND u.open_allowance;
        IF v_open_month_cost + p_cost_usd > p_open_month_limit_usd THEN
            outcome := 'OPEN_MONTH_LIMIT';
            usage_id := NULL;
            RETURN NEXT;
            RETURN;
        END IF;
    END IF;

    INSERT INTO public.model_usage (
        workspace_id, requested_by_user_id, purpose, job_id, run_epoch, model_name, prompt_version, rate_card,
        reserved_input_tokens, reserved_output_tokens, reserved_cost_usd, open_allowance, created_at)
    VALUES (
        p_workspace_id, p_user_id, p_purpose, p_job_id, p_run_epoch, p_model_name, p_prompt_version, p_rate_card,
        p_input_tokens, p_output_tokens, p_cost_usd, v_open, v_now)
    RETURNING id INTO usage_id;
    outcome := 'RESERVED';
    RETURN NEXT;
END;
$$;

REVOKE ALL ON FUNCTION model_usage_reserve(BIGINT, BIGINT, TEXT, BIGINT, INTEGER, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, INTEGER, NUMERIC, NUMERIC, NUMERIC, NUMERIC, NUMERIC)
    FROM PUBLIC, brownie_api, brownie_worker;

-- The member-facing reserve keeps its checks and gains the two limits as
-- pass-throughs, defaulting to none so a caller that does not set them
-- changes nothing.
DROP FUNCTION reserve_member_model_usage(BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, NUMERIC, NUMERIC, NUMERIC, TEXT);
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
    p_global_month_limit_usd NUMERIC,
    p_purpose TEXT DEFAULT 'ASSIST',
    p_open_day_limit_usd NUMERIC DEFAULT NULL,
    p_open_month_limit_usd NUMERIC DEFAULT NULL
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
        p_workspace_id, v_actor, p_purpose, NULL, 0, p_model_name, p_prompt_version, p_rate_card,
        p_input_tokens, p_output_tokens, p_cost_usd, 1, p_run_limit_usd, p_workspace_month_limit_usd,
        p_global_month_limit_usd, p_open_day_limit_usd, p_open_month_limit_usd) r;
END;
$$;

REVOKE ALL ON FUNCTION reserve_member_model_usage(BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, NUMERIC, NUMERIC, NUMERIC, TEXT, NUMERIC, NUMERIC)
    FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION reserve_member_model_usage(BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, NUMERIC, NUMERIC, NUMERIC, TEXT, NUMERIC, NUMERIC)
    TO brownie_api;

-- The worker-facing reserve gains the same two pass-throughs.
DROP FUNCTION public.worker_reserve_model_usage(BIGINT, TEXT, BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, INTEGER, NUMERIC, NUMERIC, NUMERIC);
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
    p_global_month_limit_usd NUMERIC,
    p_open_day_limit_usd NUMERIC DEFAULT NULL,
    p_open_month_limit_usd NUMERIC DEFAULT NULL
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
        p_run_max_requests, p_run_limit_usd, p_workspace_month_limit_usd, p_global_month_limit_usd,
        p_open_day_limit_usd, p_open_month_limit_usd) r;
END;
$$;

REVOKE ALL ON FUNCTION public.worker_reserve_model_usage(BIGINT, TEXT, BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, INTEGER, NUMERIC, NUMERIC, NUMERIC, NUMERIC, NUMERIC)
    FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_reserve_model_usage(BIGINT, TEXT, BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, INTEGER, NUMERIC, NUMERIC, NUMERIC, NUMERIC, NUMERIC)
    TO brownie_worker;
