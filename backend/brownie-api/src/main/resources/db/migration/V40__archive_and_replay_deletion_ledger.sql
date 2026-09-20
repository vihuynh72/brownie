-- A backup restores what the database held when it was taken, including
-- everything that was deleted for good afterwards. The record that a
-- deletion happened therefore has to exist somewhere a restore does not
-- put back, and has to be applied to a restored database before anybody is
-- let in. This migration gives the deletion ledger both halves: a mark for
-- "this entry has been copied out", and a routine that applies a copied-out
-- entry to a database that has forgotten it.
--
-- Only the worker copies entries out and applies them, as it is the only
-- process that removes a deleted document's stored files.

ALTER TABLE deletion_request ADD COLUMN archived_at TIMESTAMPTZ;

CREATE INDEX deletion_request_unarchived_idx ON deletion_request (id)
    WHERE archived_at IS NULL AND state IN ('PURGED', 'VERIFIED');

-- Hands out entries that were carried out and have not been copied out
-- yet. What it returns is the whole of what an archived entry says: ids,
-- times and counts.
CREATE FUNCTION public.worker_collect_unarchived_deletions(
    p_limit INTEGER
) RETURNS TABLE (
    request_id BIGINT,
    workspace_id BIGINT,
    scope TEXT,
    target_id BIGINT,
    requested_by_user_id BIGINT,
    requested_at TIMESTAMPTZ,
    purged_at TIMESTAMPTZ,
    target_created_at TIMESTAMPTZ,
    inventory TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 256 THEN
        RAISE EXCEPTION 'The archive batch size must be between one and 256.';
    END IF;

    RETURN QUERY
    SELECT r.id, r.workspace_id, r.scope, r.target_id, r.requested_by_user_id, r.requested_at, r.purged_at,
           r.target_created_at, r.inventory::text
    FROM public.deletion_request r
    WHERE r.archived_at IS NULL AND r.state IN ('PURGED', 'VERIFIED')
    ORDER BY r.id
    LIMIT p_limit;
END;
$$;

-- Records that one entry now exists outside the database. Repeating it
-- changes nothing, which is what lets the worker write the copy first and
-- record it second: a crash between the two only means the same copy is
-- written again. If that was the last thing the deletion was waiting for,
-- it is closed here.
CREATE FUNCTION public.worker_mark_deletion_archived(
    p_request_id BIGINT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_marked BOOLEAN;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    UPDATE public.deletion_request r
    SET archived_at = clock_timestamp()
    WHERE r.id = p_request_id AND r.archived_at IS NULL AND r.state IN ('PURGED', 'VERIFIED');
    v_marked := FOUND;
    IF v_marked THEN
        PERFORM public.worker_verify_deletion(p_request_id);
    END IF;
    RETURN v_marked;
END;
$$;

-- Applies one archived entry to this database. In a database that never
-- lost anything the target is already gone and this answers ABSENT. In one
-- restored from a backup taken before the deletion, the target is back,
-- and this removes it again exactly as the original deletion did: the
-- same purge, the same queue of stored objects for the worker to remove.
--
-- The target must be the very thing the entry was written for: the same
-- id, created at the same moment. A restore also winds the id sequences
-- back, so afterwards a new document can be given the id of one that was
-- deleted, and if an older backup is later restored on top of that, an
-- entry from the abandoned history can name an id that belongs to
-- something else entirely. Neither may ever be reached by an entry.
CREATE FUNCTION public.worker_replay_deletion(
    p_scope TEXT,
    p_workspace_id BIGINT,
    p_target_id BIGINT,
    p_requested_by_user_id BIGINT,
    p_requested_at TIMESTAMPTZ,
    p_target_created_at TIMESTAMPTZ
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_request_id BIGINT;
    v_exists BOOLEAN;
    v_outcome TEXT;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_scope IS NULL OR p_scope NOT IN ('DOCUMENT', 'WORKSPACE')
            OR p_workspace_id IS NULL OR p_workspace_id <= 0
            OR p_target_id IS NULL OR p_target_id <= 0
            OR p_requested_by_user_id IS NULL OR p_requested_by_user_id <= 0
            OR p_requested_at IS NULL THEN
        RAISE EXCEPTION 'An archived deletion names a scope, a workspace, a target, who asked and when.';
    END IF;
    -- An entry that does not say when its target was created removed nothing
    -- that can be identified, so there is nothing it can be applied to.
    IF p_target_created_at IS NULL THEN
        RETURN 'ABSENT';
    END IF;
    IF p_scope = 'WORKSPACE' AND p_target_id <> p_workspace_id THEN
        RAISE EXCEPTION 'A workspace deletion targets its own workspace.';
    END IF;

    IF p_scope = 'DOCUMENT' THEN
        SELECT TRUE INTO v_exists
        FROM public.document d
        WHERE d.workspace_id = p_workspace_id AND d.id = p_target_id AND d.created_at = p_target_created_at;
    ELSE
        SELECT TRUE INTO v_exists
        FROM public.workspace w
        WHERE w.id = p_workspace_id AND w.created_at = p_target_created_at;
    END IF;
    IF v_exists IS NOT TRUE THEN
        RETURN 'ABSENT';
    END IF;

    -- The backup may hold the entry as it stood then: still in the trash.
    SELECT r.id INTO v_request_id
    FROM public.deletion_request r
    WHERE r.workspace_id = p_workspace_id AND r.scope = p_scope AND r.target_id = p_target_id AND r.state = 'TRASHED'
    FOR UPDATE;
    IF NOT FOUND THEN
        IF p_scope = 'DOCUMENT' THEN
            PERFORM public.retention_cancel_document_jobs(
                p_workspace_id, p_target_id, 'Job cancelled because its document was deleted.');
            UPDATE public.document d SET trashed_at = clock_timestamp()
            WHERE d.workspace_id = p_workspace_id AND d.id = p_target_id AND d.trashed_at IS NULL;
        END IF;
        INSERT INTO public.deletion_request (workspace_id, scope, target_id, state, requested_by_user_id, requested_at, purge_after)
        VALUES (p_workspace_id, p_scope, p_target_id, 'TRASHED', p_requested_by_user_id, p_requested_at, clock_timestamp())
        RETURNING id INTO v_request_id;
    END IF;

    v_outcome := public.retention_execute_purge(v_request_id);
    IF v_outcome = 'PURGED' THEN
        RETURN 'REPLAYED';
    END IF;
    RETURN v_outcome;
END;
$$;

REVOKE ALL ON FUNCTION public.worker_collect_unarchived_deletions(INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_mark_deletion_archived(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION public.worker_replay_deletion(TEXT, BIGINT, BIGINT, BIGINT, TIMESTAMPTZ, TIMESTAMPTZ) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_collect_unarchived_deletions(INTEGER) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_mark_deletion_archived(BIGINT) TO brownie_worker;
GRANT EXECUTE ON FUNCTION public.worker_replay_deletion(TEXT, BIGINT, BIGINT, BIGINT, TIMESTAMPTZ, TIMESTAMPTZ) TO brownie_worker;

-- A deletion is only called verified once it is also recorded outside the
-- database: from then on nothing that can happen to this database alone
-- brings the deleted thing back for good. Both routines are otherwise as
-- they were.
CREATE OR REPLACE FUNCTION public.worker_verify_deletion(
    p_request_id BIGINT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_state TEXT;
    v_archived_at TIMESTAMPTZ;
    v_remaining_rows BIGINT;
    v_pending_objects BIGINT;
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;

    SELECT r.state, r.archived_at INTO v_state, v_archived_at
    FROM public.deletion_request r
    WHERE r.id = p_request_id
    FOR UPDATE;
    IF NOT FOUND OR v_state <> 'PURGED' THEN
        RETURN COALESCE(v_state = 'VERIFIED', FALSE);
    END IF;
    IF v_archived_at IS NULL THEN
        RETURN FALSE;
    END IF;

    SELECT rem.remaining_rows, rem.pending_objects INTO v_remaining_rows, v_pending_objects
    FROM public.retention_remaining(p_request_id) rem;
    IF v_remaining_rows > 0 OR v_pending_objects > 0 THEN
        RETURN FALSE;
    END IF;

    UPDATE public.deletion_request r
    SET state = 'VERIFIED', verified_at = clock_timestamp()
    WHERE r.id = p_request_id;
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION public.worker_collect_unverified_deletions(
    p_limit INTEGER
) RETURNS SETOF BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF session_user <> 'brownie_worker' THEN
        RAISE EXCEPTION 'Worker retention routines require the brownie_worker login.'
            USING ERRCODE = '42501';
    END IF;
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 256 THEN
        RAISE EXCEPTION 'The verification batch size must be between one and 256.';
    END IF;

    RETURN QUERY
    SELECT r.id
    FROM public.deletion_request r
    WHERE r.state = 'PURGED'
      AND r.archived_at IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM public.deletion_blob_task t
          WHERE t.deletion_request_id = r.id AND t.state = 'PENDING')
    ORDER BY r.id
    LIMIT p_limit;
END;
$$;
