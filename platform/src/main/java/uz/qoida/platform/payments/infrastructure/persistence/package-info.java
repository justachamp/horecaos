/**
 * JdbcClient stores for payments (ADR 0013).
 *
 * <p>Every tenant-owned query carries the tenant predicate, and every state change
 * is a conditional UPDATE naming the status it expects. Neither is stylistic here:
 * an intent id arrives from a client, and a checkout retry, a provider callback and
 * an expiry sweep routinely arrive at one row together.
 */
package uz.qoida.platform.payments.infrastructure.persistence;
