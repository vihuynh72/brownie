-- A staged object is safe to attach only while its attempt remains current.
-- Reclaiming a lease replaces its fencing token; reaching a terminal state
-- ends every remaining attempt. Keep attached records intact, but mark all
-- unattached metadata from a lost or terminal attempt unusable in the same
-- database transaction as the job transition.
CREATE FUNCTION public.discard_unattached_staged_outputs_for_job_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.state IN ('CANCELLED', 'DEAD') THEN
        UPDATE public.job_staged_output o
        SET state = 'DISCARDED'
        WHERE o.job_id = NEW.id
          AND o.state IN ('STAGED', 'VERIFIED');
    ELSIF OLD.state = 'LEASED'
            AND NEW.state = 'LEASED'
            AND NEW.fencing_token > OLD.fencing_token THEN
        UPDATE public.job_staged_output o
        SET state = 'DISCARDED'
        WHERE o.job_id = NEW.id
          AND o.fencing_token < NEW.fencing_token
          AND o.state IN ('STAGED', 'VERIFIED');
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.discard_unattached_staged_outputs_for_job_transition()
    FROM PUBLIC, brownie_api, brownie_worker;

CREATE TRIGGER job_discard_unattached_staged_outputs_after_transition
AFTER UPDATE OF state, fencing_token ON public.job
FOR EACH ROW
EXECUTE FUNCTION public.discard_unattached_staged_outputs_for_job_transition();
