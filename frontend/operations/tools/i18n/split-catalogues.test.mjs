/**
 * Tests for split-catalogues.mjs over throwaway app trees. Run: `npm run i18n:dead:test`.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';

import { readEntries } from './dead-keys.mjs';
import { splitCatalogues } from './split-catalogues.mjs';
import { AREA_TABLE, app, read } from './test-app.mjs';

const I18N = 'src/app/core/i18n';

function catalogue(constant, annotation, entries) {
  const body = entries.join('\n');
  return `${annotation ? `import type { MessageCatalogue } from './messages.en';\n\n` : ''}export const ${constant}${annotation} = {\n${body}\n}${annotation ? ';' : ' as const;'}\n`;
}

/** The single-file layout every branch cut before the split still edits. */
function singleFileApp(extra = {}) {
  const en = [
    `  'shell.title': 'Shell',`,
    ``,
    `  // The orders queue, wave 7.`,
    `  'orders.title': 'Orders',`,
    `  'orders.status.NEW': 'New',`,
    `  'kitchen.title': 'Kitchen',`,
    `  'orders.detail.close': 'Close',`,
  ];
  const ru = [
    `  'shell.title': 'Оболочка',`,
    ``,
    `  // Очередь заказов, волна 7.`,
    `  'orders.title': 'Заказы',`,
    `  'orders.status.NEW': 'Новый',`,
    `  'kitchen.title': 'Кухня',`,
    `  'orders.detail.close':`,
    `    'Закрыть, и это значение настолько длинное, что prettier переносит его на отдельную строку',`,
  ];
  const uz = [
    `  'shell.title': 'Qobiq',`,
    ``,
    `  // Buyurtmalar navbati, 7-to'lqin.`,
    `  'orders.title': 'Buyurtmalar',`,
    `  'orders.status.NEW': 'Yangi',`,
    `  'kitchen.title': 'Oshxona',`,
    `  'orders.detail.close': 'Yopish',`,
  ];
  return app({
    // The formatting the real app has, so the assertions below read the output the repository would commit.
    '.prettierrc': "{ \"singleQuote\": true, \"printWidth\": 100 }\n",
    [`${I18N}/message-areas.ts`]: AREA_TABLE,
    [`${I18N}/messages.en.ts`]: catalogue('messagesEn', '', en),
    [`${I18N}/messages.ru.ts`]: catalogue('messagesRu', ': MessageCatalogue', ru),
    [`${I18N}/messages.uz-latn.ts`]: catalogue('messagesUzLatn', ': MessageCatalogue', uz),
    ...extra,
  });
}

function keysOf(dir, relative) {
  return readEntries(path.join(dir, relative)).entries.map((entry) => entry.key);
}

test('puts every key in the module of its area, in each locale, in the original order', async () => {
  const dir = singleFileApp();

  const { files } = await splitCatalogues(dir);

  assert.equal(files.length, 3 * 3 + 3, 'nine area modules and three aggregates');
  for (const locale of ['en', 'ru', 'uz-latn']) {
    assert.deepEqual(keysOf(dir, `${I18N}/messages/core.${locale}.ts`), ['shell.title', 'orders.status.NEW']);
    assert.deepEqual(keysOf(dir, `${I18N}/messages/orders.${locale}.ts`), ['orders.title', 'orders.detail.close']);
    assert.deepEqual(keysOf(dir, `${I18N}/messages/kitchen.${locale}.ts`), ['kitchen.title']);
  }
});

test('writes the aggregates that keep MessageKey, messagesEn and the other whole catalogues importable', async () => {
  const dir = singleFileApp();

  await splitCatalogues(dir);

  const en = read(dir, `${I18N}/messages.en.ts`);
  assert.match(en, /import \{ coreEn \} from '\.\/messages\/core\.en';/);
  assert.match(en, /export const messagesEn = \{\n  \.\.\.coreEn,\n  \.\.\.ordersEn,\n  \.\.\.kitchenEn,\n\} as const;/);
  assert.match(en, /export type MessageKey = keyof typeof messagesEn;/);
  assert.match(en, /export type MessageCatalogue = Record<MessageKey, string>;/);
  const ru = read(dir, `${I18N}/messages.ru.ts`);
  assert.match(ru, /export const messagesRu: MessageCatalogue = \{\n  \.\.\.coreRu,/);
  assert.match(read(dir, `${I18N}/messages.uz-latn.ts`), /export const messagesUzLatn: MessageCatalogue = \{/);
});

test('types the translated modules against the English ones and keeps comments with their entries', async () => {
  const dir = singleFileApp();

  await splitCatalogues(dir);

  const ru = read(dir, `${I18N}/messages/orders.ru.ts`);
  assert.match(ru, /import type \{ AreaMessages \} from '\.\.\/message-areas';/);
  assert.match(ru, /import type \{ ordersEn \} from '\.\/orders\.en';/);
  assert.match(ru, /export const ordersRu: AreaMessages<typeof ordersEn> = \{/);
  assert.match(ru, /\n  \/\/ Очередь заказов, волна 7\.\n  'orders\.title': 'Заказы',\n/);
  assert.match(read(dir, `${I18N}/messages/orders.en.ts`), /\n  \/\/ The orders queue, wave 7\.\n  'orders\.title'/);
  assert.match(read(dir, `${I18N}/messages/orders.en.ts`), /^export const ordersEn = \{|\nexport const ordersEn = \{/);
  // A value prettier wrapped onto its own line moves as one entry.
  assert.match(ru, /'orders\.detail\.close':\n    'Закрыть, и это значение/);
});

test('running it again changes nothing, and --check says so', async () => {
  const dir = singleFileApp();
  await splitCatalogues(dir);

  const again = await splitCatalogues(dir, { write: false });

  assert.deepEqual(again.files, []);
});

test('moving a namespace in the table moves its entries, comments and all', async () => {
  const dir = singleFileApp();
  await splitCatalogues(dir);
  const table = path.join(dir, `${I18N}/message-areas.ts`);
  fs.writeFileSync(table, fs.readFileSync(table, 'utf8').replace(`kitchen: 'kitchen',`, `kitchen: 'orders',`));

  await splitCatalogues(dir);

  assert.deepEqual(keysOf(dir, `${I18N}/messages/orders.en.ts`), ['orders.title', 'orders.detail.close', 'kitchen.title']);
  assert.deepEqual(keysOf(dir, `${I18N}/messages/kitchen.en.ts`), []);
});

test('a branch that still adds keys to the single-file layout can be folded in by running it again', async () => {
  const dir = singleFileApp();
  await splitCatalogues(dir);
  // What a merge leaves when the other branch's three files win: a new key in each, in the old layout.
  for (const [locale, constant, annotation, value] of [
    ['en', 'messagesEn', '', 'Fresh'],
    ['ru', 'messagesRu', ': MessageCatalogue', 'Свежий'],
    ['uz-latn', 'messagesUzLatn', ': MessageCatalogue', 'Yangi'],
  ]) {
    fs.writeFileSync(
      path.join(dir, `${I18N}/messages.${locale}.ts`),
      catalogue(constant, annotation, [`  'kitchen.fresh': '${value}',`, `  'orders.title': 'T',`, `  'shell.title': 'S',`, `  'orders.status.NEW': 'N',`, `  'kitchen.title': 'K',`, `  'orders.detail.close': 'C',`]),
    );
  }

  await splitCatalogues(dir);

  assert.deepEqual(keysOf(dir, `${I18N}/messages/kitchen.ru.ts`), ['kitchen.fresh', 'kitchen.title']);
  assert.match(read(dir, `${I18N}/messages.ru.ts`), /\.\.\.coreRu,/);
  assert.doesNotMatch(read(dir, `${I18N}/messages.ru.ts`), /'kitchen\.fresh'/);
});

test('refuses a key whose namespace nobody assigned, naming the key and the file to fix', async () => {
  const dir = singleFileApp();
  for (const locale of ['en', 'ru', 'uz-latn']) {
    const file = path.join(dir, `${I18N}/messages.${locale}.ts`);
    fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replace(`  'shell.title'`, `  'people.title': 'P',\n  'shell.title'`));
  }

  await assert.rejects(splitCatalogues(dir), /no area for 1 key\(s\), e\.g\. 'people\.title': add the namespace\(s\) people to AREA_BY_NAMESPACE in src\/app\/core\/i18n\/message-areas\.ts/);
  assert.ok(!fs.existsSync(path.join(dir, `${I18N}/messages`)), 'nothing was written');
});

test('refuses locales that define different keys', async () => {
  const dir = singleFileApp();
  const file = path.join(dir, `${I18N}/messages.ru.ts`);
  fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replace(`  'kitchen.title': 'Кухня',\n`, ''));

  await assert.rejects(splitCatalogues(dir), /ru and en define different keys: missing \["kitchen\.title"\] \(1\)/);
});
