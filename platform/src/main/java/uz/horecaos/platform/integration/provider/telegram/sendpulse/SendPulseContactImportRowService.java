package uz.horecaos.platform.integration.provider.telegram.sendpulse;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerImportDirectory;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore;
import uz.horecaos.platform.integration.provider.telegram.TelegramCustomerLinkService;

/**
 * One row of a SendPulse import, planned and — on a real run — applied, in
 * its own transaction (ADR 0059 stage 3).
 *
 * <p>Its own {@code @Service} bean rather than a method on {@link
 * SendPulseContactImportService}, so that {@code @Transactional} actually
 * takes effect per row through Spring's proxy: the orchestrator loops over
 * every row of a run and must not roll one row's writes back because a later
 * row failed, which self-invoking a {@code @Transactional} method on the same
 * bean cannot give — see this platform's own {@code AGENTS.md} note on why a
 * green test proves the assertion, not the code, and the same discipline
 * applies to a transaction boundary nobody Ran.
 *
 * <h2>The three seams this reuses rather than duplicates</h2>
 *
 * <ul>
 *   <li>{@link TelegramBindingStore#customerAccountFor} — the exact read
 *       {@code TelegramBindingStore} already offers for "does this chat
 *       already belong to a customer", used here as the row-level idempotency
 *       check: importing the same file twice finds every previously-imported
 *       chat already linked and writes nothing more for it.
 *   <li>{@link TelegramCustomerLinkService#importLink} — the binding +
 *       endpoint pair, exactly the shape the wave-7 {@code /start} handshake
 *       creates.
 *   <li>{@link CustomerImportDirectory} — the one door this build opens for a
 *       bulk caller to create a customer account and record an imported
 *       consent decision (see that interface's own javadoc for why it is not
 *       {@code CustomerDirectory}).
 * </ul>
 */
@Service
public class SendPulseContactImportRowService {

    /**
     * The consent purpose an imported TELEGRAM decision is recorded under —
     * the same purpose the platform's own broadcast path (ADR 0059 stage 4,
     * ADR 0044) will ask for before sending a campaign over this channel.
     * Not configurable per import: SendPulse's own export carries one
     * subscribe/unsubscribe flag, not a purpose-by-purpose consent ledger, so
     * recording anything narrower would be inventing precision the source
     * data does not have.
     */
    static final String CONSENT_PURPOSE = "MARKETING";

    static final String CONSENT_CHANNEL = "TELEGRAM";

    /**
     * The consent record's {@code policy_version}. A literal rather than a
     * lookup: SendPulse's own export carries no policy version at all, and
     * this string exists so every consent decision this import ever writes
     * says plainly, forever, that it came from here rather than from a
     * tenant's own policy text.
     */
    static final String IMPORT_POLICY_VERSION = "sendpulse-import-v1";

    private final TelegramBindingStore bindings;
    private final TelegramCustomerLinkService customerLinks;
    private final CustomerImportDirectory customers;
    private final Clock clock;

    public SendPulseContactImportRowService(
            TelegramBindingStore bindings,
            TelegramCustomerLinkService customerLinks,
            CustomerImportDirectory customers,
            Clock clock) {
        this.bindings = bindings;
        this.customerLinks = customerLinks;
        this.customers = customers;
        this.clock = clock;
    }

    @Transactional
    public SendPulseImportRowOutcome process(
            UUID tenantId,
            UUID installationId,
            UUID brandId,
            UUID importRunId,
            SendPulseContactRow row,
            boolean dryRun,
            String importedBySubject) {

        if (row.isRejected()) {
            return SendPulseImportRowOutcome.rejected(
                    Objects.requireNonNull(row.rejectReason(), "a rejected row always carries a reason"));
        }

        long chatId = Objects.requireNonNull(row.chatId(), "a non-rejected row always carries a chat id");
        boolean subscribed = Objects.requireNonNull(row.subscribed(), "a non-rejected row always carries a status");

        // Row-level idempotency: a chat this exact bot already knows about —
        // from an earlier import run, or from a customer's own live /start —
        // is left exactly as it is. Re-importing the same file therefore
        // creates nothing twice.
        Optional<UUID> alreadyLinked = bindings.customerAccountFor(tenantId, chatId);
        if (alreadyLinked.isPresent()) {
            return SendPulseImportRowOutcome.skippedAlreadyLinked(alreadyLinked.get(), subscribed);
        }

        List<UUID> phoneMatches;
        try {
            phoneMatches = row.rawPhone() == null ? List.of() : customers.accountsWithPhone(tenantId, row.rawPhone());
        } catch (IllegalArgumentException malformedPhone) {
            // customers.api's own phone normalization threw — the row's
            // phone column carried something PhoneNumber.normalize refuses
            // (blank once stripped of punctuation), which the parser could
            // not check itself; see SendPulseImportRejectReason.MALFORMED_PHONE.
            return SendPulseImportRowOutcome.rejected(SendPulseImportRejectReason.MALFORMED_PHONE);
        }
        if (phoneMatches.size() > 1) {
            return SendPulseImportRowOutcome.rejected(SendPulseImportRejectReason.AMBIGUOUS_PHONE_MATCH);
        }
        Optional<UUID> matchedAccount = phoneMatches.isEmpty() ? Optional.empty() : Optional.of(phoneMatches.get(0));

        // Two different Telegram accounts sharing a household phone is a
        // real case, not a corner case — ADR 0015 is explicit about it. The
        // match above found the right customer; it does not follow that this
        // row's own chat can become that customer's Telegram link too, since
        // one customer account holds at most one active binding.
        if (matchedAccount.isPresent()
                && customerLinks.activeBinding(tenantId, matchedAccount.get()).isPresent()) {
            return SendPulseImportRowOutcome.rejected(
                    SendPulseImportRejectReason.ACCOUNT_ALREADY_LINKED_TO_ANOTHER_CHAT);
        }

        if (dryRun) {
            return matchedAccount
                    .map(accountId -> SendPulseImportRowOutcome.matched(accountId, subscribed))
                    .orElseGet(() -> SendPulseImportRowOutcome.created(null, subscribed));
        }

        UUID customerAccountId;
        boolean created;
        if (matchedAccount.isPresent()) {
            customerAccountId = matchedAccount.get();
            created = false;
        } else {
            CustomerAccountRef account = customers.createAccountWithoutPrincipal(tenantId, brandId);
            customerAccountId = account.accountId();
            if (row.rawPhone() != null) {
                customers.attachPhoneContact(tenantId, customerAccountId, row.rawPhone());
            }
            created = true;
        }

        Instant now = clock.instant();
        customerLinks.importLink(
                tenantId,
                installationId,
                brandId,
                customerAccountId,
                chatId,
                row.telegramUserId(),
                subscribed,
                importedBySubject,
                now);

        Instant decidedAt = row.subscriptionDecidedAt() != null ? row.subscriptionDecidedAt() : now;
        customers.record(
                tenantId,
                customerAccountId,
                // Brand-scoped, not tenant-wide: unlike effectivePreference
                // (which falls back from brand-specific to a NULL row),
                // ConsentDirectory#consentFor matches brand_id exactly
                // against the notification's own brandId, and this import is
                // always one specific brand's bot. A tenant-wide row here
                // would silently never match a real eligibility check.
                brandId,
                CONSENT_PURPOSE,
                CONSENT_CHANNEL,
                subscribed,
                IMPORT_POLICY_VERSION,
                "sendpulse-import:%s:row-%d".formatted(importRunId, row.rowNumber()),
                decidedAt);

        return created
                ? SendPulseImportRowOutcome.created(customerAccountId, subscribed)
                : SendPulseImportRowOutcome.matched(customerAccountId, subscribed);
    }
}
