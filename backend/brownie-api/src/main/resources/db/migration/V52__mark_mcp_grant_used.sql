-- Stamping "last used" on an OAuth grant. The personal-token path hides
-- the same write inside mcp_resolve_personal_token, which both looks the
-- grant up and touches last_used_at; the access-token resolver reads the
-- grant through ordinary tenant-scoped SQL instead, so the touch gets
-- its own SECURITY DEFINER function for the resolver to call once it
-- holds a live grant.
--
-- The throttle mirrors the personal-token stamp on purpose: once a
-- minute is as fresh as "last used" needs to be, and a chat app's
-- chatty reconnects should not turn into a write per call.

CREATE OR REPLACE FUNCTION mcp_mark_grant_used(p_grant_id BIGINT)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    UPDATE public.mcp_grant g
    SET last_used_at = clock_timestamp()
    WHERE g.id = p_grant_id
      AND g.revoked_at IS NULL
      AND (g.last_used_at IS NULL OR g.last_used_at < clock_timestamp() - interval '1 minute');
END;
$$;

REVOKE ALL ON FUNCTION mcp_mark_grant_used(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION mcp_mark_grant_used(BIGINT) TO brownie_api;
