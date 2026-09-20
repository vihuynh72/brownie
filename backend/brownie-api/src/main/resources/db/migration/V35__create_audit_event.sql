-- A record of the few actions that someone may later need to account for:
-- what was removed and by whom, what left the system as an export, when
-- work was retried by hand, when support was let in. It holds ids, an
-- action name, a time, the request's correlation id and a small bag of
-- counts or codes; never a title, a filename, a field value or any text a
-- person wrote.
--
-- Like the deletion ledger that follows it, it has no foreign key to the
-- workspace or the person: a row saying a workspace was deleted is only
-- worth anything once that workspace is gone. actor_user_id is NULL when
-- the system acted on its own (the worker carrying out expired trash).
--
-- It is append-only by permission, not by convention: both runtime logins
-- lose UPDATE, DELETE and TRUNCATE, so the only ways a row ever leaves are
-- the expiry routine below and the table owner.
CREATE TABLE audit_event (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    actor_user_id BIGINT,
    action TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id BIGINT NOT NULL,
    correlation_id TEXT,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_event_ids_positive CHECK (workspace_id > 0 AND resource_id > 0 AND (actor_user_id IS NULL OR actor_user_id > 0)),
    CONSTRAINT audit_event_action_known CHECK (action IN (
        'DOCUMENT_TRASHED', 'DOCUMENT_RESTORED', 'DOCUMENT_DELETED', 'WORKSPACE_DELETED',
        'DOCUMENT_EXPORTED', 'JOB_RETRIED', 'SUPPORT_GRANT_CREATED', 'SUPPORT_GRANT_REVOKED'
    )),
    CONSTRAINT audit_event_resource_type_format CHECK (
        resource_type ~ '^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$' AND char_length(resource_type) <= 100
    ),
    CONSTRAINT audit_event_correlation_id_length CHECK (correlation_id IS NULL OR char_length(correlation_id) BETWEEN 1 AND 128),
    CONSTRAINT audit_event_details_object CHECK (jsonb_typeof(details) = 'object'),
    -- Small on purpose: counts and codes fit; a document's content does not.
    CONSTRAINT audit_event_details_bounded CHECK (pg_column_size(details) <= 2048)
);

CREATE INDEX audit_event_workspace_idx ON audit_event (workspace_id, occurred_at DESC, id DESC);
CREATE INDEX audit_event_occurred_at_idx ON audit_event (occurred_at);

ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;

CREATE POLICY audit_event_member_select ON audit_event
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = audit_event.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

-- The API may record an event only as the person it is acting for, in a
-- workspace that person belongs to: it cannot write a row in anyone
-- else's name, and it cannot write one in the system's name at all.
CREATE POLICY audit_event_actor_insert ON audit_event
    FOR INSERT TO brownie_api
    WITH CHECK (
        actor_user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = audit_event.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM brownie_api, brownie_worker;

-- For the database routines that perform an audited action themselves
-- (deletion, a manual retry): the event is written in the same transaction
-- as the action, so neither can exist without the other. The correlation
-- id is whatever the API put in app.correlation_id for this transaction,
-- and nothing when the worker is the caller. Internal: no runtime role may
-- execute it.
CREATE FUNCTION audit_append(
    p_workspace_id BIGINT,
    p_actor_user_id BIGINT,
    p_action TEXT,
    p_resource_type TEXT,
    p_resource_id BIGINT,
    p_details JSONB
) RETURNS VOID
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    INSERT INTO public.audit_event (workspace_id, actor_user_id, action, resource_type, resource_id, correlation_id, details)
    VALUES (
        p_workspace_id,
        p_actor_user_id,
        p_action,
        p_resource_type,
        p_resource_id,
        NULLIF(left(current_setting('app.correlation_id', true), 128), ''),
        COALESCE(p_details, '{}'::jsonb));
END;
$$;

REVOKE ALL ON FUNCTION audit_append(BIGINT, BIGINT, TEXT, TEXT, BIGINT, JSONB) FROM PUBLIC, brownie_api, brownie_worker;

-- Audit rows are kept for a bounded time and then go. The worker removes
-- them in batches; the age is passed in so the period stays configuration.
CREATE FUNCTION public.worker_expire_audit_events(
    p_older_than_millis BIGINT,
    p_limit INTEGER
) RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_removed INTEGER;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    -- A floor of one day, so a misconfigured period cannot erase the record of what just happened.
    IF p_older_than_millis IS NULL OR p_older_than_millis < 86400000 THEN
        RAISE EXCEPTION 'Audit events are kept for at least one day.';
    END IF;
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 5000 THEN
        RAISE EXCEPTION 'The expiry batch size must be between one and 5000.';
    END IF;

    DELETE FROM public.audit_event a
    WHERE a.id IN (
        SELECT e.id FROM public.audit_event e
        WHERE e.occurred_at <= clock_timestamp() - make_interval(secs => p_older_than_millis / 1000.0)
        ORDER BY e.id
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED);
    GET DIAGNOSTICS v_removed = ROW_COUNT;
    RETURN v_removed;
END;
$$;

REVOKE ALL ON FUNCTION public.worker_expire_audit_events(BIGINT, INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_expire_audit_events(BIGINT, INTEGER) TO brownie_worker;
