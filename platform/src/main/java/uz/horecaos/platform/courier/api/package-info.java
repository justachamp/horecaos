/**
 * What courier exposes to other modules: its ADR 0030 configuration keys, the
 * self-authorization declaration a courier-facing endpoint uses (ADR 0049),
 * and the {@link uz.horecaos.platform.courier.api.BusinessDayWindows} port
 * {@code reporting} implements for it (ADR 0043) — the same
 * direction-of-dependency {@code ordering.api} and {@code customers.api}
 * already use.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.courier.api;
