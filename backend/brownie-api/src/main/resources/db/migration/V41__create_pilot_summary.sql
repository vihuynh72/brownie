-- What a small invited group actually did, in one row.
--
-- The operations summary next to it answers "is anything stuck or costing
-- money". This answers a different question: is this useful to the people
-- using it. It is deliberately made of what is already recorded -- who acted
-- and when, how many documents were started, how many drafts were asked for,
-- how often a person changed what the model proposed, what failed -- rather
-- than of new tracking put in to produce a number. Nothing here is about one
-- named person: every figure is a count.
--
-- "Came back" is the figure that matters most and the one most easily
-- misread: it counts people who did something on two or more separate days,
-- because somebody who tries a tool once and never returns has told you
-- something, and a pilot that cannot show return visits has not shown value.
--
-- Like the operations summary, this is a query an operator runs; no route
-- serves it and neither runtime role may execute it.

CREATE FUNCTION pilot_summary(window_days INTEGER DEFAULT 30)
RETURNS TABLE (
    window_days_reported INTEGER,
    people_with_an_account BIGINT,
    people_who_did_something BIGINT,
    people_who_came_back BIGINT,
    days_with_any_activity BIGINT,
    documents_started BIGINT,
    documents_in_the_trash BIGINT,
    documents_exported BIGINT,
    drafts_requested BIGINT,
    drafts_that_never_finished BIGINT,
    questions_asked_of_people BIGINT,
    proposals_offered BIGINT,
    proposals_accepted BIGINT,
    fields_the_model_wrote BIGINT,
    fields_a_person_rewrote_after_it BIGINT,
    model_requests BIGINT,
    model_cost_usd NUMERIC
)
LANGUAGE sql
STABLE
SET search_path = pg_catalog, public
AS $$
    WITH since AS (
        SELECT clock_timestamp() - make_interval(days => GREATEST(window_days, 1)) AS at
    ),
    activity AS (
        SELECT a.actor_user_id,
               date_trunc('day', a.occurred_at AT TIME ZONE 'UTC') AS day
          FROM public.audit_event a, since s
         WHERE a.occurred_at >= s.at
           AND a.actor_user_id IS NOT NULL
         GROUP BY 1, 2
    ),
    -- A field the model wrote, and whether a person later wrote that same
    -- field on a later revision of the same document. Comparing the field's
    -- own history is what separates "corrected the draft" from "typed
    -- something somewhere", which is the difference the number is for.
    model_written AS (
        SELECT f.document_id, f.field_id, min(f.revision_id) AS first_revision
          FROM public.document_revision_field_state f, since s
         WHERE f.authorship = 'AI_COMPOSED'
           AND f.created_at >= s.at
         GROUP BY 1, 2
    ),
    rewritten AS (
        SELECT DISTINCT m.document_id, m.field_id
          FROM model_written m
          JOIN public.document_revision_field_state later
            ON later.document_id = m.document_id
           AND later.field_id = m.field_id
           AND later.revision_id > m.first_revision
           AND later.authorship IN ('USER_AUTHORED', 'MIXED')
    )
    SELECT
        GREATEST(window_days, 1),
        (SELECT count(*) FROM public.user_identity WHERE disabled_at IS NULL),
        (SELECT count(DISTINCT actor_user_id) FROM activity),
        (SELECT count(*) FROM (SELECT actor_user_id FROM activity GROUP BY 1 HAVING count(*) >= 2) AS people_on_two_days),
        (SELECT count(DISTINCT day) FROM activity),
        (SELECT count(*) FROM public.document d, since s WHERE d.created_at >= s.at),
        (SELECT count(*) FROM public.document d, since s WHERE d.created_at >= s.at AND d.trashed_at IS NOT NULL),
        (SELECT count(DISTINCT r.document_id) FROM public.export_receipt r, since s WHERE r.exported_at >= s.at),
        (SELECT count(*) FROM public.generation_run g, since s WHERE g.created_at >= s.at),
        (SELECT count(*) FROM public.generation_run g
           JOIN public.job j ON j.id = g.job_id, since s
          WHERE g.created_at >= s.at AND j.state IN ('DEAD', 'CANCELLED')),
        (SELECT count(*) FROM public.question q, since s WHERE q.created_at >= s.at),
        (SELECT count(*) FROM public.document_patch_proposal p, since s WHERE p.created_at >= s.at),
        (SELECT count(*) FROM public.document_patch_proposal p, since s WHERE p.created_at >= s.at AND p.status = 'ACCEPTED'),
        (SELECT count(*) FROM model_written),
        (SELECT count(*) FROM rewritten),
        (SELECT count(*) FROM public.model_usage u, since s WHERE u.created_at >= s.at),
        (SELECT COALESCE(sum(public.model_usage_counted_cost(u.state, u.reserved_cost_usd, u.actual_cost_usd)), 0)
           FROM public.model_usage u, since s WHERE u.created_at >= s.at)
$$;

-- An operator's query, like operations_summary(): neither runtime role may
-- run it, and nothing in the application calls it.
REVOKE ALL ON FUNCTION pilot_summary(INTEGER) FROM PUBLIC, brownie_api, brownie_worker;
