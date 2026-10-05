import { ValidationResult } from './readiness-api';

/**
 * The three tiers settings.md §10.0 sorts the readiness panel by: blocking (0) → expiring (1) →
 * advisory (2). The server names the tier (`severity`); a server older than the third tier sends
 * only `advisory`, which then reads as the two tiers it knew.
 */
export type ReadinessTier = 'BLOCKING' | 'EXPIRING' | 'ADVISORY';

const TIER_WEIGHT: Readonly<Record<ReadinessTier, number>> = {
  BLOCKING: 0,
  EXPIRING: 1,
  ADVISORY: 2,
};

export function tierOf(finding: ValidationResult): ReadinessTier {
  if (finding.severity === 'EXPIRING' || finding.severity === 'ADVISORY') {
    return finding.severity;
  }
  if (finding.severity === 'BLOCKING') {
    return 'BLOCKING';
  }
  return finding.advisory === true ? 'ADVISORY' : 'BLOCKING';
}

/** What a finding is a case of: one condition, however many items offend it. */
function conditionOf(finding: ValidationResult): string {
  return finding.errorCode ?? finding.stepKey;
}

/**
 * Severity weight ascending, then **how many items offend the same condition**, descending (settings.md §10.0:
 * «then by count descending»), so the condition affecting the most branches or channels leads its tier. Items of
 * one condition stay together and keep the order the server named them in; two conditions with the same count
 * keep the order in which the server first named them. Never mutates its input.
 */
export function orderFindings(findings: readonly ValidationResult[]): readonly ValidationResult[] {
  interface Group {
    readonly tier: ReadinessTier;
    readonly firstIndex: number;
    readonly items: ValidationResult[];
  }
  const groups = new Map<string, Group>();
  findings.forEach((finding, index) => {
    const tier = tierOf(finding);
    const key = `${tier}|${conditionOf(finding)}`;
    const group = groups.get(key);
    if (group) {
      group.items.push(finding);
    } else {
      groups.set(key, { tier, firstIndex: index, items: [finding] });
    }
  });
  return [...groups.values()]
    .sort(
      (a, b) =>
        TIER_WEIGHT[a.tier] - TIER_WEIGHT[b.tier] ||
        b.items.length - a.items.length ||
        a.firstIndex - b.firstIndex,
    )
    .flatMap((group) => group.items);
}

export interface TierCounts {
  readonly blocking: number;
  readonly expiring: number;
  readonly advisory: number;
}

export function countByTier(findings: readonly ValidationResult[]): TierCounts {
  let blocking = 0;
  let expiring = 0;
  let advisory = 0;
  for (const finding of findings) {
    switch (tierOf(finding)) {
      case 'BLOCKING':
        blocking += 1;
        break;
      case 'EXPIRING':
        expiring += 1;
        break;
      case 'ADVISORY':
        advisory += 1;
        break;
    }
  }
  return { blocking, expiring, advisory };
}
