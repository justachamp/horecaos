package uz.horecaos.platform.iam.application.staff;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.staff.StaffMemberCards;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.MemberRow;

/**
 * {@link StaffMemberCards} over the tenant's own record (ADR 0139). Every read
 * names the tenant, so a member id of another tenant is simply absent.
 */
@Service
class StaffMemberCardService implements StaffMemberCards {

    private final JdbcStaffMemberStore store;
    private final StaffMemberCodec codec;

    StaffMemberCardService(JdbcStaffMemberStore store, StaffMemberCodec codec) {
        this.store = store;
        this.codec = codec;
    }

    @Override
    public Map<UUID, Card> cardsOf(UUID tenantId, Collection<UUID> memberIds) {
        Map<UUID, Card> cards = new LinkedHashMap<>();
        for (MemberRow row : store.findByIds(tenantId, memberIds)) {
            cards.put(row.id(), card(row));
        }
        return cards;
    }

    @Override
    public List<Card> pickable(UUID tenantId) {
        return store.listByTenant(tenantId, null).stream()
                .filter(row -> !StaffMembers.ENDED.equals(row.employmentStatus()))
                .map(this::card)
                .toList();
    }

    private Card card(MemberRow row) {
        StaffMemberCodec.Plain plain = codec.openAll(row);
        return new Card(
                row.id(),
                row.principalSubject(),
                row.displayReference(),
                StaffMemberCodec.joinName(plain.firstName(), plain.lastName()),
                plain.phone(),
                row.employmentStatus());
    }
}
