package uz.horecaos.platform.customers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.customers.application.VerificationChallengeStore;
import uz.horecaos.platform.customers.domain.ChallengeStatus;

/**
 * The {@link VerificationChallengeStore} contract, held in a map.
 *
 * <p>Every method mirrors the guard clause of the statement in
 * {@code JdbcVerificationChallengeStore} rather than reimplementing the rule its
 * own way. That is the point of the port existing: the attempt limit, the
 * single-use code and the single-use grant are conditions, and a second
 * implementation of the same conditions is what lets a test assert them without a
 * database — including the interleavings, which are the part a test against a live
 * PostgreSQL is least likely to produce on demand.
 *
 * <p>Synchronised throughout, because a conditional {@code UPDATE} is atomic and an
 * implementation of it that was not would be asserting something weaker than the
 * thing under test.
 */
final class InMemoryVerificationChallengeStore implements VerificationChallengeStore {

    private final Map<UUID, Row> rows = new LinkedHashMap<>();

    record Row(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String purpose,
            String contactType,
            String destinationHash,
            String destinationValue,
            String codeHash,
            int attemptsUsed,
            int maxAttempts,
            ChallengeStatus status,
            Instant issuedAt,
            Instant expiresAt,
            @Nullable Instant settledAt,
            @Nullable String grantHash,
            @Nullable Instant grantExpiresAt,
            @Nullable Instant grantRedeemedAt) {

        Row with(ChallengeStatus newStatus, Instant settled) {
            return new Row(
                    id,
                    tenantId,
                    brandId,
                    purpose,
                    contactType,
                    destinationHash,
                    destinationValue,
                    codeHash,
                    attemptsUsed,
                    maxAttempts,
                    newStatus,
                    issuedAt,
                    expiresAt,
                    settled,
                    grantHash,
                    grantExpiresAt,
                    grantRedeemedAt);
        }
    }

    synchronized Optional<Row> row(UUID challengeId) {
        return Optional.ofNullable(rows.get(challengeId));
    }

    synchronized List<Row> all() {
        return List.copyOf(rows.values());
    }

    @Override
    public synchronized void insert(NewChallenge challenge) {
        rows.put(
                challenge.id(),
                new Row(
                        challenge.id(),
                        challenge.tenantId(),
                        challenge.brandId(),
                        challenge.purpose(),
                        challenge.contactType(),
                        challenge.destinationHash(),
                        challenge.destinationValue(),
                        challenge.codeHash(),
                        0,
                        challenge.maxAttempts(),
                        ChallengeStatus.PENDING,
                        challenge.issuedAt(),
                        challenge.expiresAt(),
                        null,
                        null,
                        null,
                        null));
    }

    @Override
    public synchronized IssuanceWindow issuanceWindow(UUID tenantId, String destinationHash, Instant since) {
        List<Row> matching = rows.values().stream()
                .filter(row -> row.tenantId().equals(tenantId))
                .filter(row -> row.destinationHash().equals(destinationHash))
                .toList();

        return new IssuanceWindow(matching.stream().map(Row::issuedAt).max(Comparator.naturalOrder()), (int)
                matching.stream().filter(row -> !row.issuedAt().isBefore(since)).count());
    }

    @Override
    public synchronized int supersedePending(UUID tenantId, String contactType, String destinationHash, Instant now) {
        List<Row> pending = new ArrayList<>(rows.values().stream()
                .filter(row -> row.tenantId().equals(tenantId))
                .filter(row -> row.contactType().equals(contactType))
                .filter(row -> row.destinationHash().equals(destinationHash))
                .filter(row -> row.status() == ChallengeStatus.PENDING)
                .toList());

        pending.forEach(row -> rows.put(row.id(), row.with(ChallengeStatus.SUPERSEDED, now)));
        return pending.size();
    }

    @Override
    public synchronized Optional<Attempt> consumeAttempt(UUID tenantId, UUID challengeId, Instant now) {
        Row row = rows.get(challengeId);
        if (row == null
                || !row.tenantId().equals(tenantId)
                || row.status() != ChallengeStatus.PENDING
                || !row.expiresAt().isAfter(now)
                || row.attemptsUsed() >= row.maxAttempts()) {
            return Optional.empty();
        }

        Row spent = new Row(
                row.id(),
                row.tenantId(),
                row.brandId(),
                row.purpose(),
                row.contactType(),
                row.destinationHash(),
                row.destinationValue(),
                row.codeHash(),
                row.attemptsUsed() + 1,
                row.maxAttempts(),
                row.status(),
                row.issuedAt(),
                row.expiresAt(),
                row.settledAt(),
                row.grantHash(),
                row.grantExpiresAt(),
                row.grantRedeemedAt());
        rows.put(spent.id(), spent);

        return Optional.of(new Attempt(spent.codeHash(), spent.maxAttempts() - spent.attemptsUsed()));
    }

    @Override
    public synchronized boolean markVerified(
            UUID tenantId, UUID challengeId, String grantHash, Instant grantExpiresAt, Instant now) {
        Row row = rows.get(challengeId);
        if (row == null
                || !row.tenantId().equals(tenantId)
                || row.status() != ChallengeStatus.PENDING
                || !row.expiresAt().isAfter(now)) {
            return false;
        }
        rows.put(
                challengeId,
                new Row(
                        row.id(),
                        row.tenantId(),
                        row.brandId(),
                        row.purpose(),
                        row.contactType(),
                        row.destinationHash(),
                        row.destinationValue(),
                        row.codeHash(),
                        row.attemptsUsed(),
                        row.maxAttempts(),
                        ChallengeStatus.VERIFIED,
                        row.issuedAt(),
                        row.expiresAt(),
                        now,
                        grantHash,
                        grantExpiresAt,
                        null));
        return true;
    }

    @Override
    public synchronized void markExhausted(UUID tenantId, UUID challengeId, Instant now) {
        Row row = rows.get(challengeId);
        if (row != null && row.tenantId().equals(tenantId) && row.status() == ChallengeStatus.PENDING) {
            rows.put(challengeId, row.with(ChallengeStatus.EXHAUSTED, now));
        }
    }

    @Override
    public synchronized boolean deleteUnsent(UUID tenantId, UUID challengeId) {
        Row row = rows.get(challengeId);
        if (row == null
                || !row.tenantId().equals(tenantId)
                || row.status() != ChallengeStatus.PENDING
                || row.attemptsUsed() != 0) {
            return false;
        }
        rows.remove(challengeId);
        return true;
    }

    @Override
    public synchronized Optional<RedeemedGrant> redeemGrant(String grantHash, Instant now) {
        return rows.values().stream()
                .filter(row -> grantHash.equals(row.grantHash()))
                .filter(row -> row.status() == ChallengeStatus.VERIFIED)
                .filter(row -> row.grantRedeemedAt() == null)
                .filter(row ->
                        row.grantExpiresAt() != null && row.grantExpiresAt().isAfter(now))
                .findFirst()
                .map(row -> {
                    rows.put(
                            row.id(),
                            new Row(
                                    row.id(),
                                    row.tenantId(),
                                    row.brandId(),
                                    row.purpose(),
                                    row.contactType(),
                                    row.destinationHash(),
                                    row.destinationValue(),
                                    row.codeHash(),
                                    row.attemptsUsed(),
                                    row.maxAttempts(),
                                    row.status(),
                                    row.issuedAt(),
                                    row.expiresAt(),
                                    row.settledAt(),
                                    row.grantHash(),
                                    row.grantExpiresAt(),
                                    now));
                    return new RedeemedGrant(
                            row.id(),
                            row.tenantId(),
                            row.brandId(),
                            row.contactType(),
                            row.destinationHash(),
                            row.destinationValue());
                });
    }

    @Override
    public synchronized int expirePending(Instant now, int limit) {
        List<Row> lapsed = rows.values().stream()
                .filter(row -> row.status() == ChallengeStatus.PENDING)
                .filter(row -> !row.expiresAt().isAfter(now))
                .limit(limit)
                .toList();

        lapsed.forEach(row -> rows.put(row.id(), row.with(ChallengeStatus.EXPIRED, now)));
        return lapsed.size();
    }

    @Override
    public synchronized int purgeSettledBefore(Instant cutoff, int limit) {
        List<Row> stale = rows.values().stream()
                .filter(row -> row.status() != ChallengeStatus.PENDING)
                .filter(row -> row.settledAt() != null && row.settledAt().isBefore(cutoff))
                .limit(limit)
                .toList();

        stale.forEach(row -> rows.remove(row.id()));
        return stale.size();
    }
}
