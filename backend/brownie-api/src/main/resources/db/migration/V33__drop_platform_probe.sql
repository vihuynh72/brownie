-- platform_probe was the first migration's self-check table: it existed
-- only so the very first HTTP route could prove that migrations, the
-- restricted runtime role, and the request pipeline worked end to end.
-- Every real domain table now proves the same thing, and its unauthenticated
-- route has been removed, so the table goes too. Migrations are forward-only,
-- which is why this is a new file rather than an edit to V1.

DROP TABLE IF EXISTS platform_probe;
