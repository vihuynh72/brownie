-- Google Drive files as sources: a Google Doc copied as the plain text
-- Google's own export of it produces, or a plain-text file copied as it is.
--
-- Only the two checks that list what a copied source may be are widened.
-- A Drive copy, like a calendar copy, says completely where it came from:
-- the connection and the person's choice it was read through, the file's id
-- and Drive's version of it at the moment it was read. Its conversion is
-- GOOGLE_DOC_AS_TEXT for a Doc, and none for a text file, which arrives as
-- it was. The calendar branch is unchanged, word for word, and an upload
-- still says nothing of the kind.
--
-- Nothing else changes: not the grants, their read-only check, their
-- policies or privileges; not the bounds on the origin columns; not the
-- index that keeps one snapshot per version of one thing per choice; not
-- what deleting a workspace removes.
ALTER TABLE source_snapshot
    DROP CONSTRAINT source_snapshot_kind_known,
    ADD CONSTRAINT source_snapshot_kind_known CHECK (kind IN ('ARTIFACT', 'GOOGLE_CALENDAR', 'GOOGLE_DRIVE'));

ALTER TABLE source_snapshot
    DROP CONSTRAINT source_snapshot_origin_shape,
    ADD CONSTRAINT source_snapshot_origin_shape CHECK (
        CASE WHEN kind = 'GOOGLE_CALENDAR'
            THEN origin_connection_id IS NOT NULL AND origin_grant_id IS NOT NULL AND origin_external_id IS NOT NULL
                 -- IS NOT DISTINCT FROM, not =, so a missing conversion is refused rather than read as unknown and let through.
                 AND origin_revision IS NOT NULL AND origin_conversion IS NOT DISTINCT FROM 'CALENDAR_EVENT_AS_TEXT'
            WHEN kind = 'GOOGLE_DRIVE'
            THEN origin_connection_id IS NOT NULL AND origin_grant_id IS NOT NULL AND origin_external_id IS NOT NULL
                 AND origin_revision IS NOT NULL
                 -- A Doc arrives as its exported text; a text file arrives as it is, with no conversion at all.
                 AND (origin_conversion IS NULL OR origin_conversion = 'GOOGLE_DOC_AS_TEXT')
            ELSE origin_connection_id IS NULL AND origin_grant_id IS NULL AND origin_external_id IS NULL
                 AND origin_revision IS NULL AND origin_modified_at IS NULL AND origin_title IS NULL
                 AND origin_link IS NULL AND origin_conversion IS NULL
        END);
