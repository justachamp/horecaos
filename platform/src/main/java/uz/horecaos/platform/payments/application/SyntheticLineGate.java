package uz.horecaos.platform.payments.application;

import uz.horecaos.platform.payments.domain.ProviderBinding;

/**
 * Whether the bridge may file its one-line placeholder receipt through this binding
 * (ADR 0166, Decision 6).
 *
 * <p>The real line builder does not exist yet, and the only thing that can be filed without one
 * is one synthetic line for the whole order total, a placeholder classification, VAT 0 and, when
 * the seller cannot be resolved, a zero TIN. Filed with the tax authority that can only be
 * corrected, never withdrawn, so the answer is no everywhere except a sandbox, where the zeroes
 * cannot be mistaken for a sale and the wire can be proved.
 */
public interface SyntheticLineGate {

    boolean permitted(ProviderBinding binding);

    /** Never permitted: what a deployment with nothing configured does, and what production does. */
    SyntheticLineGate NEVER = binding -> false;
}
