package uz.horecaos.platform.customers.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerExportReads;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.AccountRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.AddressRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.ContactPointRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant;
import uz.horecaos.platform.customers.spi.ExportReveal;
import uz.horecaos.platform.customers.spi.ExportRow;
import uz.horecaos.platform.customers.spi.ExportSection;
import uz.horecaos.platform.customers.spi.ExportTable;
import uz.horecaos.platform.customers.spi.ExportsCustomerData;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;

/**
 * What the customers module itself holds about the customer (ADR 0029): who she is, how she can be reached, where she
 * lives, what she agreed to, what she saved, what was amended and what she has asked for before.
 *
 * <p>Two things are left out on purpose and named in the manifest. The reasons on a blacklist entry are the tenant's
 * working notes about a customer written to be read by staff, and whether a data subject is entitled to them is a
 * question for legal (the cover note's open input), so the export says that she was listed, since when and until when,
 * and withholds the text. A session token or a sign-in link is a credential and not hers to carry out.
 */
@Component
@ExportsCustomerData(
        name = "customers",
        tables = {
            "customer.customer_accounts",
            "customer.contact_points",
            "customer.addresses",
            "customer.brand_profiles",
            "customer.consent_decisions",
            "customer.favourites",
            "customer.blacklist_entries",
            "customer.leads",
            "customer.profile_corrections",
            "customer.export_requests"
        })
public class CustomerRecordsExportParticipant implements CustomerExportParticipant {

    private static final String ACCOUNT_TABLE = "customer.customer_accounts";
    private static final String CONTACT_TABLE = "customer.contact_points";
    private static final String LEAD_TABLE = "customer.leads";
    private static final String CORRECTION_TABLE = "customer.profile_corrections";

    private final JdbcCustomerStore store;
    private final JdbcCustomerExportReads reads;
    private final JdbcExportRequestStore requests;
    private final CustomerProfileService profiles;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;

    public CustomerRecordsExportParticipant(
            JdbcCustomerStore store,
            JdbcCustomerExportReads reads,
            JdbcExportRequestStore requests,
            CustomerProfileService profiles,
            FieldProtection protection,
            ObjectMapper objectMapper) {
        this.store = store;
        this.reads = reads;
        this.requests = requests;
        this.profiles = profiles;
        this.protection = protection;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        UUID tenantId = subject.tenantId();
        UUID accountId = subject.customerAccountId();
        String purpose = subject.purpose();

        List<ExportTable> tables = new ArrayList<>();
        tables.add(ExportTable.of(ACCOUNT_TABLE, List.of(account(tenantId, accountId, purpose))));
        tables.add(ExportTable.of(CONTACT_TABLE, contactPoints(tenantId, accountId, purpose)));
        tables.add(ExportTable.of("customer.addresses", addresses(tenantId, accountId, purpose)));
        tables.add(table("customer.brand_profiles", reads.brandProfiles(tenantId, accountId)));
        tables.add(ExportTable.ofNewestFirst(
                "customer.consent_decisions",
                store.consentHistory(tenantId, accountId).stream()
                        .map(consent -> ExportRow.row()
                                .put("purpose", consent.purpose())
                                .put("brandId", consent.brandId())
                                .put("channel", consent.channel())
                                .put("decision", consent.decision())
                                .put("policyVersion", consent.policyVersion())
                                .put("source", consent.source())
                                .put("decidedAt", consent.decidedAt())
                                .build())
                        .toList()));
        tables.add(table("customer.favourites", reads.favourites(tenantId, accountId)));
        tables.add(table("customer.blacklist_entries", reads.blacklistFacts(tenantId, accountId)));
        tables.add(ExportTable.ofNewestFirst(LEAD_TABLE, leads(subject)));
        tables.add(ExportTable.ofNewestFirst(CORRECTION_TABLE, corrections(subject)));
        tables.add(table("customer.export_requests", reads.exportRequests(tenantId, accountId)));
        return ExportSection.of(tables).withholding("blacklist_entries.reasons_are_staff_notes");
    }

    private static ExportTable table(String name, List<Map<String, Object>> fetched) {
        return ExportTable.ofNewestFirst(
                name, fetched.stream().map(ExportRow::render).toList());
    }

    private Map<String, @Nullable Object> account(UUID tenantId, UUID accountId, String purpose) {
        AccountRow account = store.account(tenantId, accountId).orElseThrow();
        String dateOfBirth = account.dateOfBirthEncrypted() == null
                ? null
                : protection.reveal(
                        tenantId,
                        ProtectedValue.deserialize(account.dateOfBirthEncrypted()),
                        new RecordRef(ACCOUNT_TABLE, "date_of_birth_encrypted", accountId),
                        purpose);
        return ExportRow.row()
                .put("id", account.id())
                .put("status", account.status())
                .put("displayName", account.displayName())
                .put("dateOfBirth", dateOfBirth)
                .put("preferredLocale", account.preferredLocale())
                .put("preferredTimezone", account.preferredTimezone())
                .put("createdAt", account.createdAt())
                .build();
    }

    private List<Map<String, @Nullable Object>> contactPoints(UUID tenantId, UUID accountId, String purpose) {
        List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        for (ContactPointRow contact : store.contactPoints(tenantId, accountId)) {
            rows.add(ExportRow.row()
                    .put("id", contact.id())
                    .put("type", contact.type())
                    .put(
                            "value",
                            protection.reveal(
                                    tenantId,
                                    ProtectedValue.deserialize(contact.encryptedValue()),
                                    new RecordRef(CONTACT_TABLE, "encrypted_value", contact.id()),
                                    purpose))
                    .put("verificationStatus", contact.verificationStatus())
                    .put("primary", contact.isPrimary())
                    .build());
        }
        return rows;
    }

    private List<Map<String, @Nullable Object>> addresses(UUID tenantId, UUID accountId, String purpose) {
        List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        for (AddressRow address : store.allAddresses(tenantId, accountId)) {
            CustomerProfileService.RevealedAddress revealed = profiles.reveal(tenantId, address, purpose);
            CustomerProfileService.AddressFields fields = revealed.fields();
            rows.add(ExportRow.row()
                    .put("id", revealed.id())
                    .put("label", revealed.label())
                    .put("line1", fields.line1())
                    .put("line2", fields.line2())
                    .put("city", fields.city())
                    .put("district", fields.district())
                    .put("postalCode", fields.postalCode())
                    .put("entrance", fields.entrance())
                    .put("floor", fields.floor())
                    .put("apartment", fields.apartment())
                    .put("landmark", fields.landmark())
                    .put("deliveryInstructions", revealed.deliveryInstructions())
                    .put("latitude", revealed.latitude())
                    .put("longitude", revealed.longitude())
                    .build());
        }
        return rows;
    }

    private List<Map<String, @Nullable Object>> leads(ExportSubject subject) {
        List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw :
                reads.leads(subject.tenantId(), subject.customerAccountId(), subject.phoneLookupHashes())) {
            rows.add(ExportReveal.row(protection, subject, LEAD_TABLE, "id", raw));
        }
        return rows;
    }

    private List<Map<String, @Nullable Object>> corrections(ExportSubject subject) {
        List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : reads.corrections(subject.tenantId(), subject.customerAccountId())) {
            Map<String, @Nullable Object> row = ExportReveal.row(protection, subject, CORRECTION_TABLE, "id", raw);
            // The prior value was stored as a JSON document; the bundle carries it as one.
            Object prior = row.get("priorValue");
            if (prior instanceof String json) {
                row.put("priorValue", objectMapper.readValue(json, Map.class));
            }
            rows.add(row);
        }
        return rows;
    }
}
