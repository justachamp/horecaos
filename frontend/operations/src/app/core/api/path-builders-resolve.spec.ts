// This app's tsconfig carries no "node" types (a browser app has no business importing Node built-ins at
// runtime): these run only under Vitest's Node test runner and never ship to a browser, the exception
// `preference-vocabulary.spec.ts` and `activity-log-action-codes-coverage.spec.ts` already carry.
// @ts-expect-error node:fs is available at Vitest's Node runtime
import { readFileSync, readdirSync } from 'node:fs';
// @ts-expect-error node:path is available at Vitest's Node runtime
import { dirname, resolve } from 'node:path';
// @ts-expect-error node:url is available at Vitest's Node runtime
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import * as catalog from './catalog-paths';
import * as content from './content-paths';
import * as delivery from './delivery-paths';
import * as finance from './finance-paths';
import * as geo from './geo-paths';
import * as lead from './lead-paths';
import * as loyalty from './loyalty-paths';
import * as marketing from './marketing-paths';
import * as operations from './operations-paths';
import * as pos from './pos-paths';
import * as print from './print-paths';
import * as promoCodes from './promo-codes-paths';
import * as promotion from './promotion-paths';
import * as referral from './referral-paths';
import * as reports from './reports-paths';
import * as reviews from './reviews-paths';
import * as settings from './settings-paths';
import * as staff from './staff-paths';
import * as terminal from './terminal-paths';

/**
 * Every path builder of every `core/api/*-paths.ts`, resolved against the OpenAPI baseline (ADR 0057).
 *
 * **Why this exists (rows 10.9a and 3.2, batch-20 audit).** The seven `notificationTemplate*` builders
 * built `/api/v1/operations/tenants/{t}/brands/{b}/notification-templates` while
 * `NotificationTemplateController` is mapped at `/api/v1/tenants/{t}/brands/{b}/notification-templates`.
 * A probe through the real stack answered 404 for the console's path and 403 for the controller's, so the
 * template editor could not list, create, version, activate, test-send or load its variable catalogue,
 * and every suite was green: each spec of the screen replaces the API with a stub and so never sees a
 * URL, and no spec asserted the literal path. A script run by hand found it; a script run by hand is not
 * a gate. This is the gate.
 *
 * **What it does.** Calls every function of every exported builder object with an argument that stands
 * for an id, a scope or a number, strips the query string, and requires the result to match a path the
 * full v1 baseline (`platform/api/openapi/v1/horecaos-api.json`) publishes, a `{parameter}` matching one
 * segment. The full document rather than the operations group, because this console calls a few
 * control-plane paths on purpose (`settings-paths.ts` says which and why).
 *
 * **What it does not do.** It checks the path and not the method, and a literal segment that happens to
 * match another endpoint's `{parameter}` passes. It catches the wrong prefix, the wrong controller and
 * the typo, which are the defects that actually happened. The method is pinned for the notification
 * family by `settings-paths.spec.ts`, which also reads the baseline.
 */
const FAMILIES: Readonly<Record<string, Record<string, unknown>>> = {
  'catalog-paths': catalog,
  'content-paths': content,
  'delivery-paths': delivery,
  'finance-paths': finance,
  'geo-paths': geo,
  'lead-paths': lead,
  'loyalty-paths': loyalty,
  'marketing-paths': marketing,
  'operations-paths': operations,
  'pos-paths': pos,
  'print-paths': print,
  'promo-codes-paths': promoCodes,
  'promotion-paths': promotion,
  'referral-paths': referral,
  'reports-paths': reports,
  'reviews-paths': reviews,
  'settings-paths': settings,
  'staff-paths': staff,
  'terminal-paths': terminal,
};

type Exemption = 'PREFIX' | 'PLACEHOLDER' | 'REACH' | 'KNOWN_DEFECT';

/**
 * Builders that do not resolve, each with the reason, read rather than ignored.
 *
 * - `PREFIX`: a base another builder appends to, never called on its own.
 * - `PLACEHOLDER`: the controller takes a segment this gate cannot know (a geocoded query).
 * - `REACH`: a brand-scoped route the builder asks at branch reach; the screen that calls it does not.
 * - `KNOWN_DEFECT`: a builder that disagrees with the platform and has an owner. It is allowed to be
 *   fixed by another change without this file changing (the test reports it instead of failing), so the
 *   order two merging waves land in does not matter; the line is deleted by whoever fixes the builder.
 *
 * A `PREFIX`, `PLACEHOLDER` or `REACH` entry that now resolves fails: an exemption outliving its reason
 * is how a list like this stops meaning anything.
 */
const EXEMPT: Readonly<Record<string, Exemption>> = {
  'catalog-paths.catalogPaths.base': 'PREFIX',
  'catalog-paths.pricingPaths.base': 'PREFIX',
  'delivery-paths.dispatchRulesPaths.base': 'PREFIX',
  'loyalty-paths.loyaltyPolicyPaths.base': 'PREFIX',
  'referral-paths.referralPaths.base': 'PREFIX',
  'settings-paths.settingsPaths.channelSetup': 'PREFIX',
  'settings-paths.settingsPaths.notificationRouting': 'PREFIX',
  'geo-paths.geoPaths.lookup': 'PLACEHOLDER',
  'lead-paths.leadPaths.assignment': 'REACH',
  // Row 3.2: built on /api/v1/operations/tenants/... while OperationsCourierPositionController is mapped
  // under /api/v1/tenants/... . Owned by the live-map change (it repoints them in the same batch).
  'operations-paths.operationsPaths.courierPositions': 'KNOWN_DEFECT',
  'operations-paths.operationsPaths.courierTrackReveals': 'KNOWN_DEFECT',
};

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../../../../../..');
const baseline = JSON.parse(
  readFileSync(resolve(repo, 'platform/api/openapi/v1/horecaos-api.json'), 'utf8') as string,
) as { paths: Record<string, unknown> };

function escapeForRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const PUBLISHED: readonly RegExp[] = Object.keys(baseline.paths).map(
  (template) =>
    new RegExp(
      '^' +
        template
          .split('/')
          .map((segment) => (/^\{[^}]+\}$/.test(segment) ? '[^/]+' : escapeForRegExp(segment)))
          .join('/') +
        '$',
    ),
);

/**
 * One argument that serves as a scope (`scope.tenantId`, `scope.brandId`, any property), as an id string
 * and as a number: every property reads as a plain id-like string and the value itself coerces to one.
 */
function marker(): unknown {
  return new Proxy(
    {},
    {
      get(_target, property) {
        if (property === Symbol.toPrimitive || property === 'toString' || property === 'valueOf') {
          return () => 'zz1';
        }
        return typeof property === 'symbol' ? undefined : 'zz1';
      },
    },
  );
}

interface Built {
  readonly key: string;
  readonly path: string | null;
  readonly failure: string | null;
}

function buildEvery(): readonly Built[] {
  const built: Built[] = [];
  for (const [file, namespace] of Object.entries(FAMILIES)) {
    for (const [exportName, family] of Object.entries(namespace)) {
      if (typeof family !== 'object' || family === null) {
        continue;
      }
      for (const [name, builder] of Object.entries(family as Record<string, unknown>)) {
        if (typeof builder !== 'function') {
          continue;
        }
        const key = `${file}.${exportName}.${name}`;
        try {
          const args = Array.from({ length: builder.length }, () => marker());
          const result: unknown = builder.apply(family, args);
          built.push({
            key,
            path: typeof result === 'string' ? result.split('?')[0] : null,
            failure: typeof result === 'string' ? null : `returned ${typeof result}`,
          });
        } catch (thrown) {
          built.push({ key, path: null, failure: `threw ${String(thrown)}` });
        }
      }
    }
  }
  return built;
}

const BUILT = buildEvery();

function resolves(path: string): boolean {
  return PUBLISHED.some((pattern) => pattern.test(path));
}

describe('every path builder resolves against the published OpenAPI baseline', () => {
  it('knows every *-paths.ts file in this folder, so a new one cannot be added unchecked', () => {
    const onDisk = (readdirSync(here) as string[])
      .filter((file) => file.endsWith('-paths.ts'))
      .map((file) => file.replace(/\.ts$/, ''))
      .sort();
    expect(Object.keys(FAMILIES).sort()).toEqual(onDisk);
  });

  it('actually calls the builders (a gate that walks nothing passes everything)', () => {
    expect(BUILT.length).toBeGreaterThan(700);
    expect(BUILT.filter((entry) => entry.failure !== null)).toEqual([]);
    expect(
      BUILT.some((entry) => entry.key === 'settings-paths.settingsPaths.notificationTemplates'),
    ).toBe(true);
  });

  it('resolves every builder that is not exempt, naming each one that does not', () => {
    const unresolved = BUILT.filter(
      (entry) => entry.path !== null && !resolves(entry.path) && EXEMPT[entry.key] === undefined,
    ).map((entry) => `${entry.key} -> ${entry.path}`);
    expect(
      unresolved,
      'a builder points at a path the platform does not publish: a 404 in a real browser that no stubbed spec will ever see',
    ).toEqual([]);
  });

  it('keeps no exemption that has stopped being true', () => {
    const outlived = BUILT.filter((entry) => {
      const reason = EXEMPT[entry.key];
      return (
        reason !== undefined &&
        reason !== 'KNOWN_DEFECT' &&
        entry.path !== null &&
        resolves(entry.path)
      );
    }).map((entry) => entry.key);
    expect(outlived, 'delete these lines from EXEMPT: they resolve now').toEqual([]);

    const unknown = Object.keys(EXEMPT).filter((key) => !BUILT.some((entry) => entry.key === key));
    expect(unknown, 'these exemptions name a builder that no longer exists').toEqual([]);
  });

  it('puts the notification-template family on the controller prefix, not the operations one', () => {
    // The defect this file exists for, asserted by name so the gate cannot pass it by exemption.
    const family = BUILT.filter((entry) => entry.key.includes('settingsPaths.notification'));
    expect(family.length).toBeGreaterThanOrEqual(7);
    for (const entry of family) {
      expect(entry.path, entry.key).not.toContain('/api/v1/operations/');
    }
    expect(EXEMPT['settings-paths.settingsPaths.notificationTemplates']).toBeUndefined();
    expect(EXEMPT['settings-paths.settingsPaths.notificationVariableCatalogue']).toBeUndefined();
  });
});
