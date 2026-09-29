-- Changes Brownie makes in a person's own outside account, and only when
-- that person approved that exact change: a new file in their Drive, a new
-- event on their calendar, or text added to a Google Doc Brownie made for
-- them.
--
-- Everything here is shaped by two facts about such a change. It cannot be
-- taken back by Brownie once it has happened, so it happens only through an
-- approval bound to the exact payload the person saw, re-checked under lock
-- at the moment it starts. And a request to the provider can be lost after it
-- was sent, so every change is recorded before it leaves, carries whatever
-- the provider offers to recognise it again, and is never sent a second time
-- until it is known that the first did not happen.
--
-- The runtime login may read its person's own rows and propose a new action;
-- every other change is one of the routines below, which is where the rules
-- of the lifecycle live. The worker has no access at all: nothing here runs
-- unattended.

-- Two more kinds of connection, each agreed to on its own and each carrying
-- only its own permission: saving new files to Drive (and adding to Google
-- Docs Brownie saved), and creating calendar events. Reading keeps its own
-- connections, whose words ("Brownie only reads") stay true.
ALTER TABLE connector_connection DROP CONSTRAINT connector_connection_access_known;
ALTER TABLE connector_connection ADD CONSTRAINT connector_connection_access_known
    CHECK (access IN ('DRIVE_FILES', 'CALENDAR_EVENTS', 'DRIVE_SAVING', 'CALENDAR_EVENT_CREATION'));

-- So that an action can name a receipt of its own document and no other.
ALTER TABLE export_receipt ADD CONSTRAINT export_receipt_workspace_document_id_key UNIQUE (workspace_id, document_id, id);

-- Which kind of connection each kind of action is carried out through.
CREATE FUNCTION action_type_access(p_type TEXT) RETURNS TEXT
LANGUAGE sql
IMMUTABLE
SET search_path = pg_catalog, public
AS $$
    SELECT CASE p_type
        WHEN 'DRIVE_SAVE_FILE' THEN 'DRIVE_SAVING'
        WHEN 'DRIVE_SAVE_AS_GOOGLE_DOC' THEN 'DRIVE_SAVING'
        WHEN 'GOOGLE_DOC_APPEND' THEN 'DRIVE_SAVING'
        WHEN 'CALENDAR_CREATE_EVENT' THEN 'CALENDAR_EVENT_CREATION'
    END
$$;

-- One proposed change and what became of it.
--
-- payload_canonical is the exact text the person was shown, in one canonical
-- JSON form, and payload_hash its SHA-256; the table itself refuses a hash
-- that does not match the text, and neither can ever be changed. sibling_key
-- names the change itself (its kind, where it goes and what it contains), so
-- that a second proposal of the same change cannot be sent while the first
-- might already have happened. The columns from required_revision_id to
-- target_external_id repeat, typed, the facts the approval depends on, so
-- that the routine that starts a change can check them under lock.
-- provider_key is whatever the provider accepts to recognise a repeated
-- request (a file id reserved in advance, a chosen event id), fixed when the
-- action is proposed and used by every attempt.
CREATE TABLE action_request (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    action_type TEXT NOT NULL,
    payload_canonical TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    sibling_key TEXT NOT NULL,
    required_revision_id BIGINT,
    export_receipt_id BIGINT,
    target_action_id BIGINT,
    target_external_id TEXT,
    provider_key TEXT,
    state TEXT NOT NULL DEFAULT 'AWAITING_APPROVAL',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    approved_at TIMESTAMPTZ,
    approval_expires_at TIMESTAMPTZ,
    current_attempt_id BIGINT,
    lease_expires_at TIMESTAMPTZ,
    external_id TEXT,
    external_link TEXT,
    verification TEXT,
    -- For a conversion: how many of the filled-in values were looked for in
    -- the converted text, and how many were found.
    check_total INTEGER,
    check_found INTEGER,
    failure_reason TEXT,
    outcome_acknowledged_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT action_request_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT action_request_member_fk
        FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_member (workspace_id, user_id),
    CONSTRAINT action_request_document_fk
        FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id),
    CONSTRAINT action_request_connection_fk
        FOREIGN KEY (workspace_id, connection_id) REFERENCES connector_connection (workspace_id, id),
    CONSTRAINT action_request_revision_fk
        FOREIGN KEY (workspace_id, document_id, required_revision_id) REFERENCES document_revision (workspace_id, document_id, id),
    CONSTRAINT action_request_export_receipt_fk
        FOREIGN KEY (workspace_id, document_id, export_receipt_id) REFERENCES export_receipt (workspace_id, document_id, id),
    CONSTRAINT action_request_type_known CHECK (action_type IN (
        'DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC', 'CALENDAR_CREATE_EVENT', 'GOOGLE_DOC_APPEND')),
    CONSTRAINT action_request_state_known CHECK (state IN (
        'AWAITING_APPROVAL', 'APPROVED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'OUTCOME_UNKNOWN', 'RECONCILING', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT action_request_payload_bounded CHECK (char_length(payload_canonical) BETWEEN 2 AND 262144),
    CONSTRAINT action_request_payload_hash_matches CHECK (
        payload_hash ~ '^[0-9a-f]{64}$'
        AND payload_hash = encode(sha256(convert_to(payload_canonical, 'UTF8')), 'hex')),
    CONSTRAINT action_request_sibling_key_format CHECK (sibling_key ~ '^[0-9a-f]{64}$'),
    -- Identifiers the provider chose or accepts: letters, digits, '-' and '_'
    -- only. The length is checked apart because a pattern counts to 255 at most.
    CONSTRAINT action_request_provider_ids_shape CHECK (
        (provider_key IS NULL OR (char_length(provider_key) BETWEEN 5 AND 1024 AND provider_key ~ '^[A-Za-z0-9_-]+$'))
        AND (target_external_id IS NULL OR (char_length(target_external_id) BETWEEN 1 AND 1024 AND target_external_id ~ '^[A-Za-z0-9_-]+$'))
        AND (external_id IS NULL OR (char_length(external_id) BETWEEN 1 AND 1024 AND external_id ~ '^[A-Za-z0-9_-]+$'))),
    -- Only ever a page at the provider, shown to the person as a link.
    CONSTRAINT action_request_link_https CHECK (
        external_link IS NULL OR (char_length(external_link) <= 2048 AND external_link ~ '^https://')),
    -- What each kind needs to be checked, and nothing it does not.
    CONSTRAINT action_request_type_shape CHECK (
        CASE action_type
            WHEN 'DRIVE_SAVE_FILE' THEN provider_key IS NOT NULL AND required_revision_id IS NOT NULL
                AND export_receipt_id IS NOT NULL AND target_action_id IS NULL AND target_external_id IS NULL
            WHEN 'DRIVE_SAVE_AS_GOOGLE_DOC' THEN provider_key IS NULL AND required_revision_id IS NOT NULL
                AND export_receipt_id IS NOT NULL AND target_action_id IS NULL AND target_external_id IS NULL
            WHEN 'CALENDAR_CREATE_EVENT' THEN provider_key IS NOT NULL AND required_revision_id IS NULL
                AND export_receipt_id IS NULL AND target_action_id IS NULL AND target_external_id IS NULL
            WHEN 'GOOGLE_DOC_APPEND' THEN provider_key IS NULL AND required_revision_id IS NOT NULL
                AND export_receipt_id IS NOT NULL AND target_action_id IS NOT NULL AND target_external_id IS NOT NULL
        END),
    -- A proposal can be approved for half an hour; an approval can be
    -- carried out, or tried again, for a quarter of an hour.
    CONSTRAINT action_request_proposal_lifetime CHECK (expires_at = created_at + interval '30 minutes'),
    CONSTRAINT action_request_approval_shape CHECK (
        (approved_at IS NULL) = (approval_expires_at IS NULL)
        AND (approved_at IS NULL OR approval_expires_at = approved_at + interval '15 minutes')
        AND (state NOT IN ('APPROVED', 'EXECUTING', 'SUCCEEDED', 'OUTCOME_UNKNOWN', 'RECONCILING') OR approved_at IS NOT NULL)),
    -- An attempt holds the action exactly while it is talking to the provider.
    CONSTRAINT action_request_lease_shape CHECK (
        (state IN ('EXECUTING', 'RECONCILING')) = (current_attempt_id IS NOT NULL)
        AND (current_attempt_id IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT action_request_verification_known CHECK (verification IS NULL OR verification IN (
        'MATCHED', 'CONVERSION_CHECKED', 'CONVERSION_DIFFERS', 'CONVERSION_UNCHECKED', 'REMOVED_AFTERWARDS', 'MISMATCHED')),
    CONSTRAINT action_request_check_shape CHECK (
        (check_total IS NULL) = (check_found IS NULL)
        AND (check_total IS NULL OR (check_found BETWEEN 0 AND check_total AND check_total <= 10000))
        AND (check_total IS NULL OR verification IN ('CONVERSION_CHECKED', 'CONVERSION_DIFFERS', 'CONVERSION_UNCHECKED'))),
    CONSTRAINT action_request_success_shape CHECK (
        state <> 'SUCCEEDED'
        OR (external_id IS NOT NULL
            AND verification IN ('MATCHED', 'CONVERSION_CHECKED', 'CONVERSION_DIFFERS', 'CONVERSION_UNCHECKED', 'REMOVED_AFTERWARDS'))),
    CONSTRAINT action_request_failure_shape CHECK (
        (state = 'FAILED') = (failure_reason IS NOT NULL)
        AND (failure_reason IS NULL OR failure_reason IN (
            'CONNECTION_CHANGED', 'DOCUMENT_GONE', 'DOCUMENT_CHANGED', 'EXPORT_CHANGED', 'TARGET_CHANGED', 'CONTENT_CHANGED',
            'PROVIDER_REFUSED', 'STORAGE_FULL', 'BLOCKED_BY_ORGANIZATION', 'PERMISSION_REFUSED', 'TARGET_UNAVAILABLE',
            'LIMIT_REACHED', 'READBACK_MISMATCH'))
        AND (verification IS DISTINCT FROM 'MISMATCHED' OR failure_reason = 'READBACK_MISMATCH')),
    CONSTRAINT action_request_finished_shape CHECK (
        (state IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')) = (finished_at IS NOT NULL)),
    CONSTRAINT action_request_acknowledgement_shape CHECK (
        outcome_acknowledged_at IS NULL OR state IN ('OUTCOME_UNKNOWN', 'RECONCILING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX action_request_document_idx ON action_request (workspace_id, document_id, created_at DESC, id DESC);

-- The same change cannot be under way twice, or started again while an
-- earlier one might have happened and the person has not said they checked.
CREATE UNIQUE INDEX action_request_unresolved_change_key
    ON action_request (workspace_id, user_id, sibling_key)
    WHERE state IN ('EXECUTING', 'RECONCILING') OR (state = 'OUTCOME_UNKNOWN' AND outcome_acknowledged_at IS NULL);

-- A provider's recognition key belongs to one action.
CREATE UNIQUE INDEX action_request_provider_key ON action_request (workspace_id, provider_key) WHERE provider_key IS NOT NULL;

-- One talk with the provider about an action: carrying it out, or asking
-- afterwards what became of it. sent_at is set just before a change leaves,
-- so an attempt without it certainly sent nothing. external_id is kept here
-- the moment an answer names one, even by an attempt that has since lost its
-- hold on the action, so that what the provider made can always be found.
-- Nothing a person wrote is kept: Google's status and its reason words only.
CREATE TABLE action_attempt (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    action_id BIGINT NOT NULL,
    attempt_number INTEGER NOT NULL,
    kind TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    outcome TEXT,
    provider_status INTEGER,
    provider_reasons TEXT,
    external_id TEXT,
    result_revision TEXT,
    CONSTRAINT action_attempt_workspace_id_id_key UNIQUE (workspace_id, id),
    CONSTRAINT action_attempt_action_fk
        FOREIGN KEY (workspace_id, action_id) REFERENCES action_request (workspace_id, id),
    CONSTRAINT action_attempt_number_key UNIQUE (action_id, attempt_number),
    CONSTRAINT action_attempt_kind_known CHECK (kind IN ('EXECUTE', 'RECONCILE')),
    CONSTRAINT action_attempt_outcome_known CHECK (outcome IS NULL OR outcome IN ('APPLIED', 'NOT_APPLIED', 'UNKNOWN', 'NOT_SENT')),
    CONSTRAINT action_attempt_finish_shape CHECK ((finished_at IS NULL) = (outcome IS NULL)),
    CONSTRAINT action_attempt_only_execution_sends CHECK (sent_at IS NULL OR kind = 'EXECUTE'),
    CONSTRAINT action_attempt_provider_status_range CHECK (provider_status IS NULL OR provider_status BETWEEN 100 AND 599),
    CONSTRAINT action_attempt_reasons_plain CHECK (
        provider_reasons IS NULL OR (char_length(provider_reasons) <= 600 AND provider_reasons ~ '^[A-Za-z_, ]*$')),
    CONSTRAINT action_attempt_ids_shape CHECK (
        (external_id IS NULL OR (char_length(external_id) BETWEEN 1 AND 1024 AND external_id ~ '^[A-Za-z0-9_-]+$'))
        AND (result_revision IS NULL OR char_length(result_revision) BETWEEN 1 AND 1024))
);

ALTER TABLE action_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE action_attempt ENABLE ROW LEVEL SECURITY;

-- An action is its person's own, as a connection is: sharing a workspace is
-- not sharing a Google account.
CREATE POLICY action_request_own_select ON action_request
    FOR SELECT TO brownie_api
    USING (
        user_id = current_workspace_user_id()
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = action_request.workspace_id
              AND wm.user_id = current_workspace_user_id()
        )
    );

-- Proposed only in the person's own name, by the workspace's owner, through
-- that person's own usable connection of the kind the action needs, for a
-- document that is not in the trash, as a proposal that has not started and
-- has nothing recorded about an outcome. A foreign key does not look through
-- row security, which is why the connection and the document are named here.
CREATE POLICY action_request_owner_propose ON action_request
    FOR INSERT TO brownie_api
    WITH CHECK (
        user_id = current_workspace_user_id()
        AND state = 'AWAITING_APPROVAL'
        AND created_at = now()
        AND approved_at IS NULL AND approval_expires_at IS NULL
        AND current_attempt_id IS NULL AND lease_expires_at IS NULL
        AND external_id IS NULL AND external_link IS NULL
        AND verification IS NULL AND check_total IS NULL AND check_found IS NULL AND failure_reason IS NULL
        AND outcome_acknowledged_at IS NULL AND finished_at IS NULL
        AND EXISTS (
            SELECT 1 FROM workspace_member wm
            WHERE wm.workspace_id = action_request.workspace_id
              AND wm.user_id = current_workspace_user_id()
              AND wm.role = 'OWNER'
        )
        AND EXISTS (
            SELECT 1 FROM connector_connection c
            WHERE c.workspace_id = action_request.workspace_id
              AND c.id = action_request.connection_id
              AND c.user_id = current_workspace_user_id()
              AND c.state = 'ACTIVE'
              AND c.access = action_type_access(action_request.action_type)
        )
        AND EXISTS (
            SELECT 1 FROM document d
            WHERE d.workspace_id = action_request.workspace_id
              AND d.id = action_request.document_id
              AND d.trashed_at IS NULL
        )
    );

CREATE POLICY action_attempt_own_select ON action_attempt
    FOR SELECT TO brownie_api
    USING (
        EXISTS (
            SELECT 1 FROM action_request a
            WHERE a.workspace_id = action_attempt.workspace_id
              AND a.id = action_attempt.action_id
              AND a.user_id = current_workspace_user_id()
        )
    );

-- New tables are given every ordinary privilege by default; these are taken
-- back so that the routines below are the only way anything here changes.
REVOKE UPDATE, DELETE, TRUNCATE ON action_request FROM brownie_api;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON action_attempt FROM brownie_api;
REVOKE ALL ON action_request, action_attempt FROM brownie_worker;

ALTER TABLE audit_event DROP CONSTRAINT audit_event_action_known;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_action_known CHECK (action IN (
    'DOCUMENT_TRASHED', 'DOCUMENT_RESTORED', 'DOCUMENT_DELETED', 'WORKSPACE_DELETED',
    'DOCUMENT_EXPORTED', 'JOB_RETRIED', 'SUPPORT_GRANT_CREATED', 'SUPPORT_GRANT_REVOKED',
    'CONNECTOR_CONNECTED', 'CONNECTOR_DISCONNECTED', 'SOURCE_IMPORTED',
    'EXTERNAL_ACTION_APPROVED', 'EXTERNAL_ACTION_SENT', 'EXTERNAL_ACTION_FINISHED'
));

-- The routines through which an action changes. Each acts for the person
-- named by current_workspace_user_id(), who must be the workspace's owner and
-- the action's own proposer; for anyone else every routine answers as if the
-- action did not exist. Ordinary refusals are answered as codes, not errors,
-- so that what a routine did before refusing (marking a proposal expired, an
-- approval failed) is kept.

-- Starts a change: approves it and claims it for one attempt, or, for an
-- action already approved whose earlier attempt certainly did not happen,
-- claims it again with the same approval. The hash presented must be the
-- hash of the payload the person was shown.
--
-- Locks, in the order imports, disconnecting and both purges take them: the
-- connection, then the document, then the action. Under those locks every
-- fact the approval depended on is checked again: the same connection still
-- usable, the document still there and out of the trash, its revision and
-- its latest export still the ones approved, the Google Doc to add to still
-- one this person had Brownie make with the same account. A fact that no
-- longer holds ends the action for good, so that restoring a document or
-- connecting again cannot bring an old approval back. A connection waiting
-- to be connected again only refuses for now: connecting again brings the
-- same connection back.
CREATE FUNCTION action_claim(
    p_workspace_id BIGINT,
    p_action_id BIGINT,
    p_presented_hash TEXT,
    p_lease_seconds INTEGER
) RETURNS TABLE (
    claim_outcome TEXT,
    claimed_attempt_id BIGINT,
    claimed_lease_expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_action public.action_request%ROWTYPE;
    v_connection_state TEXT;
    v_connection_account TEXT;
    v_document_found BOOLEAN;
    v_document_revision BIGINT;
    v_document_trashed_at TIMESTAMPTZ;
    v_latest_receipt BIGINT;
    v_failure TEXT;
    v_approving BOOLEAN;
    v_now TIMESTAMPTZ;
    v_lease TIMESTAMPTZ;
    v_attempt BIGINT;
BEGIN
    IF p_lease_seconds IS NULL OR p_lease_seconds NOT BETWEEN 60 AND 900 THEN
        RAISE EXCEPTION 'An action is held for between one and fifteen minutes.';
    END IF;
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor AND wm.role = 'OWNER'
    ) THEN
        claim_outcome := 'NOT_FOUND';
        RETURN NEXT;
        RETURN;
    END IF;

    -- Which connection and document to lock; the action is read again under the locks.
    SELECT a.* INTO v_action
    FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.id = p_action_id AND a.user_id = v_actor;
    IF NOT FOUND THEN
        claim_outcome := 'NOT_FOUND';
        RETURN NEXT;
        RETURN;
    END IF;

    SELECT c.state, c.account_id INTO v_connection_state, v_connection_account
    FROM public.connector_connection c
    WHERE c.workspace_id = p_workspace_id AND c.id = v_action.connection_id AND c.user_id = v_actor
    FOR SHARE;
    SELECT d.current_revision_id, d.trashed_at INTO v_document_revision, v_document_trashed_at
    FROM public.document d
    WHERE d.workspace_id = p_workspace_id AND d.id = v_action.document_id
    FOR UPDATE;
    v_document_found := FOUND;
    SELECT a.* INTO v_action
    FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.id = p_action_id AND a.user_id = v_actor
    FOR UPDATE;
    IF NOT FOUND THEN
        claim_outcome := 'NOT_FOUND';
        RETURN NEXT;
        RETURN;
    END IF;

    v_now := clock_timestamp();
    IF v_action.state = 'AWAITING_APPROVAL' THEN
        IF v_now >= v_action.expires_at THEN
            UPDATE public.action_request a SET state = 'EXPIRED', finished_at = v_now, updated_at = v_now WHERE a.id = v_action.id;
            claim_outcome := 'EXPIRED';
            RETURN NEXT;
            RETURN;
        END IF;
        v_approving := TRUE;
    ELSIF v_action.state = 'APPROVED' THEN
        IF v_now >= v_action.approval_expires_at THEN
            UPDATE public.action_request a SET state = 'EXPIRED', finished_at = v_now, updated_at = v_now WHERE a.id = v_action.id;
            claim_outcome := 'EXPIRED';
            RETURN NEXT;
            RETURN;
        END IF;
        v_approving := FALSE;
    ELSE
        claim_outcome := 'NOT_APPROVABLE';
        RETURN NEXT;
        RETURN;
    END IF;

    IF p_presented_hash IS DISTINCT FROM v_action.payload_hash THEN
        claim_outcome := 'HASH_MISMATCH';
        RETURN NEXT;
        RETURN;
    END IF;

    IF v_connection_state = 'RECONNECT_REQUIRED' THEN
        claim_outcome := 'CONNECTION_UNUSABLE';
        RETURN NEXT;
        RETURN;
    END IF;

    IF v_connection_state IS DISTINCT FROM 'ACTIVE' THEN
        v_failure := 'CONNECTION_CHANGED';
    ELSIF NOT v_document_found OR v_document_trashed_at IS NOT NULL THEN
        v_failure := 'DOCUMENT_GONE';
    ELSIF v_action.required_revision_id IS NOT NULL AND v_document_revision IS DISTINCT FROM v_action.required_revision_id THEN
        v_failure := 'DOCUMENT_CHANGED';
    END IF;
    IF v_failure IS NULL AND v_action.export_receipt_id IS NOT NULL THEN
        SELECT max(r.id) INTO v_latest_receipt
        FROM public.export_receipt r
        WHERE r.workspace_id = p_workspace_id AND r.document_id = v_action.document_id;
        IF v_latest_receipt IS DISTINCT FROM v_action.export_receipt_id THEN
            v_failure := 'EXPORT_CHANGED';
        END IF;
    END IF;
    IF v_failure IS NULL AND v_action.target_action_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM public.action_request t
        JOIN public.connector_connection tc ON tc.workspace_id = t.workspace_id AND tc.id = t.connection_id
        WHERE t.workspace_id = p_workspace_id
          AND t.id = v_action.target_action_id
          AND t.user_id = v_actor
          AND t.action_type = 'DRIVE_SAVE_AS_GOOGLE_DOC'
          AND t.state = 'SUCCEEDED'
          AND t.external_id = v_action.target_external_id
          AND tc.account_id = v_connection_account
    ) THEN
        v_failure := 'TARGET_CHANGED';
    END IF;
    IF v_failure IS NOT NULL THEN
        UPDATE public.action_request a
        SET state = 'FAILED', failure_reason = v_failure, finished_at = v_now, updated_at = v_now
        WHERE a.id = v_action.id;
        claim_outcome := v_failure;
        RETURN NEXT;
        RETURN;
    END IF;

    v_lease := v_now + make_interval(secs => p_lease_seconds);
    BEGIN
        INSERT INTO public.action_attempt (workspace_id, action_id, attempt_number, kind, started_at, lease_expires_at)
        VALUES (
            p_workspace_id,
            v_action.id,
            (SELECT COALESCE(max(t.attempt_number), 0) + 1 FROM public.action_attempt t WHERE t.action_id = v_action.id),
            'EXECUTE',
            v_now,
            v_lease)
        RETURNING id INTO v_attempt;
        UPDATE public.action_request a
        SET state = 'EXECUTING',
            approved_at = CASE WHEN v_approving THEN v_now ELSE a.approved_at END,
            approval_expires_at = CASE WHEN v_approving THEN v_now + interval '15 minutes' ELSE a.approval_expires_at END,
            current_attempt_id = v_attempt,
            lease_expires_at = v_lease,
            updated_at = v_now
        WHERE a.id = v_action.id;
    EXCEPTION WHEN unique_violation THEN
        -- The same change is already under way, or unresolved, through another action.
        claim_outcome := 'SIBLING_UNRESOLVED';
        RETURN NEXT;
        RETURN;
    END;

    IF v_approving THEN
        PERFORM public.audit_append(
            p_workspace_id, v_actor, 'EXTERNAL_ACTION_APPROVED', 'action', v_action.id,
            jsonb_build_object('type', v_action.action_type, 'payloadHash', v_action.payload_hash));
    END IF;
    claim_outcome := 'CLAIMED';
    claimed_attempt_id := v_attempt;
    claimed_lease_expires_at := v_lease;
    RETURN NEXT;
END;
$$;

-- Asked just before a change leaves: is this attempt still the one holding
-- the action, with time left for the whole request? Only then is it marked
-- as sent, once, and written on the audit record, so that a record of the
-- attempt exists whatever happens after it leaves. Any other answer means do
-- not send.
CREATE FUNCTION action_mark_sent(
    p_workspace_id BIGINT,
    p_action_id BIGINT,
    p_attempt_id BIGINT,
    p_min_lease_seconds INTEGER
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_action public.action_request%ROWTYPE;
    v_number INTEGER;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor AND wm.role = 'OWNER'
    ) THEN
        RETURN 'NOT_FOUND';
    END IF;
    SELECT a.* INTO v_action
    FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.id = p_action_id AND a.user_id = v_actor
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'GONE';
    END IF;
    IF v_action.state <> 'EXECUTING' OR v_action.current_attempt_id IS DISTINCT FROM p_attempt_id THEN
        RETURN 'FENCED_OUT';
    END IF;
    IF v_action.lease_expires_at - clock_timestamp() < make_interval(secs => p_min_lease_seconds) THEN
        RETURN 'LEASE_TOO_SHORT';
    END IF;
    UPDATE public.action_attempt t
    SET sent_at = clock_timestamp()
    WHERE t.id = p_attempt_id AND t.action_id = p_action_id AND t.sent_at IS NULL
    RETURNING t.attempt_number INTO v_number;
    IF NOT FOUND THEN
        RETURN 'ALREADY_SENT';
    END IF;
    PERFORM public.audit_append(
        p_workspace_id, v_actor, 'EXTERNAL_ACTION_SENT', 'action', p_action_id,
        jsonb_build_object('type', v_action.action_type, 'attempt', v_number));
    RETURN 'SEND';
END;
$$;

-- Keeps the provider's id for what it made the moment an answer names it: on
-- the attempt always, even one that has since lost its hold on the action,
-- and on the action while this attempt holds it.
CREATE FUNCTION action_record_external_id(
    p_workspace_id BIGINT,
    p_action_id BIGINT,
    p_attempt_id BIGINT,
    p_external_id TEXT
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_action public.action_request%ROWTYPE;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor AND wm.role = 'OWNER'
    ) THEN
        RETURN 'NOT_FOUND';
    END IF;
    SELECT a.* INTO v_action
    FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.id = p_action_id AND a.user_id = v_actor
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'GONE';
    END IF;
    UPDATE public.action_attempt t
    SET external_id = p_external_id
    WHERE t.id = p_attempt_id AND t.action_id = p_action_id AND t.kind = 'EXECUTE'
      AND (t.external_id IS NULL OR t.external_id = p_external_id);
    IF NOT FOUND THEN
        RETURN 'CONFLICT';
    END IF;
    IF v_action.state = 'EXECUTING' AND v_action.current_attempt_id = p_attempt_id AND v_action.external_id IS NULL THEN
        UPDATE public.action_request a SET external_id = p_external_id, updated_at = clock_timestamp() WHERE a.id = p_action_id;
    END IF;
    RETURN 'RECORDED';
END;
$$;

-- Ends an attempt with what became of the change, if it is still the attempt
-- holding the action. APPROVED means "certainly not done, and may be sent
-- again while the approval lasts": for a conversion or an event, whose
-- repetition the provider cannot recognise for certain, that is refused once
-- a send may have taken effect. An approval that has run out ends as EXPIRED
-- instead.
CREATE FUNCTION action_finish(
    p_workspace_id BIGINT,
    p_action_id BIGINT,
    p_attempt_id BIGINT,
    p_state TEXT,
    p_outcome TEXT,
    p_verification TEXT,
    p_failure_reason TEXT,
    p_external_id TEXT,
    p_external_link TEXT,
    p_provider_status INTEGER,
    p_provider_reasons TEXT,
    p_result_revision TEXT,
    p_check_total INTEGER,
    p_check_found INTEGER
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_action public.action_request%ROWTYPE;
    v_attempt public.action_attempt%ROWTYPE;
    v_sent BOOLEAN;
    v_ambiguous BOOLEAN;
    v_next TEXT;
    v_now TIMESTAMPTZ;
BEGIN
    IF p_state IS NULL OR p_state NOT IN ('SUCCEEDED', 'FAILED', 'OUTCOME_UNKNOWN', 'APPROVED') THEN
        RAISE EXCEPTION 'An attempt ends as SUCCEEDED, FAILED, OUTCOME_UNKNOWN or APPROVED, not %.', p_state;
    END IF;
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor AND wm.role = 'OWNER'
    ) THEN
        RETURN 'NOT_FOUND';
    END IF;
    SELECT a.* INTO v_action
    FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.id = p_action_id AND a.user_id = v_actor
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'GONE';
    END IF;
    IF v_action.state NOT IN ('EXECUTING', 'RECONCILING') OR v_action.current_attempt_id IS DISTINCT FROM p_attempt_id THEN
        RETURN 'FENCED_OUT';
    END IF;
    SELECT t.* INTO v_attempt FROM public.action_attempt t WHERE t.id = p_attempt_id;

    v_sent := EXISTS (SELECT 1 FROM public.action_attempt t WHERE t.action_id = p_action_id AND t.sent_at IS NOT NULL);
    -- A send is ambiguous unless the provider's answer showed it was not
    -- processed. Only a file with a reserved id and an addition guarded by a
    -- revision can be sent again after an ambiguous one: the provider itself
    -- refuses the repeat if the first took effect.
    v_ambiguous := EXISTS (
            SELECT 1 FROM public.action_attempt t
            WHERE t.action_id = p_action_id AND t.id <> p_attempt_id AND t.sent_at IS NOT NULL
              AND (t.outcome IS NULL OR t.outcome = 'UNKNOWN'))
        OR (v_attempt.sent_at IS NOT NULL AND p_outcome IS DISTINCT FROM 'NOT_APPLIED');
    IF p_state = 'APPROVED' AND v_ambiguous AND v_action.action_type NOT IN ('DRIVE_SAVE_FILE', 'GOOGLE_DOC_APPEND') THEN
        RAISE EXCEPTION 'A % that may have been sent is never sent again.', v_action.action_type;
    END IF;
    v_now := clock_timestamp();
    v_next := p_state;
    IF p_state = 'APPROVED' AND v_now >= v_action.approval_expires_at THEN
        v_next := 'EXPIRED';
    END IF;

    UPDATE public.action_attempt t
    SET finished_at = v_now,
        outcome = p_outcome,
        provider_status = p_provider_status,
        provider_reasons = p_provider_reasons,
        external_id = COALESCE(t.external_id, p_external_id),
        result_revision = p_result_revision
    WHERE t.id = p_attempt_id;

    UPDATE public.action_request a
    SET state = v_next,
        verification = CASE WHEN v_next IN ('SUCCEEDED', 'FAILED') THEN p_verification END,
        check_total = CASE WHEN v_next = 'SUCCEEDED' THEN p_check_total END,
        check_found = CASE WHEN v_next = 'SUCCEEDED' THEN p_check_found END,
        failure_reason = CASE WHEN v_next = 'FAILED' THEN p_failure_reason END,
        external_id = COALESCE(a.external_id, p_external_id),
        external_link = COALESCE(p_external_link, a.external_link),
        current_attempt_id = NULL,
        lease_expires_at = NULL,
        finished_at = CASE WHEN v_next IN ('SUCCEEDED', 'FAILED', 'EXPIRED') THEN v_now END,
        -- "I have checked" answers an unknown outcome; once the outcome is
        -- known not to have happened, there is nothing left for it to answer.
        outcome_acknowledged_at = CASE WHEN v_next IN ('SUCCEEDED', 'FAILED', 'OUTCOME_UNKNOWN') THEN a.outcome_acknowledged_at END,
        updated_at = v_now
    WHERE a.id = p_action_id;

    -- Every attempt that ends after something was sent is recorded with how
    -- it ended, including "certainly not done" (approved again, or expired),
    -- so that no record of a send is left without an outcome.
    IF v_sent THEN
        PERFORM public.audit_append(
            p_workspace_id, v_actor, 'EXTERNAL_ACTION_FINISHED', 'action', p_action_id,
            jsonb_strip_nulls(jsonb_build_object(
                'type', v_action.action_type, 'state', v_next, 'verification', p_verification,
                'checkTotal', CASE WHEN v_next = 'SUCCEEDED' THEN p_check_total END,
                'checkFound', CASE WHEN v_next = 'SUCCEEDED' THEN p_check_found END,
                'failureReason', CASE WHEN v_next = 'FAILED' THEN p_failure_reason END, 'attemptKind', v_attempt.kind)));
    END IF;
    RETURN 'FINISHED';
END;
$$;

-- Takes an action whose outcome is unknown, or whose attempt stopped holding
-- it without finishing, to ask the provider what became of it. Asking is only
-- reading, so any usable connection of this person for the same account will
-- do, and the offer switch does not matter: a person must always be able to
-- find out what happened. An attempt that was taken over is closed as
-- unknown here, and can no longer finish or send.
CREATE FUNCTION action_claim_reconcile(
    p_workspace_id BIGINT,
    p_action_id BIGINT,
    p_connection_id BIGINT,
    p_lease_seconds INTEGER
) RETURNS TABLE (
    claim_outcome TEXT,
    claimed_attempt_id BIGINT,
    claimed_lease_expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_action public.action_request%ROWTYPE;
    v_account TEXT;
    v_usable BOOLEAN;
    v_now TIMESTAMPTZ;
    v_lease TIMESTAMPTZ;
    v_attempt BIGINT;
BEGIN
    IF p_lease_seconds IS NULL OR p_lease_seconds NOT BETWEEN 60 AND 900 THEN
        RAISE EXCEPTION 'An action is held for between one and fifteen minutes.';
    END IF;
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor AND wm.role = 'OWNER'
    ) THEN
        claim_outcome := 'NOT_FOUND';
        RETURN NEXT;
        RETURN;
    END IF;
    SELECT a.* INTO v_action
    FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.id = p_action_id AND a.user_id = v_actor;
    IF NOT FOUND THEN
        claim_outcome := 'NOT_FOUND';
        RETURN NEXT;
        RETURN;
    END IF;
    SELECT c.account_id INTO v_account
    FROM public.connector_connection c
    WHERE c.workspace_id = p_workspace_id AND c.id = v_action.connection_id;

    -- The connection first, then the action, as everywhere else.
    SELECT TRUE INTO v_usable
    FROM public.connector_connection c
    WHERE c.workspace_id = p_workspace_id
      AND c.id = p_connection_id
      AND c.user_id = v_actor
      AND c.state = 'ACTIVE'
      AND c.access = public.action_type_access(v_action.action_type)
      AND c.account_id = v_account
    FOR SHARE;
    IF v_usable IS NOT TRUE THEN
        claim_outcome := 'CONNECTION_UNUSABLE';
        RETURN NEXT;
        RETURN;
    END IF;
    SELECT a.* INTO v_action
    FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.id = p_action_id AND a.user_id = v_actor
    FOR UPDATE;
    IF NOT FOUND THEN
        claim_outcome := 'NOT_FOUND';
        RETURN NEXT;
        RETURN;
    END IF;

    v_now := clock_timestamp();
    IF NOT (v_action.state = 'OUTCOME_UNKNOWN'
            OR (v_action.state IN ('EXECUTING', 'RECONCILING') AND v_action.lease_expires_at <= v_now)) THEN
        claim_outcome := 'NOT_RECONCILABLE';
        RETURN NEXT;
        RETURN;
    END IF;
    v_lease := v_now + make_interval(secs => p_lease_seconds);
    BEGIN
        IF v_action.current_attempt_id IS NOT NULL THEN
            UPDATE public.action_attempt t
            SET finished_at = v_now, outcome = 'UNKNOWN'
            WHERE t.id = v_action.current_attempt_id AND t.finished_at IS NULL;
        END IF;
        INSERT INTO public.action_attempt (workspace_id, action_id, attempt_number, kind, started_at, lease_expires_at)
        VALUES (
            p_workspace_id,
            v_action.id,
            (SELECT COALESCE(max(t.attempt_number), 0) + 1 FROM public.action_attempt t WHERE t.action_id = v_action.id),
            'RECONCILE',
            v_now,
            v_lease)
        RETURNING id INTO v_attempt;
        UPDATE public.action_request a
        SET state = 'RECONCILING', current_attempt_id = v_attempt, lease_expires_at = v_lease, updated_at = v_now
        WHERE a.id = v_action.id;
    EXCEPTION WHEN unique_violation THEN
        -- The person has since proposed the same change again, and it is under way.
        claim_outcome := 'SIBLING_UNRESOLVED';
        RETURN NEXT;
        RETURN;
    END;
    claim_outcome := 'CLAIMED';
    claimed_attempt_id := v_attempt;
    claimed_lease_expires_at := v_lease;
    RETURN NEXT;
END;
$$;

-- Ends an action that has not started because something it depends on was
-- found changed before any request could be made: the bytes in storage no
-- longer the approved ones, or the Google Doc to add to shared, trashed,
-- edited or gone since it was shown. What the claim would have found first
-- is recorded first, as the claim records it: a proposal or approval that
-- ran out has expired, and one whose connection is no longer the one it was
-- made with ended because the connection changed; a connection waiting to
-- be connected again leaves the action as it is.
CREATE FUNCTION action_fail_before_sending(
    p_workspace_id BIGINT,
    p_action_id BIGINT,
    p_reason TEXT
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_action public.action_request%ROWTYPE;
    v_connection_state TEXT;
    v_now TIMESTAMPTZ;
BEGIN
    IF p_reason IS NULL OR p_reason NOT IN ('TARGET_CHANGED', 'CONTENT_CHANGED') THEN
        RAISE EXCEPTION 'Before sending, an action fails only because its target or its content changed, not %.', p_reason;
    END IF;
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor AND wm.role = 'OWNER'
    ) THEN
        RETURN 'NOT_FOUND';
    END IF;
    -- The connection, then the action, in the order the claim takes them.
    SELECT a.* INTO v_action
    FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.id = p_action_id AND a.user_id = v_actor;
    IF NOT FOUND THEN
        RETURN 'NOT_OPEN';
    END IF;
    SELECT c.state INTO v_connection_state
    FROM public.connector_connection c
    WHERE c.workspace_id = p_workspace_id AND c.id = v_action.connection_id AND c.user_id = v_actor
    FOR SHARE;
    SELECT a.* INTO v_action
    FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.id = p_action_id AND a.user_id = v_actor
    FOR UPDATE;
    IF NOT FOUND OR v_action.state NOT IN ('AWAITING_APPROVAL', 'APPROVED') THEN
        RETURN 'NOT_OPEN';
    END IF;
    v_now := clock_timestamp();
    IF (v_action.state = 'AWAITING_APPROVAL' AND v_now >= v_action.expires_at)
            OR (v_action.state = 'APPROVED' AND v_now >= v_action.approval_expires_at) THEN
        UPDATE public.action_request a SET state = 'EXPIRED', finished_at = v_now, updated_at = v_now WHERE a.id = v_action.id;
        RETURN 'EXPIRED';
    END IF;
    IF v_connection_state = 'RECONNECT_REQUIRED' THEN
        RETURN 'NOT_OPEN';
    END IF;
    UPDATE public.action_request a
    SET state = 'FAILED',
        failure_reason = CASE WHEN v_connection_state IS DISTINCT FROM 'ACTIVE' THEN 'CONNECTION_CHANGED' ELSE p_reason END,
        finished_at = v_now,
        updated_at = v_now
    WHERE a.id = v_action.id;
    RETURN 'FAILED';
END;
$$;

-- Withdraws an action that has not happened: one awaiting approval, or one
-- approved whose every attempt certainly did not take effect (an action whose
-- outcome may be unknown is never approved, so never withdrawn this way).
CREATE FUNCTION action_cancel(
    p_workspace_id BIGINT,
    p_action_id BIGINT
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_now TIMESTAMPTZ;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor AND wm.role = 'OWNER'
    ) THEN
        RETURN 'NOT_FOUND';
    END IF;
    v_now := clock_timestamp();
    UPDATE public.action_request a
    SET state = 'CANCELLED', finished_at = v_now, updated_at = v_now
    WHERE a.workspace_id = p_workspace_id AND a.id = p_action_id AND a.user_id = v_actor
      AND a.state IN ('AWAITING_APPROVAL', 'APPROVED');
    RETURN CASE WHEN FOUND THEN 'CANCELLED' ELSE 'NOT_CANCELLABLE' END;
END;
$$;

-- The person says they have looked for themselves at an outcome Brownie
-- cannot know. The action stays "outcome unknown", which is still true; what
-- changes is that the same change may now be proposed and sent again. An
-- attempt that stopped holding the action without finishing (its lease ran
-- out) is closed as unknown here too, so an action whose account can no
-- longer be reached to ask is never left holding the change for good.
CREATE FUNCTION action_acknowledge_unknown(
    p_workspace_id BIGINT,
    p_action_id BIGINT
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_action public.action_request%ROWTYPE;
    v_now TIMESTAMPTZ;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id AND wm.user_id = v_actor AND wm.role = 'OWNER'
    ) THEN
        RETURN 'NOT_FOUND';
    END IF;
    SELECT a.* INTO v_action
    FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.id = p_action_id AND a.user_id = v_actor
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_UNKNOWN';
    END IF;
    v_now := clock_timestamp();
    IF v_action.state = 'OUTCOME_UNKNOWN' AND v_action.outcome_acknowledged_at IS NULL THEN
        UPDATE public.action_request a
        SET outcome_acknowledged_at = v_now, updated_at = v_now
        WHERE a.id = v_action.id;
        RETURN 'ACKNOWLEDGED';
    END IF;
    IF v_action.state IN ('EXECUTING', 'RECONCILING') AND v_action.lease_expires_at <= v_now THEN
        UPDATE public.action_attempt t
        SET finished_at = v_now, outcome = 'UNKNOWN'
        WHERE t.id = v_action.current_attempt_id AND t.finished_at IS NULL;
        UPDATE public.action_request a
        SET state = 'OUTCOME_UNKNOWN', current_attempt_id = NULL, lease_expires_at = NULL,
            outcome_acknowledged_at = v_now, updated_at = v_now
        WHERE a.id = v_action.id;
        -- As when an attempt finishes: a change that was sent is never left on the record without how it ended.
        IF EXISTS (SELECT 1 FROM public.action_attempt t WHERE t.action_id = v_action.id AND t.sent_at IS NOT NULL) THEN
            PERFORM public.audit_append(
                p_workspace_id, v_actor, 'EXTERNAL_ACTION_FINISHED', 'action', v_action.id,
                jsonb_build_object(
                    'type', v_action.action_type,
                    'state', 'OUTCOME_UNKNOWN',
                    'attemptKind', (SELECT t.kind FROM public.action_attempt t WHERE t.id = v_action.current_attempt_id),
                    'acknowledged', true));
        END IF;
        RETURN 'ACKNOWLEDGED';
    END IF;
    RETURN 'NOT_UNKNOWN';
END;
$$;

REVOKE ALL ON FUNCTION action_type_access(TEXT) FROM PUBLIC, brownie_worker;
REVOKE ALL ON FUNCTION action_claim(BIGINT, BIGINT, TEXT, INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION action_mark_sent(BIGINT, BIGINT, BIGINT, INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION action_record_external_id(BIGINT, BIGINT, BIGINT, TEXT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION action_finish(BIGINT, BIGINT, BIGINT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, INTEGER, TEXT, TEXT, INTEGER, INTEGER)
    FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION action_claim_reconcile(BIGINT, BIGINT, BIGINT, INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION action_fail_before_sending(BIGINT, BIGINT, TEXT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION action_cancel(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION action_acknowledge_unknown(BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
-- The mapping is read by the proposal policy, which runs as the API.
GRANT EXECUTE ON FUNCTION action_type_access(TEXT) TO brownie_api;
GRANT EXECUTE ON FUNCTION action_claim(BIGINT, BIGINT, TEXT, INTEGER) TO brownie_api;
GRANT EXECUTE ON FUNCTION action_mark_sent(BIGINT, BIGINT, BIGINT, INTEGER) TO brownie_api;
GRANT EXECUTE ON FUNCTION action_record_external_id(BIGINT, BIGINT, BIGINT, TEXT) TO brownie_api;
GRANT EXECUTE ON FUNCTION action_finish(BIGINT, BIGINT, BIGINT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, INTEGER, TEXT, TEXT, INTEGER, INTEGER) TO brownie_api;
GRANT EXECUTE ON FUNCTION action_claim_reconcile(BIGINT, BIGINT, BIGINT, INTEGER) TO brownie_api;
GRANT EXECUTE ON FUNCTION action_fail_before_sending(BIGINT, BIGINT, TEXT) TO brownie_api;
GRANT EXECUTE ON FUNCTION action_cancel(BIGINT, BIGINT) TO brownie_api;
GRANT EXECUTE ON FUNCTION action_acknowledge_unknown(BIGINT, BIGINT) TO brownie_api;

-- Deleting for good now also removes a document's actions and waits, like it
-- waits for a running job, while a change is in the air. The four routines
-- below are the ones from before, unchanged except for those lines.

CREATE OR REPLACE FUNCTION retention_purge_document(
    p_request_id BIGINT,
    p_workspace_id BIGINT,
    p_document_id BIGINT
) RETURNS JSONB
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_job_ids BIGINT[];
    v_snapshot_ids BIGINT[];
    v_artifact_ids BIGINT[];
    v_idempotency_ids BIGINT[];
    v_rows BIGINT := 0;
    v_count BIGINT;
    v_blobs BIGINT;
BEGIN
    SELECT COALESCE(array_agg(j.id), '{}') INTO v_job_ids
    FROM public.job j
    WHERE j.workspace_id = p_workspace_id
      AND j.resource_type = 'document'
      AND j.resource_id = p_document_id;

    SELECT COALESCE(array_agg(s.id), '{}') INTO v_snapshot_ids
    FROM public.source_snapshot s
    WHERE s.workspace_id = p_workspace_id
      AND (
          EXISTS (
              SELECT 1 FROM public.document_source ds
              WHERE ds.workspace_id = p_workspace_id
                AND ds.document_id = p_document_id
                AND ds.source_snapshot_id = s.id)
          OR EXISTS (
              SELECT 1 FROM public.generation_run gr
              WHERE gr.workspace_id = p_workspace_id
                AND gr.document_id = p_document_id
                AND gr.source_snapshot_id = s.id)
          OR EXISTS (
              SELECT 1
              FROM public.document_revision_field_evidence fe
              JOIN public.source_span sp ON sp.workspace_id = fe.workspace_id AND sp.id = fe.source_span_id
              WHERE fe.workspace_id = p_workspace_id
                AND fe.document_id = p_document_id
                AND sp.source_snapshot_id = s.id)
          OR EXISTS (
              SELECT 1
              FROM public.document_patch_proposal_evidence pe
              JOIN public.document_patch_proposal pp ON pp.workspace_id = pe.workspace_id AND pp.id = pe.proposal_id
              JOIN public.source_span sp ON sp.workspace_id = pe.workspace_id AND sp.id = pe.source_span_id
              WHERE pe.workspace_id = p_workspace_id
                AND pp.document_id = p_document_id
                AND sp.source_snapshot_id = s.id))
      AND NOT EXISTS (
          SELECT 1 FROM public.document_source ds
          WHERE ds.workspace_id = p_workspace_id
            AND ds.source_snapshot_id = s.id
            AND ds.document_id <> p_document_id)
      AND NOT EXISTS (
          SELECT 1 FROM public.generation_run gr
          WHERE gr.workspace_id = p_workspace_id
            AND gr.source_snapshot_id = s.id
            AND gr.document_id <> p_document_id)
      AND NOT EXISTS (
          SELECT 1
          FROM public.document_revision_field_evidence fe
          JOIN public.source_span sp ON sp.workspace_id = fe.workspace_id AND sp.id = fe.source_span_id
          WHERE fe.workspace_id = p_workspace_id
            AND sp.source_snapshot_id = s.id
            AND fe.document_id <> p_document_id)
      AND NOT EXISTS (
          SELECT 1
          FROM public.document_patch_proposal_evidence pe
          JOIN public.document_patch_proposal pp ON pp.workspace_id = pe.workspace_id AND pp.id = pe.proposal_id
          JOIN public.source_span sp ON sp.workspace_id = pe.workspace_id AND sp.id = pe.source_span_id
          WHERE pe.workspace_id = p_workspace_id
            AND sp.source_snapshot_id = s.id
            AND pp.document_id <> p_document_id)
      AND NOT EXISTS (
          SELECT 1 FROM public.template_version tv
          WHERE tv.workspace_id = p_workspace_id AND tv.source_artifact_id = s.artifact_id)
      AND NOT EXISTS (
          SELECT 1 FROM public.template_example te
          WHERE te.workspace_id = p_workspace_id AND te.source_artifact_id = s.artifact_id);

    SELECT COALESCE(array_agg(DISTINCT owned.artifact_id), '{}') INTO v_artifact_ids
    FROM (
        SELECT c.docx_artifact_id AS artifact_id FROM public.document_compilation c
        WHERE c.workspace_id = p_workspace_id AND c.document_id = p_document_id
        UNION ALL
        SELECT c.pdf_artifact_id FROM public.document_compilation c
        WHERE c.workspace_id = p_workspace_id AND c.document_id = p_document_id
        UNION ALL
        SELECT m.docx_artifact_id FROM public.validation_manifest m
        WHERE m.workspace_id = p_workspace_id AND m.document_id = p_document_id
        UNION ALL
        SELECT m.pdf_artifact_id FROM public.validation_manifest m
        WHERE m.workspace_id = p_workspace_id AND m.document_id = p_document_id AND m.pdf_artifact_id IS NOT NULL
        UNION ALL
        SELECT o.artifact_id FROM public.job_output_artifact o
        WHERE o.workspace_id = p_workspace_id AND o.job_id = ANY (v_job_ids)
        UNION ALL
        SELECT s.artifact_id FROM public.source_snapshot s
        WHERE s.workspace_id = p_workspace_id AND s.id = ANY (v_snapshot_ids)
    ) owned
    -- Any ready file can be attached as a source or taught as a template,
    -- including one this document generated. A file something else still
    -- uses stays, whoever produced it.
    WHERE NOT EXISTS (
              SELECT 1 FROM public.source_snapshot s
              WHERE s.workspace_id = p_workspace_id
                AND s.artifact_id = owned.artifact_id
                AND NOT (s.id = ANY (v_snapshot_ids)))
      AND NOT EXISTS (
              SELECT 1 FROM public.template_version tv
              WHERE tv.workspace_id = p_workspace_id AND tv.source_artifact_id = owned.artifact_id)
      AND NOT EXISTS (
              SELECT 1 FROM public.template_example te
              WHERE te.workspace_id = p_workspace_id AND te.source_artifact_id = owned.artifact_id)
      AND NOT EXISTS (
              SELECT 1 FROM public.template_baseline_render br
              WHERE br.workspace_id = p_workspace_id
                AND owned.artifact_id IN (br.docx_artifact_id, br.pdf_artifact_id))
      AND NOT EXISTS (
              SELECT 1 FROM public.document_compilation c
              WHERE c.workspace_id = p_workspace_id
                AND c.document_id <> p_document_id
                AND owned.artifact_id IN (c.docx_artifact_id, c.pdf_artifact_id))
      AND NOT EXISTS (
              SELECT 1 FROM public.validation_manifest m
              WHERE m.workspace_id = p_workspace_id
                AND m.document_id <> p_document_id
                AND owned.artifact_id IN (m.docx_artifact_id, m.pdf_artifact_id))
      AND NOT EXISTS (
              SELECT 1 FROM public.export_receipt x
              WHERE x.workspace_id = p_workspace_id
                AND x.document_id <> p_document_id
                AND owned.artifact_id IN (x.docx_artifact_id, x.pdf_artifact_id))
      AND NOT EXISTS (
              SELECT 1 FROM public.job_output_artifact o
              WHERE o.workspace_id = p_workspace_id
                AND o.artifact_id = owned.artifact_id
                AND NOT (o.job_id = ANY (v_job_ids)));

    INSERT INTO public.deletion_blob_task (workspace_id, deletion_request_id, object_key)
    SELECT p_workspace_id, p_request_id, keys.object_key
    FROM (
        SELECT a.blob_key AS object_key FROM public.artifact a
        WHERE a.workspace_id = p_workspace_id AND a.id = ANY (v_artifact_ids)
        UNION
        -- A published output's artifact was created under the staged
        -- object's own key, so a key whose artifact is staying must stay too.
        SELECT o.object_key FROM public.job_staged_output o
        WHERE o.workspace_id = p_workspace_id AND o.job_id = ANY (v_job_ids) AND o.cleaned_at IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM public.artifact kept
              WHERE kept.workspace_id = p_workspace_id
                AND kept.blob_key = o.object_key
                AND NOT (kept.id = ANY (v_artifact_ids)))
        UNION
        SELECT 'generation-runs/' || p_workspace_id || '/jobs/' || gr.job_id || '/pending-questions.json'
        FROM public.generation_run gr
        WHERE gr.workspace_id = p_workspace_id AND gr.document_id = p_document_id
        UNION
        SELECT 'generation-runs/' || p_workspace_id || '/jobs/' || gr.job_id || '/resolved-answers.json'
        FROM public.generation_run gr
        WHERE gr.workspace_id = p_workspace_id AND gr.document_id = p_document_id
        UNION
        SELECT 'generation-runs/' || p_workspace_id || '/inputs/' || gr.bundle_hash || '.json'
        FROM public.generation_run gr
        WHERE gr.workspace_id = p_workspace_id
          AND gr.document_id = p_document_id
          AND NOT EXISTS (
              SELECT 1 FROM public.generation_run other
              WHERE other.workspace_id = p_workspace_id
                AND other.bundle_hash = gr.bundle_hash
                AND other.document_id <> p_document_id)
    ) keys
    ON CONFLICT (deletion_request_id, object_key) DO NOTHING;
    GET DIAGNOSTICS v_blobs = ROW_COUNT;

    -- What the person proposed or had Brownie do in their own account for
    -- this document holds what was sent (a file name, an event's text, the
    -- text added to a Doc), so it goes with the document: attempts, then the
    -- actions, then the export receipts they name. What was made in the
    -- person's account stays there. The actions are locked first, as every
    -- routine that writes an attempt locks its action first.
    PERFORM 1 FROM public.action_request a
    WHERE a.workspace_id = p_workspace_id AND a.document_id = p_document_id
    ORDER BY a.id
    FOR UPDATE;
    DELETE FROM public.action_attempt x
    USING public.action_request a
    WHERE a.workspace_id = x.workspace_id AND a.id = x.action_id
      AND a.workspace_id = p_workspace_id AND a.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.action_request x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.export_receipt x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.export_approval x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.validation_manifest x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document_compilation x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.document_patch_proposal_evidence pe
    USING public.document_patch_proposal pp
    WHERE pp.workspace_id = pe.workspace_id AND pp.id = pe.proposal_id
      AND pp.workspace_id = p_workspace_id AND pp.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document_patch_proposal x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.question x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.generation_run x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    SELECT COALESCE(array_agg(r.idempotency_record_id), '{}') INTO v_idempotency_ids
    FROM (
        SELECT cr.idempotency_record_id FROM public.command_receipt cr
        WHERE cr.workspace_id = p_workspace_id AND cr.job_id = ANY (v_job_ids)
        UNION
        SELECT dr.idempotency_record_id FROM public.document_command_receipt dr
        WHERE dr.workspace_id = p_workspace_id AND dr.document_id = p_document_id
    ) r;

    DELETE FROM public.job_output_artifact x WHERE x.workspace_id = p_workspace_id AND x.job_id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.outbox_event x WHERE x.workspace_id = p_workspace_id AND x.job_id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.job_event x WHERE x.workspace_id = p_workspace_id AND x.job_id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.job_staged_output x WHERE x.workspace_id = p_workspace_id AND x.job_id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.command_receipt x WHERE x.workspace_id = p_workspace_id AND x.job_id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.job x WHERE x.workspace_id = p_workspace_id AND x.id = ANY (v_job_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.document_revision_field_evidence x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document_revision_field_state x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document_command_receipt x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.idempotency_record x WHERE x.workspace_id = p_workspace_id AND x.id = ANY (v_idempotency_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document_source x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    -- The document and its revisions point at each other; the pointer goes
    -- first, then every revision in one statement (a parent and its child
    -- leave together, so the self-reference never sees a missing parent).
    UPDATE public.document d SET current_revision_id = NULL
    WHERE d.workspace_id = p_workspace_id AND d.id = p_document_id;
    DELETE FROM public.document_revision x WHERE x.workspace_id = p_workspace_id AND x.document_id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.document x WHERE x.workspace_id = p_workspace_id AND x.id = p_document_id;
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    DELETE FROM public.source_span x WHERE x.workspace_id = p_workspace_id AND x.source_snapshot_id = ANY (v_snapshot_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.source_snapshot x WHERE x.workspace_id = p_workspace_id AND x.id = ANY (v_snapshot_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.extraction_version x WHERE x.workspace_id = p_workspace_id AND x.artifact_id = ANY (v_artifact_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.pdf_extraction_version x WHERE x.workspace_id = p_workspace_id AND x.artifact_id = ANY (v_artifact_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.plain_text_extraction_version x WHERE x.workspace_id = p_workspace_id AND x.artifact_id = ANY (v_artifact_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;
    DELETE FROM public.artifact x WHERE x.workspace_id = p_workspace_id AND x.id = ANY (v_artifact_ids);
    GET DIAGNOSTICS v_count = ROW_COUNT; v_rows := v_rows + v_count;

    RETURN jsonb_build_object(
        'rowsRemoved', v_rows,
        'objectsQueued', v_blobs,
        'jobsRemoved', COALESCE(array_length(v_job_ids, 1), 0),
        'sourcesRemoved', COALESCE(array_length(v_snapshot_ids, 1), 0));
END;
$$;


CREATE OR REPLACE FUNCTION retention_remaining(
    p_request_id BIGINT
) RETURNS TABLE (
    remaining_rows BIGINT,
    pending_objects BIGINT
)
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_request public.deletion_request%ROWTYPE;
BEGIN
    SELECT r.* INTO v_request FROM public.deletion_request r WHERE r.id = p_request_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF v_request.scope = 'DOCUMENT' THEN
        SELECT
            (SELECT count(*) FROM public.document x WHERE x.workspace_id = v_request.workspace_id AND x.id = v_request.target_id)
            + (SELECT count(*) FROM public.document_revision x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_revision_field_evidence x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_revision_field_state x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_command_receipt x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_compilation x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_patch_proposal x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.document_source x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.question x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.generation_run x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.validation_manifest x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.export_approval x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.export_receipt x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.action_request x WHERE x.workspace_id = v_request.workspace_id AND x.document_id = v_request.target_id)
            + (SELECT count(*) FROM public.job x WHERE x.workspace_id = v_request.workspace_id
                   AND x.resource_type = 'document' AND x.resource_id = v_request.target_id)
        INTO remaining_rows;
    ELSE
        SELECT
            (SELECT count(*) FROM public.workspace x WHERE x.id = v_request.workspace_id)
            + (SELECT count(*) FROM public.workspace_member x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.document x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.artifact x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.template x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.job x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.source_snapshot x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.plain_text_extraction_version x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.connector_resource_grant x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.connector_connection x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.action_request x WHERE x.workspace_id = v_request.workspace_id)
            + (SELECT count(*) FROM public.action_attempt x WHERE x.workspace_id = v_request.workspace_id)
        INTO remaining_rows;
    END IF;

    SELECT count(*) INTO pending_objects
    FROM public.deletion_blob_task t
    WHERE t.deletion_request_id = p_request_id AND t.state = 'PENDING';

    RETURN NEXT;
END;
$$;


CREATE OR REPLACE FUNCTION retention_execute_purge(
    p_request_id BIGINT
) RETURNS TEXT
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_request public.deletion_request%ROWTYPE;
    v_live INTEGER;
    v_inventory JSONB;
BEGIN
    SELECT r.* INTO v_request
    FROM public.deletion_request r
    WHERE r.id = p_request_id
    FOR UPDATE;
    IF NOT FOUND OR v_request.state <> 'TRASHED' THEN
        RETURN 'NOT_OPEN';
    END IF;

    -- Every permanent deletion passes through here, whoever asked for it,
    -- so this is where the ledger learns exactly which thing it removed.
    UPDATE public.deletion_request r
    SET target_created_at = CASE v_request.scope
            WHEN 'DOCUMENT' THEN (SELECT d.created_at FROM public.document d
                                  WHERE d.workspace_id = v_request.workspace_id AND d.id = v_request.target_id)
            ELSE (SELECT w.created_at FROM public.workspace w WHERE w.id = v_request.target_id)
        END
    WHERE r.id = p_request_id AND r.target_created_at IS NULL;

    IF v_request.scope = 'DOCUMENT' THEN
        -- Jobs before the document: the worker's completion routine locks a
        -- job and then its document, and taking them in the other order
        -- here could deadlock against it.
        v_live := public.retention_cancel_document_jobs(
            v_request.workspace_id, v_request.target_id, 'Job cancelled because its document was deleted.');
        IF v_live > 0 THEN
            RETURN 'JOBS_STILL_STOPPING';
        END IF;
        PERFORM 1 FROM public.document d
        WHERE d.workspace_id = v_request.workspace_id AND d.id = v_request.target_id
        FOR UPDATE;
        -- A write to the person's own account that is in the air right now is
        -- waited for like a running job: the record of what was sent (the
        -- provider's id, how to find what was made) must not be removed in
        -- the seconds before it is written. A write takes this document's
        -- lock before it starts, so none can begin after this check; asking
        -- about one, or finishing one, takes its action's lock, which is
        -- taken here first, so one already started is seen by the check.
        PERFORM 1 FROM public.action_request a
        WHERE a.workspace_id = v_request.workspace_id AND a.document_id = v_request.target_id
        ORDER BY a.id
        FOR UPDATE;
        IF EXISTS (
            SELECT 1 FROM public.action_request a
            WHERE a.workspace_id = v_request.workspace_id
              AND a.document_id = v_request.target_id
              AND a.state IN ('EXECUTING', 'RECONCILING')
              AND a.lease_expires_at > clock_timestamp()
        ) THEN
            RETURN 'JOBS_STILL_STOPPING';
        END IF;
        v_inventory := public.retention_purge_document(p_request_id, v_request.workspace_id, v_request.target_id);
    ELSE
        v_live := 0;
        PERFORM public.retention_cancel_document_jobs(
            d.workspace_id, d.id, 'Job cancelled because its workspace was deleted.')
        FROM public.document d
        WHERE d.workspace_id = v_request.workspace_id;
        SELECT count(*) INTO v_live
        FROM public.job j
        WHERE j.workspace_id = v_request.workspace_id
          AND j.state IN ('LEASED', 'CANCEL_REQUESTED')
          AND j.lease_expires_at > clock_timestamp();
        IF v_live > 0 THEN
            RETURN 'JOBS_STILL_STOPPING';
        END IF;
        -- The same wait for a write in the air, across the workspace. Its
        -- trash entries, the workspace and then its connections are locked
        -- first, in the order the purge below takes them: a write starts by
        -- taking its connection, so none can begin after this check.
        PERFORM 1
        FROM public.deletion_request r
        WHERE r.workspace_id = v_request.workspace_id AND r.state = 'TRASHED'
        ORDER BY r.id
        FOR UPDATE;
        PERFORM 1 FROM public.workspace w WHERE w.id = v_request.workspace_id FOR UPDATE;
        PERFORM 1
        FROM public.connector_connection c
        WHERE c.workspace_id = v_request.workspace_id
        ORDER BY c.id
        FOR UPDATE;
        PERFORM 1
        FROM public.action_request a
        WHERE a.workspace_id = v_request.workspace_id
        ORDER BY a.id
        FOR UPDATE;
        IF EXISTS (
            SELECT 1 FROM public.action_request a
            WHERE a.workspace_id = v_request.workspace_id
              AND a.state IN ('EXECUTING', 'RECONCILING')
              AND a.lease_expires_at > clock_timestamp()
        ) THEN
            RETURN 'JOBS_STILL_STOPPING';
        END IF;
        v_inventory := public.retention_purge_workspace(p_request_id, v_request.workspace_id);
    END IF;

    UPDATE public.deletion_request r
    SET state = 'PURGED',
        purged_at = clock_timestamp(),
        purge_after = NULL,
        inventory = v_inventory
    WHERE r.id = p_request_id;

    -- The acting member when a person asked; nobody when the worker carried
    -- out trash whose time had run out. Written last, so it exists exactly
    -- when the deletion does.
    PERFORM public.audit_append(
        v_request.workspace_id,
        public.current_workspace_user_id(),
        CASE WHEN v_request.scope = 'DOCUMENT' THEN 'DOCUMENT_DELETED' ELSE 'WORKSPACE_DELETED' END,
        CASE WHEN v_request.scope = 'DOCUMENT' THEN 'document' ELSE 'workspace' END,
        v_request.target_id,
        v_inventory || jsonb_build_object('deletionRequestId', p_request_id));
    RETURN 'PURGED';
END;
$$;


CREATE OR REPLACE FUNCTION delete_workspace(
    p_workspace_id BIGINT
) RETURNS TABLE (
    outcome TEXT,
    request_id BIGINT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor BIGINT;
    v_now TIMESTAMPTZ;
BEGIN
    v_actor := public.current_workspace_user_id();
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1
        FROM public.workspace w
        JOIN public.workspace_member wm ON wm.workspace_id = w.id AND wm.user_id = v_actor
        WHERE w.id = p_workspace_id AND w.owner_user_id = v_actor AND wm.role = 'OWNER'
    ) THEN
        outcome := 'NOT_FOUND';
        request_id := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    PERFORM public.retention_cancel_document_jobs(
        d.workspace_id, d.id, 'Job cancelled because its workspace was deleted.')
    FROM public.document d
    WHERE d.workspace_id = p_workspace_id;
    IF EXISTS (
        SELECT 1 FROM public.job j
        WHERE j.workspace_id = p_workspace_id
          AND j.state IN ('LEASED', 'CANCEL_REQUESTED')
          AND j.lease_expires_at > clock_timestamp()
    ) THEN
        outcome := 'JOBS_STILL_STOPPING';
        request_id := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    -- A write to the person's own account that is in the air is waited for
    -- too, after the locks that stop a new one starting (see
    -- retention_execute_purge, which checks the same again).
    PERFORM 1
    FROM public.deletion_request r
    WHERE r.workspace_id = p_workspace_id AND r.state = 'TRASHED'
    ORDER BY r.id
    FOR UPDATE;
    PERFORM 1 FROM public.workspace w WHERE w.id = p_workspace_id FOR UPDATE;
    PERFORM 1
    FROM public.connector_connection c
    WHERE c.workspace_id = p_workspace_id
    ORDER BY c.id
    FOR UPDATE;
    IF EXISTS (
        SELECT 1 FROM public.action_request a
        WHERE a.workspace_id = p_workspace_id
          AND a.state IN ('EXECUTING', 'RECONCILING')
          AND a.lease_expires_at > clock_timestamp()
    ) THEN
        outcome := 'JOBS_STILL_STOPPING';
        request_id := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    v_now := clock_timestamp();
    INSERT INTO public.deletion_request (workspace_id, scope, target_id, state, requested_by_user_id, requested_at, purge_after)
    VALUES (p_workspace_id, 'WORKSPACE', p_workspace_id, 'TRASHED', v_actor, v_now, v_now)
    RETURNING id INTO request_id;

    outcome := public.retention_execute_purge(request_id);
    IF outcome <> 'PURGED' THEN
        RAISE EXCEPTION 'A workspace deletion that was cleared to proceed answered %.', outcome;
    END IF;
    RETURN NEXT;
END;
$$;


-- CREATE OR REPLACE keeps each routine's owner and permissions; they are
-- restated so that this file alone says who may run them.
REVOKE ALL ON FUNCTION retention_purge_document(BIGINT, BIGINT, BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_remaining(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION retention_execute_purge(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
REVOKE ALL ON FUNCTION delete_workspace(BIGINT) FROM PUBLIC, brownie_api, brownie_worker;
GRANT EXECUTE ON FUNCTION delete_workspace(BIGINT) TO brownie_api;
