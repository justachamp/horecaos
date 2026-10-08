-- ADR 0112: a published scenario version is immutable. ScenarioService refuses to
-- replace the steps of a campaign that has left DRAFT, and an edit is a new version
-- (a new DRAFT that supersedes it, with its own approval). This is the database's half of
-- that rule, for the same reason V0043 states the engagement bounds twice: the number
-- an approver signed off is the steps they read, and a second writer that reaches this
-- table another way (a repair script, a future endpoint, a bug) must not be able to
-- change what a second signature meant.
--
-- A trigger rather than a CHECK because the fact that decides it, the campaign's status,
-- lives in another table. It fires per row on INSERT, UPDATE and DELETE; TRUNCATE (a test
-- fixture's reset) does not fire row triggers and is not affected.

CREATE FUNCTION marketing.scenario_steps_belong_to_a_draft() RETURNS trigger AS $$
DECLARE
    owner_id uuid;
    owner_tenant uuid;
    owner_status varchar(24);
BEGIN
    IF TG_OP = 'DELETE' THEN
        owner_id := OLD.campaign_id;
        owner_tenant := OLD.tenant_id;
    ELSE
        owner_id := NEW.campaign_id;
        owner_tenant := NEW.tenant_id;
    END IF;

    SELECT c.status INTO owner_status
      FROM marketing.campaigns c
     WHERE c.id = owner_id AND c.tenant_id = owner_tenant;

    IF owner_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'the steps of scenario % are fixed once it leaves DRAFT (it is %): revise it instead',
            owner_id, COALESCE(owner_status, 'missing')
            USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_scenario_steps_only_while_draft';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scenario_steps_belong_to_a_draft
    BEFORE INSERT OR UPDATE OR DELETE ON marketing.scenario_steps
    FOR EACH ROW EXECUTE FUNCTION marketing.scenario_steps_belong_to_a_draft();

COMMENT ON FUNCTION marketing.scenario_steps_belong_to_a_draft() IS
    'ADR 0112. A scenario step may be written, changed or removed only while its campaign is a DRAFT. A published version is immutable; an edit is a new draft that supersedes it.';
