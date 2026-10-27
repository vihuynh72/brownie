-- Voice transcription bills the member the same way Assist does, under its
-- own purpose so the ledger can tell the requests apart. The reserve and
-- close functions gain a purpose parameter with the old value as its
-- default, keeping every existing call working and letting transcription
-- write its own rows.

ALTER TABLE model_usage DROP CONSTRAINT model_usage_purpose_known;
ALTER TABLE model_usage ADD CONSTRAINT model_usage_purpose_known
    CHECK (purpose IN ('GENERATION', 'ASSIST', 'TRANSCRIPTION'));

DROP FUNCTION reserve_member_model_usage(BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, NUMERIC, NUMERIC, NUMERIC);
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
    p_purpose TEXT DEFAULT 'ASSIST'
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
        p_global_month_limit_usd) r;
END;
$$;

DROP FUNCTION close_member_model_usage(BIGINT, BIGINT, TEXT, INTEGER, INTEGER, NUMERIC);
CREATE FUNCTION close_member_model_usage(
    p_workspace_id BIGINT,
    p_usage_id BIGINT,
    p_state TEXT,
    p_input_tokens INTEGER,
    p_output_tokens INTEGER,
    p_cost_usd NUMERIC,
    p_purpose TEXT DEFAULT 'ASSIST'
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
          AND u.requested_by_user_id = v_actor AND u.purpose = p_purpose
    ) THEN
        RETURN FALSE;
    END IF;
    RETURN public.model_usage_close(p_usage_id, p_workspace_id, p_state, p_input_tokens, p_output_tokens, p_cost_usd);
END;
$$;

REVOKE ALL ON FUNCTION reserve_member_model_usage(BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, NUMERIC, NUMERIC, NUMERIC, TEXT)
    FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION reserve_member_model_usage(BIGINT, TEXT, TEXT, TEXT, INTEGER, INTEGER, NUMERIC, NUMERIC, NUMERIC, NUMERIC, TEXT)
    TO brownie_api;
REVOKE ALL ON FUNCTION close_member_model_usage(BIGINT, BIGINT, TEXT, INTEGER, INTEGER, NUMERIC, TEXT)
    FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION close_member_model_usage(BIGINT, BIGINT, TEXT, INTEGER, INTEGER, NUMERIC, TEXT)
    TO brownie_api;
