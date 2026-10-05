/**
 * Tests for areas.mjs: first over throwaway app trees, then over the real one. Run: `npm run i18n:dead:test`.
 */
import assert from 'node:assert/strict';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { analyseRoutes, problemsOf } from './areas.mjs';
import { AREA_TABLE, app } from './test-app.mjs';

const I18N = 'src/app/core/i18n';

/** Three areas, one key each in `core`, `orders` and `kitchen`, plus `orders.status.NEW` which is core. */
const CATALOGUE = {
  [`${I18N}/message-areas.ts`]: AREA_TABLE,
  [`${I18N}/messages/core.en.ts`]: `export const coreEn = {\n  'shell.title': 'Shell',\n  'orders.status.NEW': 'New',\n} as const;\n`,
  [`${I18N}/messages/orders.en.ts`]: `export const ordersEn = {\n  'orders.title': 'Orders',\n  'orders.detail.close': 'Close',\n} as const;\n`,
  [`${I18N}/messages/kitchen.en.ts`]: `export const kitchenEn = {\n  'kitchen.title': 'Kitchen',\n} as const;\n`,
  // The aggregate names every key; the analysis must not follow imports into it.
  [`${I18N}/messages.en.ts`]: `export const messagesEn = { 'shell.title': 'x', 'orders.title': 'x', 'kitchen.title': 'x' } as const;\nexport type MessageKey = keyof typeof messagesEn;\n`,
  'src/main.ts': `import { routes } from './app/app.routes';\nconsole.log(routes);\n`,
};

function tree(routes, files = {}) {
  return app({
    ...CATALOGUE,
    'src/app/app.routes.ts': routes,
    ...files,
  });
}

const GUARD_IMPORT = `import { messagesGuard } from './core/i18n/messages.guard';\n`;

test('a route whose code names a key of an area nothing declares is reported, with the way to it', async () => {
  const dir = tree(
    `export const routes = [\n  { path: 'orders', loadComponent: () => import('./features/orders-page').then((m) => m.OrdersPage) },\n];\n`,
    {
      'src/app/features/orders-page.ts': `import { helper } from './helper';\nexport const OrdersPage = helper;\n`,
      'src/app/features/helper.ts': `export const helper = () => i18n.t('orders.title');\n`,
    },
  );

  const analysis = await analyseRoutes(dir);

  assert.deepEqual(analysis.routes.map((row) => [row.path, row.needs, row.declared]), [
    ['orders', ['orders'], []],
  ]);
  const problems = problemsOf(analysis);
  assert.equal(problems.length, 1);
  assert.match(problems[0], /route 'orders' needs the 'orders' messages \(e\.g\. 'orders\.title'/);
  assert.match(problems[0], /src\/app\/features\/orders-page\.ts -> src\/app\/features\/helper\.ts/);
});

test('a guard on the route, or on a parent, covers it; core needs no declaration', async () => {
  const dir = tree(
    GUARD_IMPORT +
      `export const routes = [
  {
    path: 'orders',
    canActivate: [messagesGuard('orders')],
    loadComponent: () => import('./features/orders-page').then((m) => m.OrdersPage),
    children: [
      { path: ':id', loadComponent: () => import('./features/order-pane').then((m) => m.OrderPane) },
    ],
  },
];\n`,
    {
      'src/app/features/orders-page.ts': `export const a = () => i18n.t('orders.title');\n`,
      'src/app/features/order-pane.ts': `export const b = () => [i18n.t('orders.detail.close'), i18n.t('shell.title'), \`orders.status.\${s}\`];\n`,
    },
  );

  const analysis = await analyseRoutes(dir);

  assert.deepEqual(problemsOf(analysis), []);
  assert.deepEqual(
    analysis.routes.map((row) => [row.path, row.declared]),
    [
      ['orders', ['orders']],
      ['orders/:id', ['orders']],
    ],
  );
});

test('a child that needs more than its parent declares must declare it itself', async () => {
  const dir = tree(
    GUARD_IMPORT +
      `export const routes = [
  {
    path: 'orders',
    canActivate: [messagesGuard('orders')],
    loadComponent: () => import('./features/orders-page').then((m) => m.OrdersPage),
    children: [
      { path: 'kitchen', loadComponent: () => import('./features/kitchen-pane').then((m) => m.KitchenPane) },
      { path: 'fine', canActivate: [messagesGuard('kitchen')], loadComponent: () => import('./features/kitchen-pane').then((m) => m.KitchenPane) },
    ],
  },
];\n`,
    {
      'src/app/features/orders-page.ts': `export const a = () => i18n.t('orders.title');\n`,
      'src/app/features/kitchen-pane.ts': `export const b = () => i18n.t('kitchen.title');\n`,
    },
  );

  const problems = problemsOf(await analyseRoutes(dir));

  assert.equal(problems.length, 1);
  assert.match(problems[0], /route 'orders\/kitchen' needs the 'kitchen' messages/);
});

test('what a comment says, an HTML comment says, or a type-only import pulls in is not a need', async () => {
  const dir = tree(
    GUARD_IMPORT +
      `export const routes = [
  { path: 'plain', loadComponent: () => import('./features/plain').then((m) => m.Plain) },
];\n`,
    {
      'src/app/features/plain.ts': [
        `import type { Shape } from './kitchen-shape';`,
        `import { Component } from '@angular/core';`,
        `// the kitchen shows 'kitchen.title' here`,
        `/** and 'orders.title' there */`,
        `@Component({ templateUrl: './plain.html' })`,
        `export class Plain { shape?: Shape; label = 'shell.title'; }`,
      ].join('\n'),
      'src/app/features/plain.html': `<!-- {{ 'orders.title' | t }} -->\n<p>{{ 'shell.title' | t }}</p>\n`,
      'src/app/features/kitchen-shape.ts': `export interface Shape { title: string }\nexport const never = 'kitchen.title';\n`,
    },
  );

  const analysis = await analyseRoutes(dir);

  assert.deepEqual(problemsOf(analysis), []);
  assert.deepEqual(analysis.routes[0].needs, ['core']);
});

test('keys in the template and in a dialog imported lazily count towards the screen', async () => {
  const dir = tree(
    `export const routes = [
  { path: 'a', loadComponent: () => import('./features/a').then((m) => m.A) },
];\n`,
    {
      'src/app/features/a.ts': `@Component({ templateUrl: './a.html' })\nexport class A { open() { return import('./dialog'); } }\n`,
      'src/app/features/a.html': `<p>{{ 'orders.title' | t }}</p>\n`,
      'src/app/features/dialog.ts': `export const d = 'kitchen.title';\n`,
    },
  );

  const analysis = await analyseRoutes(dir);

  assert.deepEqual(analysis.routes[0].needs, ['kitchen', 'orders']);
  assert.equal(problemsOf(analysis).length, 2);
});

test('lazy imports behind a spread function call are routes too', async () => {
  const dir = tree(
    `export const routes = [
  { path: '', loadComponent: () => import('./features/shell').then((m) => m.Shell), children: [ ...placeholders() ] },
];
function placeholders() {
  return [{ path: 'x', loadComponent: () => import('./features/placeholder').then((m) => m.P) }];
}\n`,
    {
      'src/app/features/shell.ts': `export const s = 'shell.title';\n`,
      'src/app/features/placeholder.ts': `export const p = 'kitchen.title';\n`,
    },
  );

  const problems = problemsOf(await analyseRoutes(dir));

  assert.equal(problems.length, 1);
  assert.match(problems[0], /route '…placeholders\(\)'/);
});

test('code that runs before any route may only need core', async () => {
  const dir = tree(
    `import { toast } from './toast';\nexport const routes = [];\nexport { toast };\n`,
    { 'src/app/toast.ts': `export const toast = 'orders.title';\n` },
  );

  const problems = problemsOf(await analyseRoutes(dir));

  assert.equal(problems.length, 1);
  assert.match(problems[0], /code that runs before any route needs the 'orders' messages/);
  assert.match(problems[0], /src\/main\.ts -> src\/app\/app\.routes\.ts -> src\/app\/toast\.ts/);
});

test('declaring an area that does not exist is reported', async () => {
  const dir = tree(
    GUARD_IMPORT +
      `export const routes = [\n  { path: 'a', canActivate: [messagesGuard('ordres')], loadComponent: () => import('./features/a').then((m) => m.A) },\n];\n`,
    { 'src/app/features/a.ts': `export const a = 1;\n` },
  );

  const problems = problemsOf(await analyseRoutes(dir));

  assert.deepEqual(problems, ["route 'a' declares the unknown message area 'ordres'"]);
});

test('the real route table declares every area its screens need', async () => {
  const appDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

  const analysis = await analyseRoutes(appDir);

  assert.ok(analysis.routes.length > 50, 'the route table was read');
  assert.deepEqual(
    problemsOf(analysis),
    [],
    'add messagesGuard(...) to the route, or move the shared words into core (message-areas.ts)',
  );
  assert.deepEqual(analysis.eager.needs, ['core'], 'nothing but core may be needed before a route opens');
});
