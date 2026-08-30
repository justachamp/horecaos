/**
 * What customers needs from somebody else: the seam a verification code leaves
 * the platform through (ADR 0015, ADR 0020).
 *
 * <p>Separate from {@code customers.api}, which is what other modules may hold
 * for a customer and by its own contract never carries personal data. A
 * verification message carries the destination and the code, so it cannot live
 * there.
 *
 * <p>The direction is the reason this package exists at all. Notifications
 * already depends on {@code customers.api} to resolve a recipient, so customers
 * cannot depend on notifications without making the two cyclic — which
 * {@code ModularArchitectureTests} would reject. Customers therefore declares the
 * port and the adapter module implements it, exactly as notifications declares
 * {@code NotificationTransport} and integration implements it.
 */
@org.springframework.modulith.NamedInterface("spi")
package uz.qoida.platform.customers.spi;
