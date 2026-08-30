package uz.qoida.platform.tenancy;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR 0038's rollout stage 1 schema, applied by the tests that need it.
 *
 * <p><strong>This is a test fixture and not a migration.</strong> Flyway
 * numbering is central and this change may not add a file to
 * {@code db/migration}, so the DDL below is the text handed to the schema owner
 * verbatim, kept here only so that the two claims it makes can actually be
 * proved: that overlapping fiscal assignments are refused by the database rather
 * than by a service, and that the resolver's composite join cannot return another
 * tenant's company. Asserting either without running it would be asserting the
 * thing most likely to be wrong.
 *
 * <p>When the migration lands, {@link #apply} becomes a no-op — it drops and
 * recreates what would then already exist — and this file is deleted with the
 * same commit. It deliberately does not check whether the tables are already
 * there: a fixture that silently ran against the real schema in some environments
 * and its own in others would make a failure impossible to place.
 *
 * <p>{@code btree_gist} is already installed by V0025 and V0034; the statement is
 * kept so this reads as the whole of what the migration needs.
 */
public final class LegalEntitySchema {

    /** The exact DDL, in the order it must run. */
    public static final String[] STATEMENTS = {
            "CREATE EXTENSION IF NOT EXISTS btree_gist",

            """
            CREATE TABLE tenant.legal_entities (
                id uuid PRIMARY KEY,
                tenant_id uuid NOT NULL,
                code varchar(32) NOT NULL,
                legal_name varchar(200) NOT NULL,
                short_name varchar(100),
                tin varchar(9) NOT NULL,
                vat_registered boolean NOT NULL DEFAULT false,
                vat_certificate_reference varchar(64),
                tax_profile_id uuid,
                registered_address varchar(400),
                contact_phone varchar(32),
                status varchar(24) NOT NULL,
                version integer NOT NULL DEFAULT 1,
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now(),

                CONSTRAINT fk_legal_entity_tenant FOREIGN KEY (tenant_id)
                    REFERENCES tenant.tenants (id),
                CONSTRAINT uq_legal_entity_tenant_id UNIQUE (tenant_id, id),
                CONSTRAINT uq_legal_entity_code UNIQUE (tenant_id, code),
                CONSTRAINT uq_legal_entity_tin UNIQUE (tenant_id, tin),
                CONSTRAINT ck_legal_entity_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
                CONSTRAINT ck_legal_entity_tin CHECK (tin ~ '^[0-9]{9}$'),
                CONSTRAINT ck_legal_entity_status CHECK (
                    status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),
                CONSTRAINT ck_legal_entity_vat_certificate CHECK (
                    vat_registered OR vat_certificate_reference IS NULL),
                CONSTRAINT ck_legal_entity_version CHECK (version >= 1)
            )
            """,

            """
            CREATE TABLE tenant.location_fiscal_assignments (
                id uuid PRIMARY KEY,
                tenant_id uuid NOT NULL,
                brand_id uuid NOT NULL,
                location_id uuid NOT NULL,
                legal_entity_id uuid NOT NULL,
                effective_from date NOT NULL,
                effective_until date,
                approved_by varchar(200) NOT NULL,
                approval_reference varchar(200),
                version integer NOT NULL DEFAULT 1,
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now(),

                CONSTRAINT fk_location_fiscal_assignment_location
                    FOREIGN KEY (tenant_id, brand_id, location_id)
                    REFERENCES tenant.locations (tenant_id, brand_id, id),
                CONSTRAINT fk_location_fiscal_assignment_entity
                    FOREIGN KEY (tenant_id, legal_entity_id)
                    REFERENCES tenant.legal_entities (tenant_id, id),
                CONSTRAINT uq_location_fiscal_assignment_tenant_id UNIQUE (tenant_id, id),
                CONSTRAINT ck_location_fiscal_assignment_range CHECK (
                    effective_until IS NULL OR effective_until > effective_from),
                CONSTRAINT ck_location_fiscal_assignment_version CHECK (version >= 1),

                CONSTRAINT ex_location_fiscal_assignment_no_overlap EXCLUDE USING gist (
                    tenant_id WITH =,
                    location_id WITH =,
                    daterange(effective_from, effective_until, '[)') WITH &&)
            )
            """,

            """
            CREATE INDEX ix_location_fiscal_assignment_resolution
                ON tenant.location_fiscal_assignments (tenant_id, location_id, effective_from DESC)
            """,
    };

    private LegalEntitySchema() {
    }

    /** Drops and recreates both tables, so each test starts from a known empty pair. */
    public static void apply(JdbcClient jdbc) {
        jdbc.sql("DROP TABLE IF EXISTS tenant.location_fiscal_assignments CASCADE").update();
        jdbc.sql("DROP TABLE IF EXISTS tenant.legal_entities CASCADE").update();
        for (String statement : STATEMENTS) {
            jdbc.sql(statement).update();
        }
    }
}
