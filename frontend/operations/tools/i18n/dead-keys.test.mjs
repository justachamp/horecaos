/**
 * Tests for dead-keys.mjs over a throwaway app tree. Run: `npm run i18n:dead:test`.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';

import { findDeadKeys, readEntries, removeKeys } from './dead-keys.mjs';

const KEYS = {
  'used.inTemplate': 'Used in a template',
  'used.inCode': 'Used in code',
  'used.inAttribute': 'Used as an attribute value',
  'spec.only': 'Only a spec names it',
  'only.specPattern': 'Only a spec pattern matches it',
  'never.used': 'Nobody uses it',
  'never.usedEither': 'A value that prettier wraps onto its own line, with a "quote" in it',
  'status.NEW': 'New',
  'status.LATE': 'Late',
  'action.cancel': 'Cancel',
  'action.retry': 'Retry',
};

/** Entries that start a new paragraph, so the dead block sits between two blank lines. */
const BLANK_BEFORE = new Set(['only.specPattern', 'status.NEW']);

function catalogue(exportLine) {
  const body = Object.entries(KEYS)
    .map(([key, value]) =>
      // Prettier puts a long value on its own line under the key; the tool must take both lines.
      (BLANK_BEFORE.has(key) ? '\n' : '') +
      (value.length > 40
        ? `  ${JSON.stringify(key)}:\n    ${JSON.stringify(value)},`
        : `  ${JSON.stringify(key)}: ${JSON.stringify(value)},`),
    )
    .join('\n');
  return `// A comment that must survive.\n${exportLine} {\n${body}\n}${exportLine.includes('MessageCatalogue') ? ';' : ' as const;'}\n`;
}

function fixture() {
  const appDir = fs.mkdtempSync(path.join(os.tmpdir(), 'dead-keys-'));
  const write = (relative, text) => {
    const file = path.join(appDir, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text);
  };
  const i18n = 'src/app/core/i18n';
  write(`${i18n}/messages.en.ts`, catalogue('export const messagesEn ='));
  write(`${i18n}/messages.ru.ts`, catalogue('export const messagesRu: MessageCatalogue ='));
  write(`${i18n}/messages.uz-latn.ts`, catalogue('export const messagesUzLatn: MessageCatalogue ='));
  write('src/app/page.html', `<p>{{ 'used.inTemplate' | t }}</p><q-alert messageKey="used.inAttribute" />`);
  write(
    'src/app/page.ts',
    [
      `export const a = this.i18n.t('used.inCode');`,
      'export const b = (s: string) => `status.${s}`;',
      `export const c = (a: string) => 'action.' + a;`,
    ].join('\n'),
  );
  write(
    'src/app/page.spec.ts',
    [`expect(t('spec.only')).toBe('x');`, 'const key = `only.${what}`;'].join('\n'),
  );
  return appDir;
}

test('finds the keys no production file references', () => {
  const { dead, specOnly, keys } = findDeadKeys(fixture());

  assert.equal(keys.length, Object.keys(KEYS).length);
  assert.deepEqual(dead.sort(), ['never.used', 'never.usedEither', 'only.specPattern']);
  assert.deepEqual(specOnly, ['spec.only']);
});

test('keeps keys built from a template literal or a concatenated prefix', () => {
  const { dead } = findDeadKeys(fixture());

  for (const key of ['status.NEW', 'status.LATE', 'action.cancel', 'action.retry']) {
    assert.ok(!dead.includes(key), `${key} is built at runtime and must stay`);
  }
});

test('removes a dead entry from every catalogue and touches nothing else', () => {
  const appDir = fixture();
  const { dead, catalogues } = findDeadKeys(appDir);

  const results = removeKeys(catalogues, dead);

  assert.deepEqual(
    results.map((result) => result.removed),
    [3, 3, 3],
  );
  for (const file of catalogues) {
    const { entries } = readEntries(file);
    assert.deepEqual(
      entries.map((entry) => entry.key),
      Object.keys(KEYS).filter((key) => !dead.includes(key)),
    );
    const text = fs.readFileSync(file, 'utf8');
    assert.ok(text.startsWith('// A comment that must survive.\n'));
    assert.ok(!text.includes('wraps onto its own line'), 'the wrapped value went with its key');
    assert.match(text, /"used\.inCode": "Used in code",\n/);
    assert.ok(!text.includes('\n\n\n'), 'removing a paragraph of entries leaves one blank line, not two');
    assert.match(text, /"spec\.only": "Only a spec names it",\n\n  "status\.NEW"/);
  }
  assert.deepEqual(findDeadKeys(appDir).dead, []);
});

test('refuses to cut an entry that shares its line with other text', () => {
  const appDir = fixture();
  const file = path.join(appDir, 'src/app/core/i18n/messages.en.ts');
  fs.writeFileSync(
    file,
    fs
      .readFileSync(file, 'utf8')
      .replace(`"never.used": "Nobody uses it",`, `"never.used": "Nobody uses it", "also.here": "x",`),
  );
  const { catalogues } = findDeadKeys(appDir);

  assert.throws(() => removeKeys(catalogues, ['never.used']), /shares a line/);
});
