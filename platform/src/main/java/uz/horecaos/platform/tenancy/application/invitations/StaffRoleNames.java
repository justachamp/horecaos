package uz.horecaos.platform.tenancy.application.invitations;

import java.util.Map;

/**
 * The eight tenant-visible {@code PlatformRole} codes, in the words a
 * staff-invitation email or inspect-before-accept screen shows a person who
 * has not signed in yet (ADR 0116).
 *
 * <p>A small, deliberate duplicate of the frontend's own {@code
 * staff-role-labels.ts} rather than a shared source: that catalogue is a
 * client-side translation table keyed by {@code MessageKey}, and nothing on
 * this path renders through Angular's i18n pipe. Keeping the eight names here
 * in the three words the console already uses is cheaper than plumbing one
 * catalogue across a language boundary, and the codebase already accepts this
 * duplication once -- {@code TenantRoleCatalog} names the same eight roles a
 * third way, as capability sets, for the same reason: each surface needs its
 * own shape of the same eight facts.
 */
final class StaffRoleNames {

    private record Names(String uz, String ru, String en) {}

    private static final Map<String, Names> NAMES = Map.ofEntries(
            Map.entry("tenant-owner", new Names("Egasi", "Владелец", "Owner")),
            Map.entry("tenant-admin", new Names("Administrator", "Администратор", "Administrator")),
            Map.entry("tenant-finance", new Names("Moliya", "Финансы", "Finance")),
            Map.entry(
                    "support-agent", new Names("Qo'llab-quvvatlash operatori", "Оператор поддержки", "Support agent")),
            Map.entry("brand-manager", new Names("Brend menejeri", "Менеджер бренда", "Brand manager")),
            Map.entry("courier-dispatcher", new Names("Dispecher", "Диспетчер", "Dispatcher")),
            Map.entry(
                    "location-manager", new Names("Filial boshqaruvchisi", "Управляющий филиалом", "Location manager")),
            Map.entry("location-staff", new Names("Filial xodimi", "Сотрудник филиала", "Location staff")));

    private StaffRoleNames() {}

    /** The role's name in this language, or the raw code for one this table does not name -- never blank. */
    static String of(String roleCode, String locale) {
        Names names = NAMES.get(roleCode);
        if (names == null) {
            return roleCode;
        }
        return switch (locale) {
            case "uz" -> names.uz();
            case "en" -> names.en();
            default -> names.ru();
        };
    }
}
