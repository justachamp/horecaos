package uz.horecaos.platform.customers.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerImportDirectory;

/**
 * One row of a customer CSV import, planned and — on a real run — applied, in
 * its own transaction (row {@code X.13}/{@code 5.1b}).
 *
 * <p>Its own {@code @Service} bean rather than a method on {@link
 * CustomerCsvImportService}, for the identical reason {@code
 * SendPulseContactImportRowService}'s own doc gives: {@code @Transactional}
 * only takes effect through Spring's proxy when the caller invokes a
 * different bean, and the orchestrator loops over every row of a run without
 * letting one row's failure roll back the rows already committed before it.
 *
 * <p>Reuses {@link CustomerImportDirectory} — the same port {@code
 * integration}'s SendPulse adapter depends on — rather than the concrete
 * identity/profile services directly, even though this class lives inside
 * the {@code customers} module itself and could reach them without crossing
 * a module boundary. The port is the reusable half row {@code 5.1b}'s brief
 * names: {@code accountsWithPhone}/{@code createAccountWithoutPrincipal}/
 * {@code attachPhoneContact} already fix the phone-matching and
 * account-creation shape once, and a second caller composing the same three
 * services by hand would be a second chance for that shape to drift.
 */
@Service
public class CustomerCsvImportRowService {

    /**
     * Below this many digits, a "phone" cell is text, not a number — the
     * identical threshold {@code CustomerListQueryService.MIN_PHONE_DIGITS}
     * uses to decide a grid search query is phone-shaped. Checked here,
     * locally, rather than relying on {@code PhoneNumber.normalize} to
     * refuse it: that method strips punctuation and prepends {@code +}
     * unconditionally, so a garbage cell like {@code "N/A"} or {@code "-"}
     * normalizes to {@code "+"} rather than throwing, and a merchant's CSV
     * export — unlike SendPulse's own audience export — is exactly the kind
     * of file that carries stray placeholder text in a phone column.
     */
    private static final int MIN_PHONE_DIGITS = 7;

    private final CustomerImportDirectory customers;

    public CustomerCsvImportRowService(CustomerImportDirectory customers) {
        this.customers = customers;
    }

    @Transactional
    public CustomerCsvImportRowOutcome process(UUID tenantId, UUID brandId, CustomerCsvImportRow row, boolean dryRun) {
        if (row.isRejected()) {
            return CustomerCsvImportRowOutcome.rejected(
                    Objects.requireNonNull(row.rejectReason(), "a rejected row always carries a reason"));
        }

        String rawPhone = Objects.requireNonNull(row.rawPhone(), "a non-rejected row always carries a phone");
        if (rawPhone.replaceAll("[^0-9]", "").length() < MIN_PHONE_DIGITS) {
            return CustomerCsvImportRowOutcome.rejected(CustomerCsvImportRejectReason.MALFORMED_PHONE);
        }

        List<UUID> matches;
        try {
            matches = customers.accountsWithPhone(tenantId, rawPhone);
        } catch (IllegalArgumentException malformedPhone) {
            // Belt and braces: customers.api's own phone normalization threw
            // for some input the digit-count check above did not catch.
            return CustomerCsvImportRowOutcome.rejected(CustomerCsvImportRejectReason.MALFORMED_PHONE);
        }
        if (matches.size() > 1) {
            return CustomerCsvImportRowOutcome.rejected(CustomerCsvImportRejectReason.AMBIGUOUS_PHONE_MATCH);
        }
        if (!matches.isEmpty()) {
            // Idempotent across re-imports of the same file, and across a file
            // whose rows repeat a phone: the first occurrence creates the
            // account (committed before the next row starts, since each row is
            // its own transaction), and every later row with the same phone
            // finds it here instead of creating a second one.
            return CustomerCsvImportRowOutcome.matched(matches.get(0));
        }

        if (dryRun) {
            return CustomerCsvImportRowOutcome.created(null);
        }

        CustomerAccountRef account = customers.createAccountWithoutPrincipal(tenantId, brandId);
        customers.attachPhoneContact(tenantId, account.accountId(), rawPhone);
        return CustomerCsvImportRowOutcome.created(account.accountId());
    }
}
