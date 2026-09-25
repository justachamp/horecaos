// This app's tsconfig carries no "node" types (a browser app has no business
// importing Node built-ins at runtime) — these run only under Vitest's Node
// test runner, never shipped to a browser, the same exception
// design-tokens.testing.ts already carries for the identical reason.
// @ts-expect-error node:fs is available at Vitest's Node runtime
import { existsSync, readFileSync, readdirSync } from 'node:fs';
// @ts-expect-error node:path is available at Vitest's Node runtime
import { dirname, join, resolve } from 'node:path';
// @ts-expect-error node:url is available at Vitest's Node runtime
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { LOCALES } from '../../core/i18n/i18n';
import { messagesEn } from '../../core/i18n/messages.en';
import { messagesRu } from '../../core/i18n/messages.ru';
import { messagesUzLatn } from '../../core/i18n/messages.uz-latn';
import { activityLogActionLabelKey, bulkActivityLogActionSentence } from './activity-log-action-labels';

/**
 * Staff 9.3 — "extend the action-code dictionary to full coverage of the
 * codes the audit tables actually contain (enumerate them from the code, add
 * a test that every emitted code has a sentence in all three locales)".
 *
 * **What "enumerate them from the code" means here, precisely.** This walks
 * `platform/src/main/java` and regex-matches every `AuditFact.of("<code>", …)`
 * call whose action code is a string literal written at the call site —
 * mechanically the same thing a human reading the source and copying each
 * code down would do. It is a real enumeration, not a fixed list this file
 * hand-maintains: a new literal-coded `AuditFact.of(...)` call added to the
 * platform starts failing this test the next run, before it ever reaches an
 * operator's screen as a bare dotted code.
 *
 * **What it cannot see, and why that is a stated limit, not a silent gap.**
 * A handful of producers build the action code from a variable rather than
 * writing it inline — `TenantControlPlaneService`'s shared `recordAudit`
 * takes `actionCode` as a parameter, so `tenant.suspended`/`.reactivated` are
 * literals at `suspendTenant`/`reactivateTenant`'s own call sites one level
 * up, not at `AuditFact.of(actionCode, …)` itself; `LegalEntityService`,
 * `OrderStateService`, `CustomerProfileService`, `CustomerSessionService`,
 * `CourierShiftService`, `PlannedShiftService`, `MediaAssetService` and a few
 * more follow the same shape. Three codes are also built by string
 * concatenation with a runtime suffix (`"approval." + decision.name()`,
 * `"dinein.reservation." + to.name()`, `"fulfillment.dispatch." +
 * actionSuffix`) — this scan only recovers each one's static prefix, which is
 * not a code on its own, so those three are excluded below by name rather
 * than reported as false failures. None of this is invisible in the
 * dictionary itself: every one of these known-indirect codes is named by
 * hand in `ACTION_LABEL_KEYS` (see that file's own doc), this test simply
 * cannot *discover* them by scanning literals, so it is not what asserts
 * they stay covered — a change to one of those call sites is unguarded by
 * this particular test and relies on the reviewer noticing.
 */
describe('activity log action-code dictionary coverage', () => {
  const javaRoot = resolve(
    dirname(fileURLToPath(import.meta.url)),
    '../../../../../../platform/src/main/java',
  );

  const CONCATENATED_PREFIXES = new Set(['approval.', 'dinein.reservation.', 'fulfillment.dispatch.']);

  function literalActionCodesFromJavaSources(): readonly string[] {
    const codes = new Set<string>();
    const pattern = /AuditFact\.of\("([^"]+)"/g;

    function walk(dir: string): void {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) {
          walk(full);
        } else if (entry.isFile() && entry.name.endsWith('.java')) {
          const text = readFileSync(full, 'utf8');
          for (const match of text.matchAll(pattern)) {
            codes.add(match[1]);
          }
        }
      }
    }

    walk(javaRoot);
    return Array.from(codes)
      .filter((code) => !CONCATENATED_PREFIXES.has(code))
      .sort();
  }

  it('finds the platform sources to scan (this test is not silently a no-op)', () => {
    expect(existsSync(javaRoot)).toBe(true);
    const codes = literalActionCodesFromJavaSources();
    // A floor, not the exact count: proves the scan actually walked the tree
    // and matched real call sites, without pinning this test to a count that
    // drifts every time a producer is added or renamed.
    expect(codes.length).toBeGreaterThan(150);
  });

  it('names a label key or a bulk sentence for every literal-coded AuditFact.of(...) call site', () => {
    const codes = literalActionCodesFromJavaSources();
    const uncovered = codes.filter(
      (code) => activityLogActionLabelKey(code) === null && bulkActivityLogActionSentence(code, 'en') === null,
    );
    expect(uncovered).toEqual([]);
  });

  it('has a non-blank sentence in all three locales for every mapped label key', () => {
    const codes = literalActionCodesFromJavaSources();
    const keys = new Set(codes.map((code) => activityLogActionLabelKey(code)).filter((key) => key !== null));

    const catalogues: readonly [string, Readonly<Record<string, string>>][] = [
      ['en', messagesEn],
      ['ru', messagesRu],
      ['uz-Latn', messagesUzLatn],
    ];
    for (const [locale, catalogue] of catalogues) {
      const blankOrMissing = Array.from(keys).filter((key) => !catalogue[key as string]?.trim());
      expect(blankOrMissing, `blank or missing in ${locale}`).toEqual([]);
    }
  });

  it('has a non-blank bulk sentence in all three locales for every code with no hand-curated label key', () => {
    const codes = literalActionCodesFromJavaSources();
    const bulkOnly = codes.filter((code) => activityLogActionLabelKey(code) === null);
    expect(bulkOnly.length).toBeGreaterThan(150);

    for (const locale of LOCALES) {
      const blankOrMissing = bulkOnly.filter(
        (code) => !bulkActivityLogActionSentence(code, locale)?.trim(),
      );
      expect(blankOrMissing, `blank or missing in ${locale}`).toEqual([]);
    }
  });
});
