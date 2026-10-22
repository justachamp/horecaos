// This app's tsconfig carries no "node" types; these run only under Vitest's Node test runner and are
// never shipped to a browser (the same exception activity-log-action-codes-coverage.spec.ts carries).
// @ts-expect-error node:fs is available at Vitest's Node runtime
import { readdirSync } from 'node:fs';
// @ts-expect-error node:path is available at Vitest's Node runtime
import { dirname } from 'node:path';
// @ts-expect-error node:url is available at Vitest's Node runtime
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import controlPlaneBaseline from '../../../../../../platform/api/openapi/v1/horecaos-api.control-plane.json';
import operationsBaseline from '../../../../../../platform/api/openapi/v1/horecaos-api.operations.json';
import storefrontBaseline from '../../../../../../platform/api/openapi/v1/horecaos-api.storefront.json';
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
 * The builder-versus-baseline conformance gate (ADR 0057, ADR 0031; rows 3.2 and 10.9a).
 *
 * **Why it exists.** Row 3.2 shipped with `courierPositions` and `courierTrackReveals` built on
 * `/api/v1/operations/tenants/...` while the controller is mapped at
 * `/api/v1/tenants/{t}/brands/{b}/locations/{l}/operations/couriers`. Every real call 404'd and
 * nothing failed, because every spec of the live map replaced the API with a stub and so never saw
 * a URL. The same wrong prefix was on the seven `notificationTemplate*` builders (row 10.9a). A
 * script run by hand in the batch-20 audit found both; a script run by hand is not a gate.
 *
 * **What it does.** Every function in every `*Paths` object of every `*-paths.ts` file is called
 * with marker arguments, the path it returns is stripped of its query string, and the result must
 * match a path `platform/api/openapi/v1/horecaos-api.operations.json` publishes (a `{parameter}` in
 * the baseline matches any one segment). A builder pointing at a prefix the platform does not serve
 * fails here, named, instead of in a dispatcher's browser.
 *
 * **Two other surfaces are reached on purpose.** The catalogue, pricing, POS-sync and channel
 * screens of this console call controllers the platform groups under `/api/v1/control-plane/**`
 * (and the menu preview calls `/api/v1/storefront/**`). A builder on one of those prefixes is
 * checked against that surface's own baseline instead; a builder on any other prefix has to be in
 * the operations baseline. The surface a builder lands on is therefore a stated fact and not an
 * accident.
 *
 * **What it does not do.** It checks the path and not the method, and a builder whose literal
 * segment happens to match a `{parameter}` of another endpoint passes: this gate catches the wrong
 * prefix, the wrong controller and the typo, which are the defects that have actually happened.
 * `PATH_FAMILIES` is explicit on purpose (a bundler cannot glob a directory); a new `*-paths.ts`
 * file is added there.
 */
const PATH_FAMILIES: Readonly<Record<string, Record<string, unknown>>> = {
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

/**
 * Builders known to disagree with the baseline, each with the row that owns the fix. An entry is
 * deleted by the change that fixes it; an entry that now conforms is reported by the last test
 * rather than failing, so the order of two merging waves does not matter.
 */
const KNOWN_MISMATCHES: Readonly<Record<string, string>> = {
  // Row 10.9a: built on /api/v1/operations/tenants/{t}/brands/{b}/notification-templates, which the
  // platform does not serve; NotificationTemplateController is mapped under /api/v1/tenants. The
  // wave that owns row 10.9a (w9) repoints these builders; until it merges this gate lists them.
  'settings-paths.settingsPaths.notificationTemplates': 'row 10.9a',
  'settings-paths.settingsPaths.notificationTemplate': 'row 10.9a',
  'settings-paths.settingsPaths.notificationTemplateVersions': 'row 10.9a',
  'settings-paths.settingsPaths.notificationTemplateVersion': 'row 10.9a',
  'settings-paths.settingsPaths.notificationTemplateActivate': 'row 10.9a',
  'settings-paths.settingsPaths.notificationTemplateTestSend': 'row 10.9a',
  'settings-paths.settingsPaths.notificationVariableCatalogue': 'row 10.9a',
};

/**
 * A builder that cannot be called with markers alone names its arguments here, one tuple per
 * variant it has. The lead queue is reached at two levels (a `LeadReach` is not a scope), and the
 * geocode lookup takes the kind of lookup, not an identifier.
 */
const BRAND_REACH = { kind: 'BRAND', tenantId: 'ZZT', brandId: 'ZZB' } as const;
const LOCATION_REACH = {
  kind: 'LOCATION',
  tenantId: 'ZZT',
  brandId: 'ZZB',
  locationId: 'ZZL',
} as const;
const BRAND_SCOPE = { tenantId: 'ZZT', brandId: 'ZZB' } as const;

const ARGUMENT_OVERRIDES: Readonly<Record<string, readonly (readonly unknown[])[]>> = {
  'lead-paths.leadPaths.leads': [[BRAND_REACH], [LOCATION_REACH]],
  'lead-paths.leadPaths.lead': [
    [BRAND_REACH, 'ZZ1'],
    [LOCATION_REACH, 'ZZ1'],
  ],
  'lead-paths.leadPaths.transitions': [
    [BRAND_REACH, 'ZZ1'],
    [LOCATION_REACH, 'ZZ1'],
  ],
  // "Brand reach only: a branch does not decide who else gets a lead" (lead-paths.ts).
  'lead-paths.leadPaths.assignment': [[BRAND_REACH, 'ZZ1']],
  'lead-paths.leadPaths.customerLink': [
    [BRAND_REACH, 'ZZ1'],
    [LOCATION_REACH, 'ZZ1'],
  ],
  'lead-paths.leadPaths.contact': [
    [BRAND_REACH, 'ZZ1'],
    [LOCATION_REACH, 'ZZ1'],
  ],
  'lead-paths.leadPaths.contactAttempts': [
    [BRAND_REACH, 'ZZ1'],
    [LOCATION_REACH, 'ZZ1'],
  ],
  'geo-paths.geoPaths.lookup': [
    [BRAND_SCOPE, 'suggestions'],
    [BRAND_SCOPE, 'resolutions', 'ZZL'],
    [BRAND_SCOPE, 'reverse-resolutions', 'ZZL'],
  ],
};

/**
 * Builders that return the front of a path and not an endpoint of their own (a `base` the others
 * extend). They conform when what they return is the front of a published path, which still
 * catches a wrong prefix.
 */
const PREFIX_HELPERS: ReadonlySet<string> = new Set([
  'settings-paths.settingsPaths.notificationRouting',
  // The root every /setup/* endpoint extends; ChannelSetupController publishes none at /setup itself.
  'settings-paths.settingsPaths.channelSetup',
]);

function isPrefixHelper(key: string): boolean {
  return key.endsWith('.base') || PREFIX_HELPERS.has(key);
}

interface Surface {
  readonly name: string;
  /** Builders on this prefix are checked against this surface's baseline; `null` is the operations baseline itself. */
  readonly prefix: string | null;
  readonly templates: readonly PublishedPath[];
}

interface PublishedPath {
  readonly template: string;
  readonly pattern: RegExp;
}

function escapeForRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function published(document: unknown): readonly PublishedPath[] {
  const paths = (document as { paths: Record<string, unknown> }).paths;
  return Object.keys(paths).map((template) => ({
    template,
    pattern: new RegExp(
      '^' +
        template
          .split('/')
          .map((segment) => (/^\{[^}]+\}$/.test(segment) ? '[^/]+' : escapeForRegExp(segment)))
          .join('/') +
        '$',
    ),
  }));
}

const SURFACES: readonly Surface[] = [
  { name: 'operations', prefix: null, templates: published(operationsBaseline) },
  {
    name: 'control-plane',
    prefix: '/api/v1/control-plane/',
    templates: published(controlPlaneBaseline),
  },
  { name: 'storefront', prefix: '/api/v1/storefront/', templates: published(storefrontBaseline) },
];

/** The scope-shaped first argument, which is also a perfectly good tenant id string. */
function scopeMarker(): Record<string, unknown> {
  return {
    tenantId: 'ZZT',
    brandId: 'ZZB',
    locationId: 'ZZL',
    toString: () => 'ZZT',
  };
}

function argumentVariants(key: string, arity: number): readonly (readonly unknown[])[] {
  return (
    ARGUMENT_OVERRIDES[key] ?? [
      Array.from({ length: arity }, (_, index) => (index === 0 ? scopeMarker() : `ZZ${index}`)),
    ]
  );
}

interface Resolved {
  readonly key: string;
  readonly path: string | null;
  readonly failure: string | null;
}

function resolveAll(): readonly Resolved[] {
  const resolved: Resolved[] = [];
  for (const [file, module] of Object.entries(PATH_FAMILIES)) {
    for (const [exportName, family] of Object.entries(module)) {
      if (family === null || typeof family !== 'object') {
        continue;
      }
      for (const [name, builder] of Object.entries(family as Record<string, unknown>)) {
        const key = `${file}.${exportName}.${name}`;
        if (typeof builder === 'string') {
          resolved.push({ key, path: builder, failure: null });
        } else if (typeof builder === 'function') {
          for (const args of argumentVariants(key, builder.length)) {
            try {
              const value: unknown = (builder as (...args: unknown[]) => unknown).apply(family, [
                ...args,
              ]);
              resolved.push(
                typeof value === 'string'
                  ? { key, path: value, failure: null }
                  : { key, path: null, failure: `returned ${typeof value}, not a path` },
              );
            } catch (failure) {
              resolved.push({
                key,
                path: null,
                failure: failure instanceof Error ? failure.message : String(failure),
              });
            }
          }
        }
      }
    }
  }
  return resolved;
}

const RESOLVED = resolveAll();

function withoutQuery(path: string): string {
  const cut = path.search(/[?#]/);
  return cut === -1 ? path : path.slice(0, cut);
}

function surfaceOf(path: string): Surface {
  return (
    SURFACES.find((surface) => surface.prefix !== null && path.startsWith(surface.prefix)) ??
    SURFACES[0]
  );
}

/** The published template this path is, or `null`. A prefix helper may instead be the front of one. */
function publishedAs(key: string, path: string): string | null {
  const bare = withoutQuery(path);
  const surface = surfaceOf(bare);
  const exact = surface.templates.find((candidate) => candidate.pattern.test(bare));
  if (exact) {
    return exact.template;
  }
  return isPrefixHelper(key) ? frontOf(surface, bare) : null;
}

function frontOf(surface: Surface, bare: string): string | null {
  const segments = bare.split('/');
  for (const candidate of surface.templates) {
    const template = candidate.template.split('/');
    if (template.length <= segments.length) {
      continue;
    }
    const head = template.slice(0, segments.length);
    const matches = head.every((segment, index) =>
      /^\{[^}]+\}$/.test(segment) ? segments[index].length > 0 : segment === segments[index],
    );
    if (matches) {
      return candidate.template;
    }
  }
  return null;
}

describe('path builders against the operations OpenAPI baseline (ADR 0057)', () => {
  it('has read the baselines, and found the builders to check', () => {
    // A gate that quietly checks nothing (an empty baseline, an import that resolved to {}) would
    // be the worst kind of green.
    expect(SURFACES[0].templates.length).toBeGreaterThan(500);
    expect(SURFACES[1].templates.length).toBeGreaterThan(100);
    expect(SURFACES[2].templates.length).toBeGreaterThan(20);
    expect(RESOLVED.length).toBeGreaterThan(500);
    expect(RESOLVED.some((builder) => builder.key.includes('.operationsPaths.'))).toBe(true);
  });

  it('can call every builder with markers (an override names the arguments of one that cannot)', () => {
    expect(
      RESOLVED.filter((builder) => builder.failure !== null).map(
        (builder) => `${builder.key}: ${builder.failure}`,
      ),
    ).toEqual([]);
  });

  it('every builder resolves to a path the platform publishes', () => {
    const unpublished = RESOLVED.filter(
      (builder) =>
        builder.path !== null &&
        KNOWN_MISMATCHES[builder.key] === undefined &&
        publishedAs(builder.key, builder.path) === null,
    ).map((builder) => `${builder.key} -> ${withoutQuery(builder.path ?? '')}`);

    expect(unpublished).toEqual([]);
  });

  it('reaches the control-plane and storefront surfaces only on purpose, by their own prefixes', () => {
    const surfaces = new Set(
      RESOLVED.filter((builder) => builder.path !== null).map(
        (builder) => surfaceOf(withoutQuery(builder.path ?? '')).name,
      ),
    );
    expect([...surfaces].sort()).toEqual(['control-plane', 'operations', 'storefront']);
  });

  it('would have caught row 3.2: the old live-map prefix matches nothing the platform serves', () => {
    const old =
      '/api/v1/operations/tenants/ZZT/brands/ZZB/locations/ZZL/operations/couriers/positions';
    expect(publishedAs('x', old)).toBeNull();
    expect(
      publishedAs('x', operations.operationsPaths.courierPositions(scopeMarker() as never)),
    ).toBe(
      '/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/operations/couriers/positions',
    );
    expect(
      publishedAs(
        'x',
        operations.operationsPaths.courierTrackReveals(scopeMarker() as never, 'ZZ1'),
      ),
    ).toBe(
      '/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/operations/couriers/{courierId}/track-reveals',
    );
  });

  it('does not let a prefix helper stand in for a wrong prefix', () => {
    expect(
      publishedAs(
        'settings-paths.settingsPaths.notificationRouting',
        '/api/v1/tenants/ZZT/brands/ZZB/notification-routing',
      ),
    ).not.toBeNull();
    expect(
      publishedAs(
        'settings-paths.settingsPaths.notificationRouting',
        '/api/v1/operations/tenants/ZZT/brands/ZZB/notification-routing',
      ),
    ).toBeNull();
    // Only a helper may be a front; an ordinary builder has to be a whole endpoint.
    expect(publishedAs('x', '/api/v1/tenants/ZZT/brands/ZZB/notification-routing')).toBeNull();
  });

  it('keeps no known mismatch for a builder that no longer exists', () => {
    const keys = new Set(RESOLVED.map((builder) => builder.key));
    expect(Object.keys(KNOWN_MISMATCHES).filter((key) => !keys.has(key))).toEqual([]);
  });

  it('says which known mismatches now conform, so a stale entry is visible when a fix lands', () => {
    const fixed = RESOLVED.filter(
      (builder) =>
        builder.path !== null &&
        KNOWN_MISMATCHES[builder.key] !== undefined &&
        publishedAs(builder.key, builder.path) !== null,
    ).map((builder) => builder.key);
    // A fixed builder is the good outcome and is allowed; the list only tells whoever merges the
    // fix to delete the entry above, so two waves can merge in either order.
    if (fixed.length > 0) {
      console.info(
        `path-conformance: now conforming, remove from KNOWN_MISMATCHES: ${fixed.join(', ')}`,
      );
    }
    expect(Object.keys(KNOWN_MISMATCHES).length - fixed.length).toBeGreaterThanOrEqual(0);
  });

  it('lists every *-paths.ts file of this folder, so a new family cannot be left unchecked', () => {
    // PATH_FAMILIES is explicit (a bundler cannot glob a directory), which means a wave that adds a
    // `foo-paths.ts` would otherwise add builders this gate never calls. This reads the folder.
    const onDisk: string[] = (readdirSync(dirname(fileURLToPath(import.meta.url))) as string[])
      .filter((name) => /-paths\.ts$/.test(name))
      .map((name) => name.replace(/\.ts$/, ''));
    expect(onDisk.length).toBeGreaterThan(10);
    expect(onDisk.filter((name) => !(name in PATH_FAMILIES)).sort()).toEqual([]);
    expect(
      Object.keys(PATH_FAMILIES)
        .filter((name) => !onDisk.includes(name))
        .sort(),
    ).toEqual([]);
  });

  it('every listed file exports at least one Paths object and none is an empty shell', () => {
    for (const [file, module] of Object.entries(PATH_FAMILIES)) {
      const families = Object.entries(module).filter(
        ([, value]) => value !== null && typeof value === 'object',
      );
      expect(families.length, `${file} exports no Paths object`).toBeGreaterThan(0);
      for (const [exportName, family] of families) {
        expect(
          Object.keys(family as object).length,
          `${file}.${exportName} has no builders`,
        ).toBeGreaterThan(0);
      }
    }
  });
});
