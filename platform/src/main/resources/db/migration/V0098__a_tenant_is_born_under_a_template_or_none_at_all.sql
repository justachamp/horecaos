-- ADR 0008: seeds the v1 platform default onboarding template.
--
-- Gap B of the 2026-08-30 proving run. tenant.onboarding_runs.template_id
-- carries a NOT NULL foreign key onto tenant.onboarding_templates (V0014),
-- and nothing before this migration ever inserted a row there: no migration,
-- no local-fixtures seed, no controller. Every test that started a run wrote
-- one by hand, and tools/proving-run did the identical thing against a live
-- stack, both citing this exact absence. A fresh deployment could reach
-- POST .../onboarding-runs and get a foreign-key violation on the very first
-- call.
--
-- required_steps is descriptive reference data, not the runtime authority --
-- OnboardingService.startRun materialises a run's steps from the code-owned
-- OnboardingStep enum (sequence, phase, requiredInV1), never from this
-- column. It is populated here anyway, in the enum's own order, so an
-- operator reading this row sees the same shape the workflow actually runs
-- rather than an empty array that looks like nobody finished the seed.
--
-- default_configuration is read at runtime: OnboardingController.start()
-- reads it from the referenced template row and threads it through to every
-- step's input_snapshot as "defaultConfiguration", and
-- DefaultConfigurationApply (the DEFAULT_CONFIGURATION_APPLY step handler)
-- applies its "acceptancePolicy" entry as the tenant's TENANT-scope
-- ordering.acceptance policy (ADR 0030), through the same PolicyAuthor a
-- human uses to author one by hand. RESTAURANT_APPROVAL is the owner-decided
-- v1 default (minimum-viable-cutover.md): a fresh tenant's first order waits
-- for staff instead of silently auto-confirming under the platform default.
-- The document shape matches uz.horecaos.platform.ordering.domain
-- .OrderAcceptancePolicy field for field.
INSERT INTO tenant.onboarding_templates
    (id, code, version, status, description, required_steps, default_configuration, created_by)
VALUES (
    '94cc9f54-7451-4db1-ac13-4073f6833b15',
    'default',
    1,
    'ACTIVE',
    'ADR 0055 v1 platform default: every buildable ADR 0008 step required, '
        || 'RESTAURANT_APPROVAL as the tenant''s starting order-acceptance policy.',
    -- The whole concatenation must be parenthesised before the ::jsonb cast:
    -- PostgreSQL binds :: tighter than ||, so casting only the trailing
    -- fragment produced "invalid input syntax for type json" on the very
    -- first migration run.
    ('["KEYCLOAK_ORGANIZATION_RECONCILE", "TENANT_OWNER_LINK_OR_INVITE", "DEFAULT_CONFIGURATION_APPLY", '
        || '"BRANDS_AND_LOCATIONS_VALIDATE", "PAYMENT_CONFIGURATION_VALIDATE", "DELIVERY_CONFIGURATION_VALIDATE", '
        || '"POS_BINDINGS_VALIDATE", "CATALOG_READINESS_VALIDATE", "MEDIA_READINESS_VALIDATE", '
        || '"FRONTEND_DOMAIN_VALIDATE", "ACTIVATION_SMOKE_TEST", "TENANT_ACTIVATE"]')::jsonb,
    jsonb_build_object(
        'acceptancePolicy', jsonb_build_object(
            'mode', 'RESTAURANT_APPROVAL',
            'approvalChannel', 'HORECAOS_OPERATIONS',
            'approvalTimeoutSeconds', 600,
            'timeoutAction', 'AUTO_REJECT',
            'rejectionReasonRequired', false,
            'notifyCustomerWhilePending', true
        )
    ),
    'V0098 migration (platform reference data)'
)
ON CONFLICT (code, version) DO NOTHING;
