/**
 * Keeps the catalogues out of the initial bundle. Run: `npm run i18n:dead:test`.
 *
 * The whole point of the split is that only the default locale's `core` area is eager, and the way that
 * quietly stops being true is one `import { messagesRu } from ...` in production code, which drags every
 * area of that locale into the main chunk and puts the budget back where it was. The build's
 * `initial` budget would catch the size, late and as a number; this names the file.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const ts = require('typescript');

const APP_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const SRC = path.join(APP_DIR, 'src');
const I18N = path.join(SRC, 'app/core/i18n');
const LOADER = path.join(I18N, 'i18n.ts');

function sources(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      sources(full, out);
    } else if (/\.ts$/.test(entry.name) && !/\.spec\.ts$/.test(entry.name) && !full.includes(`${path.sep}testing${path.sep}`)) {
      out.push(full);
    }
  }
  return out;
}

/** The static imports of a file that can carry a value, with the names they bind. */
function staticImports(file) {
  const source = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true);
  const found = [];
  source.forEachChild((node) => {
    if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier) && !node.importClause?.isTypeOnly) {
      const named = node.importClause?.namedBindings;
      const names = named && ts.isNamedImports(named) ? named.elements.filter((e) => !e.isTypeOnly).map((e) => (e.propertyName ?? e.name).text) : ['*'];
      found.push({ specifier: node.moduleSpecifier.text, names });
    }
  });
  return found;
}

const isCatalogueModule = (file, specifier) => {
  if (!specifier.startsWith('.')) {
    return false;
  }
  const target = path.resolve(path.dirname(file), specifier);
  return (
    /\/messages\.(?:en|ru|uz-latn)$/.test(target.split(path.sep).join('/')) ||
    target.split(path.sep).join('/').includes('/core/i18n/messages/')
  );
};

test('no production file takes a value from a catalogue module; the types are all they may take', () => {
  const offenders = [];
  // The catalogue modules import each other (the aggregates spread the areas); that is what they are.
  const isCatalogue = (file) => isCatalogueModule(file, `./${path.basename(file, '.ts')}`) || file.includes(`${path.sep}core${path.sep}i18n${path.sep}messages${path.sep}`);
  for (const file of sources(SRC).filter((file) => !isCatalogue(file))) {
    for (const { specifier, names } of staticImports(file)) {
      if (!isCatalogueModule(file, specifier)) {
        continue;
      }
      const values = names.filter((name) => name !== 'MessageKey' && name !== 'MessageCatalogue');
      if (file === LOADER && specifier === './messages/core.ru' && values.join() === 'coreRu') {
        continue;
      }
      if (values.length > 0) {
        offenders.push(`${path.relative(APP_DIR, file)} imports ${values.join(', ')} from '${specifier}'`);
      }
    }
  }
  assert.deepEqual(offenders, [], 'only core.ru may be imported as a value, and only by i18n.ts: everything else loads through I18n');
});

test('the loaders in i18n.ts import every area of every locale but ru/core lazily, and nothing else', () => {
  const text = fs.readFileSync(LOADER, 'utf8');
  const source = ts.createSourceFile(LOADER, text, ts.ScriptTarget.Latest, true);
  const lazy = new Set();
  const visit = (node) => {
    if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword && ts.isStringLiteral(node.arguments[0])) {
      lazy.add(node.arguments[0].text);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);

  const modules = fs
    .readdirSync(path.join(I18N, 'messages'))
    .filter((name) => name.endsWith('.ts'))
    .map((name) => `./messages/${name.slice(0, -3)}`)
    .filter((specifier) => specifier !== './messages/core.ru')
    .sort();
  assert.deepEqual([...lazy].sort(), modules);
});
