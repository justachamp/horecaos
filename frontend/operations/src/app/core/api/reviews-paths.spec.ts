import { describe, expect, it } from 'vitest';

import { benefitGrantPaths, feedbackSettingsPaths, reviewPaths } from './reviews-paths';

const BRAND = { tenantId: 't1', brandId: 'b1' };

/**
 * These assert the literal path each builder produces, against the routes
 * `OperationsRecoveryCaseController`, `OperationsReviewModerationController` and
 * `OperationsFeedbackSettingsController` declare -- not that a builder equals itself, which cannot
 * fail whichever prefix is wrong.
 */
describe('reviewPaths (ADR 0159)', () => {
  it('keeps the review list and summary where ADR 0071 put them', () => {
    expect(reviewPaths.list(BRAND)).toBe('/api/v1/operations/tenants/t1/brands/b1/reviews');
    expect(reviewPaths.summary(BRAND)).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/reviews/summary',
    );
  });

  it('builds the brand worklist and its counts at brand scope', () => {
    expect(reviewPaths.recoveryCases(BRAND)).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/recovery-cases',
    );
    expect(reviewPaths.recoverySummary(BRAND)).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/recovery-cases/summary',
    );
  });

  it('works one case at its own branch: the drawer, the transition, the note and the link', () => {
    const base = '/api/v1/operations/tenants/t1/brands/b1/locations/l1/recovery-cases';
    expect(reviewPaths.locationRecoveryCases(BRAND, 'l1')).toBe(base);
    expect(reviewPaths.recoveryLocationSummary(BRAND, 'l1')).toBe(`${base}/summary`);
    expect(reviewPaths.recoveryCase(BRAND, 'l1', 'c1')).toBe(`${base}/c1`);
    expect(reviewPaths.recoveryTransitions(BRAND, 'l1', 'c1')).toBe(`${base}/c1/transitions`);
    expect(reviewPaths.recoveryNotes(BRAND, 'l1', 'c1')).toBe(`${base}/c1/notes`);
    expect(reviewPaths.recoveryCompensations(BRAND, 'l1', 'c1')).toBe(`${base}/c1/compensations`);
    expect(reviewPaths.recoveryCompensationCandidates(BRAND, 'l1', 'c1')).toBe(
      `${base}/c1/compensation-candidates`,
    );
  });

  it('mints an apology code where BenefitGrantController declares it: the brand’s benefit-grants', () => {
    expect(benefitGrantPaths.mint(BRAND)).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/benefit-grants',
    );
  });

  it('puts the moderation queue and its decisions under the brand reviews', () => {
    expect(reviewPaths.moderationQueue(BRAND)).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/reviews/moderation-queue',
    );
    expect(reviewPaths.moderationDecision(BRAND, 'r1')).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/reviews/r1/moderation-decisions',
    );
  });

  it('escapes an identifier rather than trusting it', () => {
    expect(reviewPaths.recoveryCase(BRAND, 'a/b', 'c d')).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/locations/a%2Fb/recovery-cases/c%20d',
    );
  });
});

describe('feedbackSettingsPaths (ADR 0159)', () => {
  it('builds the tag library, one tag and the starter pack under the tenant', () => {
    expect(feedbackSettingsPaths.tags('t1')).toBe('/api/v1/operations/tenants/t1/feedback/tags');
    expect(feedbackSettingsPaths.tag('t1', 'g1')).toBe(
      '/api/v1/operations/tenants/t1/feedback/tags/g1',
    );
    expect(feedbackSettingsPaths.starterPack('t1')).toBe(
      '/api/v1/operations/tenants/t1/feedback/tags/starter-pack',
    );
  });

  it('builds each policy at the tenant and at a brand', () => {
    expect(feedbackSettingsPaths.promptPolicy('t1')).toBe(
      '/api/v1/operations/tenants/t1/feedback/prompt-policy',
    );
    expect(feedbackSettingsPaths.brandPromptPolicy(BRAND)).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/feedback/prompt-policy',
    );
    expect(feedbackSettingsPaths.recoveryPolicy('t1')).toBe(
      '/api/v1/operations/tenants/t1/feedback/recovery-policy',
    );
    expect(feedbackSettingsPaths.brandRecoveryPolicy(BRAND)).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/feedback/recovery-policy',
    );
  });
});
