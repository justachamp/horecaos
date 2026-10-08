// This app's tsconfig carries no "node" types (a browser app has no business importing Node built-ins
// at runtime) -- these run only under Vitest's Node test runner, never shipped to a browser, the same
// exception `activity-log-action-codes-coverage.spec.ts` carries for the identical reason.
// @ts-expect-error node:fs is available at Vitest's Node runtime
import { readFileSync } from 'node:fs';
// @ts-expect-error node:path is available at Vitest's Node runtime
import { dirname, resolve } from 'node:path';
// @ts-expect-error node:url is available at Vitest's Node runtime
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { staffPaths } from '../api/staff-paths';
import { ORDER_BOARD_COLUMNS } from '../../features/orders/order-board-columns';
import {
  FISCAL_STATUS_ATTENTION,
  FISCAL_STATUS_OPTIONS,
} from '../../features/orders/order-queue-filter-state';
import { ORDER_PAYMENT_STATUS_PROJECTIONS } from '../../features/orders/order-payment-status';
import { ORDER_TABS } from '../../features/orders/order-tabs';
import {
  MAX_SAVED_VIEWS,
  ORDER_BOARD_COLUMN_KEYS,
  PREFERENCE_SURFACES,
  SAVED_VIEW_DATE_RANGES,
  SAVED_VIEW_TABS,
  SavedViewFilters,
} from './preference-documents';
import { ADDABLE_COLUMNS, DEFAULT_SHOWN_COLUMNS, MAX_SAVED_VIEW_LABEL } from './preference-edits';

/**
 * ADR 0160, «Drift»: the column and filter vocabularies exist twice, in Java and in TypeScript, and the
 * console's registries must agree with the platform's. The platform publishes its side as an OpenAPI
 * enumeration, so it appears in the generated client as unions; this reads that client and the two Java
 * sources the OpenAPI document cannot carry (which columns are shown by default, the limits), and fails
 * the build when a list drifts.
 *
 * The registry itself is typed against the same vocabulary at compile time (`satisfies Record<
 * OrderBoardColumnKey, …>` in `order-board-columns.ts`); this closes the other side.
 */

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../../../../../..');
const generated = readFileSync(
  resolve(repo, 'platform/api/generated/horecaos-api-v1.operations.ts'),
  'utf8',
) as string;
const java = (path: string): string =>
  readFileSync(
    resolve(repo, `platform/src/main/java/uz/horecaos/platform/iam/${path}`),
    'utf8',
  ) as string;

/** The body of `export interface <name> { … }` in the generated client. */
function interfaceBody(name: string): string {
  const start = generated.indexOf(`export interface ${name} {`);
  expect(start, `${name} is in the generated client`).toBeGreaterThanOrEqual(0);
  return generated.slice(start, generated.indexOf('\n}', start));
}

/** The quoted members of one property's union, `Array<…>` or bare. */
function unionOf(interfaceName: string, property: string): string[] {
  const line = interfaceBody(interfaceName)
    .split('\n')
    .find((candidate) => candidate.trim().startsWith(`${property}?:`));
  expect(line, `${interfaceName}.${property}`).toBeDefined();
  return [...(line as string).matchAll(/"([^"]+)"/g)].map((match) => match[1]);
}

function propertiesOf(interfaceName: string): string[] {
  return interfaceBody(interfaceName)
    .split('\n')
    .slice(1)
    .map((line) => /^\s{2}(\w+)\??:/.exec(line)?.[1])
    .filter((name): name is string => name !== undefined);
}

const sorted = (values: readonly string[]): string[] => [...values].sort();

describe('the preference vocabulary, console against platform (ADR 0160)', () => {
  it('names the same ten board columns on both sides of a document', () => {
    expect(sorted(unionOf('OrderBoardColumnsChange', 'show'))).toEqual(
      sorted(ORDER_BOARD_COLUMN_KEYS),
    );
    expect(sorted(unionOf('OrderBoardColumnsChange', 'hide'))).toEqual(
      sorted(ORDER_BOARD_COLUMN_KEYS),
    );
    expect(sorted(Object.keys(ORDER_BOARD_COLUMNS))).toEqual(sorted(ORDER_BOARD_COLUMN_KEYS));
  });

  it('agrees with the platform on which columns are shown by default', () => {
    const source = java('application/staff/OrderBoardColumn.java');
    const declared = [...source.matchAll(/^\s+[A-Z_]+\("(\w+)",\s*(true|false)\)/gm)].map(
      (match) => [match[1], match[2] === 'true'] as const,
    );

    expect(declared.map(([key]) => key)).toEqual([...ORDER_BOARD_COLUMN_KEYS]);
    expect(declared.filter(([, shown]) => shown).map(([key]) => key)).toEqual([
      ...DEFAULT_SHOWN_COLUMNS,
    ]);
    expect(declared.filter(([, shown]) => !shown).map(([key]) => key)).toEqual([
      ...ADDABLE_COLUMNS,
    ]);
  });

  it('agrees on the tabs a view may open on, and on the board’s own tab ids', () => {
    expect(unionOf('SavedView', 'tab')).toEqual([...SAVED_VIEW_TABS]);
    expect([...ORDER_TABS]).toEqual([...SAVED_VIEW_TABS]);
  });

  it('agrees on the closed list of filters a view may hold, and on each enumeration', () => {
    const allFilters: Required<SavedViewFilters> = {
      allBranches: true,
      channelCode: 'WEB',
      origin: 'HORECAOS',
      fulfillmentMode: 'DELIVERY',
      paymentMethodCode: 'CASH',
      paymentStatus: 'PENDING',
      fiscalStatus: 'ISSUED',
      lateOnly: true,
      problemOnly: true,
      callbackRequested: true,
      mineOnly: true,
      dateRange: 'TODAY',
    };

    expect(sorted(propertiesOf('SavedViewFilters'))).toEqual(sorted(Object.keys(allFilters)));
    expect(unionOf('SavedViewFilters', 'dateRange')).toEqual([...SAVED_VIEW_DATE_RANGES]);
    expect(unionOf('SavedViewFilters', 'origin')).toEqual(['HORECAOS', 'MARKETPLACE']);
    expect(sorted(unionOf('SavedViewFilters', 'fulfillmentMode'))).toEqual(
      sorted(['DELIVERY', 'PICKUP', 'DINE_IN']),
    );
    expect(sorted(unionOf('SavedViewFilters', 'paymentStatus'))).toEqual(
      sorted(ORDER_PAYMENT_STATUS_PROJECTIONS),
    );
    expect(sorted(unionOf('SavedViewFilters', 'fiscalStatus'))).toEqual(
      sorted([FISCAL_STATUS_ATTENTION, ...FISCAL_STATUS_OPTIONS]),
    );
  });

  it('never lets a view hold a search term, a customer, an order, a courier, a binding or an exact date', () => {
    const held = propertiesOf('SavedViewFilters');

    for (const forbidden of [
      'reference',
      'q',
      'customerId',
      'orderId',
      'courierId',
      'marketplaceBindingId',
      'locationId',
      'dateStart',
      'dateEnd',
    ]) {
      expect(held, forbidden).not.toContain(forbidden);
    }
  });

  it('agrees on the surfaces and the limits', () => {
    const surfaces = [
      ...java('application/staff/StaffPreferenceSurface.java').matchAll(/^\s+([A-Z_]+)[,;]/gm),
    ].map((match) => match[1]);
    const limits = java('application/staff/StaffPreferenceSchemas.java');

    expect(surfaces).toEqual([...PREFERENCE_SURFACES]);
    expect(limits).toMatch(new RegExp(`MAX_SAVED_VIEWS = ${MAX_SAVED_VIEWS};`));
    expect(limits).toMatch(new RegExp(`MAX_LABEL_CHARACTERS = ${MAX_SAVED_VIEW_LABEL};`));
  });

  it('calls the routes the platform publishes, by the paths it publishes them under', () => {
    const toTemplate = (path: string): string =>
      path.replace('/T/', '/{tenantId}/').replace(/\/S$/, '/{surface}');

    expect(generated).toContain(
      `"readMyPreferences": { method: "GET"; path: "${toTemplate(staffPaths.mePreferences('T'))}"`,
    );
    expect(generated).toContain(
      `"saveMyPreference": { method: "PUT"; path: "${toTemplate(staffPaths.mePreference('T', 'S'))}"`,
    );
    expect(generated).toContain(
      `"resetMyPreference": { method: "DELETE"; path: "${toTemplate(staffPaths.mePreference('T', 'S'))}"`,
    );
  });

  it('publishes the board’s staff names on the order row, beside the actor ids they replace on screen', () => {
    const row = propertiesOf('OperationsOrderControllerOrderSummaryResponse');

    expect(row).toContain('createdByDisplayName');
    expect(row).toContain('acceptedByDisplayName');
    expect(row).toContain('createdByActorId');
  });
});
