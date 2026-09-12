package uz.horecaos.platform.commercial.domain;

/**
 * A tenant's two balances (ADR 0095), each the SUM of its own wallet
 * entries. Zero and the tenant's billing currency when the wallet has no
 * entries yet.
 */
public record WalletBalances(long paidMinor, long bonusMinor, String currency) {}
