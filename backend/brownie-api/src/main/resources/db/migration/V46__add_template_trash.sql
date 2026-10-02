-- A template can be moved to the Trash Bin and taken back out.
--
-- The column is the whole of it, the same tombstone V36 gave document: the
-- template list reads only the rows without it, the Trash Bin reads only the
-- rows with it, and starting a new document from a template that carries it
-- is refused. Nothing else changes. Every document already made from the
-- template keeps its version, and that version's layout, rules and file are
-- read exactly as before, which is also why nothing deletes a trashed
-- template for good by itself: those documents depend on it. Only deleting
-- the whole workspace removes it.
--
-- The existing template_member_update policy already scopes who may change
-- the row, so trashing needs no policy of its own.
ALTER TABLE template ADD COLUMN trashed_at TIMESTAMPTZ;

-- The Trash Bin lists a workspace's trashed templates newest first; the
-- index holds only those rows, so it stays as small as the Trash Bin.
CREATE INDEX template_trash_idx ON template (workspace_id, trashed_at DESC, id DESC) WHERE trashed_at IS NOT NULL;
