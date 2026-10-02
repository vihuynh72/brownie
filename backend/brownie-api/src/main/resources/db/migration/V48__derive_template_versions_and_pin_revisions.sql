-- A template version can now be made from another one, and a document can
-- move from one version of its template to another.
--
-- Until now an activated version never changed and a document stayed on the
-- version it was started from. A person correcting the fill spots of an open
-- document (adding one where they point, renaming one, taking one away) gets
-- a new activated version made from the one the document is on, and the
-- document moves to it with its values. Nothing about an existing version
-- changes: the new one is a new row.

-- Where a version came from. derivation_key is the SHA-256 of the version it
-- was made from, the changes asked for and the reader that read the file, so
-- the same request made again finds the version it already made instead of
-- making a second one; derivation is the change list itself (kinds, field
-- ids, places), kept for history and for undoing it. The three are set
-- together or not at all: a version made from a Word file directly has none.
ALTER TABLE template_version
    ADD COLUMN derived_from_version_id BIGINT,
    ADD COLUMN derivation_key TEXT,
    ADD COLUMN derivation JSONB,
    ADD CONSTRAINT template_version_derived_from_fk
        FOREIGN KEY (workspace_id, template_id, derived_from_version_id)
        REFERENCES template_version (workspace_id, template_id, id),
    ADD CONSTRAINT template_version_derivation_together CHECK (
        (derived_from_version_id IS NULL) = (derivation_key IS NULL)
        AND (derivation_key IS NULL) = (derivation IS NULL)
    ),
    ADD CONSTRAINT template_version_derivation_key_format CHECK (
        derivation_key IS NULL OR derivation_key ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT template_version_derivation_object CHECK (
        derivation IS NULL OR jsonb_typeof(derivation) = 'object'
    );

CREATE UNIQUE INDEX template_version_derivation_idx
    ON template_version (template_id, derived_from_version_id, derivation_key)
    WHERE derivation_key IS NOT NULL;

-- Every revision now says which version of the template its content was
-- written against, so an earlier revision is read, compiled and restored
-- against the version it belonged to. No document has ever moved before
-- this migration, so each existing revision belongs to its document's
-- version.
ALTER TABLE document_revision ADD COLUMN template_version_id BIGINT;

UPDATE document_revision r
SET template_version_id = d.template_version_id
FROM document d
WHERE d.workspace_id = r.workspace_id
  AND d.id = r.document_id;

ALTER TABLE document_revision
    ALTER COLUMN template_version_id SET NOT NULL,
    ADD CONSTRAINT document_revision_template_version_fk
        FOREIGN KEY (workspace_id, template_version_id) REFERENCES template_version (workspace_id, id);

CREATE INDEX document_revision_template_version_idx ON document_revision (workspace_id, template_version_id);

-- A revision written without a version is written against the version its
-- document is on now: the first revision of a new document, and every edit
-- that does not move the document. Only a move names the version itself.
CREATE FUNCTION document_revision_default_template_version()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.template_version_id IS NULL THEN
        SELECT d.template_version_id
        INTO NEW.template_version_id
        FROM public.document d
        WHERE d.workspace_id = NEW.workspace_id
          AND d.id = NEW.document_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER document_revision_default_template_version_before_insert
    BEFORE INSERT ON document_revision
    FOR EACH ROW
    EXECUTE FUNCTION document_revision_default_template_version();

-- The document's version follows its current revision. Moving the pointer
-- moves the version too, and only to an activated version of the document's
-- own template; a revision on any other version is refused the same way a
-- revision that is not the direct next one is.
CREATE OR REPLACE FUNCTION advance_document_current_revision(
    p_workspace_id BIGINT,
    p_document_id BIGINT,
    p_expected_revision_id BIGINT,
    p_next_revision_id BIGINT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF p_workspace_id <= 0 OR p_document_id <= 0 OR p_next_revision_id <= 0 THEN
        RETURN FALSE;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.workspace_member wm
        WHERE wm.workspace_id = p_workspace_id
          AND wm.user_id = public.current_workspace_user_id()
    ) THEN
        RETURN FALSE;
    END IF;

    UPDATE public.document d
    SET current_revision_id = p_next_revision_id,
        template_version_id = (
            SELECT r.template_version_id
            FROM public.document_revision r
            WHERE r.workspace_id = p_workspace_id
              AND r.document_id = p_document_id
              AND r.id = p_next_revision_id
        )
    WHERE d.workspace_id = p_workspace_id
      AND d.id = p_document_id
      AND d.trashed_at IS NULL
      AND d.current_revision_id IS NOT DISTINCT FROM p_expected_revision_id
      AND EXISTS (
          SELECT 1
          FROM public.document_revision r
          JOIN public.template_version tv
              ON tv.workspace_id = r.workspace_id
              AND tv.id = r.template_version_id
          WHERE r.workspace_id = p_workspace_id
            AND r.document_id = p_document_id
            AND r.id = p_next_revision_id
            AND r.parent_revision_id IS NOT DISTINCT FROM p_expected_revision_id
            AND tv.template_id = d.template_id
            AND tv.status = 'ACTIVATED'
      );
    RETURN FOUND;
END;
$$;

-- Two new audited actions: a version made from another one, and a document
-- moved to another version. Their details carry ids and counts only, never
-- a label a person typed.
ALTER TABLE audit_event DROP CONSTRAINT audit_event_action_known;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_action_known CHECK (action IN (
    'DOCUMENT_TRASHED', 'DOCUMENT_RESTORED', 'DOCUMENT_DELETED', 'WORKSPACE_DELETED',
    'DOCUMENT_EXPORTED', 'JOB_RETRIED', 'SUPPORT_GRANT_CREATED', 'SUPPORT_GRANT_REVOKED',
    'CONNECTOR_CONNECTED', 'CONNECTOR_DISCONNECTED', 'SOURCE_IMPORTED',
    'EXTERNAL_ACTION_APPROVED', 'EXTERNAL_ACTION_SENT', 'EXTERNAL_ACTION_FINISHED',
    'TEMPLATE_VERSION_DERIVED', 'DOCUMENT_TEMPLATE_VERSION_CHANGED'
));
