// Node built-ins, for the reason `path-builders-resolve.spec.ts` gives: Vitest's Node runner only.
// @ts-expect-error node:fs is available at Vitest's Node runtime
import { readFileSync } from 'node:fs';
// @ts-expect-error node:path is available at Vitest's Node runtime
import { dirname, resolve } from 'node:path';
// @ts-expect-error node:url is available at Vitest's Node runtime
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { LocationScope } from './operations-paths';
import { settingsPaths } from './settings-paths';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** `NotificationTemplateController`'s `@RequestMapping`, which is where the platform serves every one of them. */
const TEMPLATES = '/api/v1/tenants/t1/brands/b1/notification-templates';

/**
 * Row 10.9a. These assert the literal path each builder produces against the controller's
 * `@RequestMapping`, not that a builder equals itself. For a release the seven builders said
 * `/api/v1/operations/tenants/...`, the platform serves nothing there, and the screen answered 404 to
 * every call while every suite was green.
 */
describe('settingsPaths: notification templates (ADR 0020, row 10.9a)', () => {
  it('builds the template collection and one template on the /api/v1/tenants surface', () => {
    expect(settingsPaths.notificationTemplates(SCOPE)).toBe(TEMPLATES);
    expect(settingsPaths.notificationTemplate(SCOPE, 'tp1')).toBe(`${TEMPLATES}/tp1`);
  });

  it('builds versions, one version, its activation and its test send', () => {
    expect(settingsPaths.notificationTemplateVersions(SCOPE, 'tp1')).toBe(
      `${TEMPLATES}/tp1/versions`,
    );
    expect(settingsPaths.notificationTemplateVersion(SCOPE, 'tp1', 3)).toBe(
      `${TEMPLATES}/tp1/versions/3`,
    );
    expect(settingsPaths.notificationTemplateActivate(SCOPE, 'tp1', 3)).toBe(
      `${TEMPLATES}/tp1/versions/3/activate`,
    );
    expect(settingsPaths.notificationTemplateTestSend(SCOPE, 'tp1', 3)).toBe(
      `${TEMPLATES}/tp1/versions/3/test-send`,
    );
  });

  it('builds the variable catalogue beside the collection, not under a template', () => {
    expect(settingsPaths.notificationVariableCatalogue(SCOPE)).toBe(
      `${TEMPLATES}/variable-catalogue`,
    );
  });

  it('keeps the routing tab where it was, on the same prefix', () => {
    expect(settingsPaths.notificationRoutingBindings(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/notification-routing/bindings',
    );
  });

  it('encodes every segment, so an id cannot escape its place in the path', () => {
    expect(settingsPaths.notificationTemplate(SCOPE, 'a/b')).toBe(`${TEMPLATES}/a%2Fb`);
    expect(
      settingsPaths.notificationTemplates({ tenantId: 't 1', brandId: 'b/1', locationId: 'l1' }),
    ).toBe('/api/v1/tenants/t%201/brands/b%2F1/notification-templates');
  });
});

const here = dirname(fileURLToPath(import.meta.url));
const baseline = JSON.parse(
  readFileSync(
    resolve(here, '../../../../../../platform/api/openapi/v1/horecaos-api.json'),
    'utf8',
  ) as string,
) as { paths: Record<string, Record<string, unknown>> };

/** The published `{parameter}` form of a built path: ids replaced by the placeholder their position takes. */
function publishedMethods(builtPath: string): readonly string[] {
  const segments = builtPath.split('/');
  for (const [template, operations] of Object.entries(baseline.paths)) {
    const parts = template.split('/');
    if (parts.length !== segments.length) {
      continue;
    }
    const matches = parts.every(
      (part, index) => /^\{[^}]+\}$/.test(part) || part === segments[index],
    );
    if (matches) {
      return Object.keys(operations);
    }
  }
  return [];
}

/**
 * The method-aware half of the gate. `path-builders-resolve.spec.ts` proves a builder's path is
 * published; this proves the console's own verb on it is, for the family row 10.9a lost to a wrong
 * prefix. The table is the calls `notifications-api.ts` makes.
 */
describe('settingsPaths: the console calls what the baseline publishes, verb included (row 10.9a)', () => {
  const CALLS: readonly (readonly [string, string, string])[] = [
    ['GET', 'list the templates', settingsPaths.notificationTemplates(SCOPE)],
    ['POST', 'create a template', settingsPaths.notificationTemplates(SCOPE)],
    ['GET', 'read every version', settingsPaths.notificationTemplateVersions(SCOPE, 'tp1')],
    ['POST', 'save a draft version', settingsPaths.notificationTemplateVersions(SCOPE, 'tp1')],
    ['GET', 'read one version', settingsPaths.notificationTemplateVersion(SCOPE, 'tp1', 2)],
    ['POST', 'activate a version', settingsPaths.notificationTemplateActivate(SCOPE, 'tp1', 2)],
    ['POST', 'send a test', settingsPaths.notificationTemplateTestSend(SCOPE, 'tp1', 2)],
    ['GET', 'load the variable catalogue', settingsPaths.notificationVariableCatalogue(SCOPE)],
  ];

  it.each(CALLS)('%s is published to %s', (method, _what, builtPath) => {
    expect(publishedMethods(builtPath), builtPath).toContain(method.toLowerCase());
  });

  it('would notice the old prefix: nothing is published under /api/v1/operations for this controller', () => {
    const old = `/api/v1/operations/tenants/t1/brands/b1/notification-templates`;
    expect(publishedMethods(old)).toEqual([]);
  });
});
