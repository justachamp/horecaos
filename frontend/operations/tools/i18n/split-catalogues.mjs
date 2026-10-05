#!/usr/bin/env node
/**
 * Lays the message catalogues out as one module per area and locale.
 *
 *   node tools/i18n/split-catalogues.mjs            write the layout
 *   node tools/i18n/split-catalogues.mjs --check    exit 1 when writing it would change anything
 *
 * Input, whichever the tree has:
 *
 *   - `src/app/core/i18n/messages.{en,ru,uz-latn}.ts` holding `'key': 'value'` entries (the single-file
 *     layout every branch cut before batch 18 still edits), or
 *   - the area modules `src/app/core/i18n/messages/<area>.{en,ru,uz-latn}.ts` (the layout this tool writes).
 *
 * Output:
 *
 *   src/app/core/i18n/messages/<area>.{en,ru,uz-latn}.ts   the entries of one area, in their original
 *                                                          order, each with the comment lines above it
 *   src/app/core/i18n/messages.{en,ru,uz-latn}.ts          thin aggregates that spread the area modules
 *                                                          back together, so `MessageKey`, `messagesEn` and
 *                                                          every spec that reads a whole catalogue keep
 *                                                          their import paths
 *
 * Which area a key goes to is `areaOfKey` in `message-areas.ts`; a key it cannot place stops the run
 * naming the key, and the three locales must define the same keys.
 *
 * Two uses beyond the first split:
 *
 *   - After a merge. A branch that added keys to the single-file layout conflicts with the aggregates;
 *     keep that branch's version of the three files, run this tool, and every area module is rewritten
 *     from them (the single-file entries win while the files hold any).
 *   - Moving a key between areas. Change `message-areas.ts`, run this tool, and the entries move with
 *     their comments.
 */
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { LOCALE_SUFFIXES, constantName, loadMessageAreas } from './message-areas.mjs';

const require = createRequire(import.meta.url);
const ts = require('typescript');
const prettier = require('prettier');

const I18N_DIR = 'src/app/core/i18n';

/** English, Russian and Uzbek: how each locale is named in prose and in the aggregate's declaration. */
const LOCALES = {
  en: { label: 'English', constant: 'messagesEn' },
  ru: { label: 'Russian', constant: 'messagesRu' },
  'uz-latn': { label: 'Uzbek (Latin script)', constant: 'messagesUzLatn' },
};

/**
 * The entries of one single-file catalogue: key, the comment lines above it, whether a blank line
 * preceded it, and its source text. Returns null when the file holds spreads only (an aggregate).
 */
function readEntriesFrom(file) {
  const text = fs.readFileSync(file, 'utf8');
  const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true);
  let literal = null;
  const find = (node) => {
    if (
      ts.isVariableDeclaration(node) &&
      ts.isIdentifier(node.name) &&
      /^\w+(?:En|Ru|UzLatn)$/.test(node.name.text) &&
      node.initializer
    ) {
      let current = node.initializer;
      while (ts.isAsExpression(current) || ts.isParenthesizedExpression(current)) {
        current = current.expression;
      }
      if (ts.isObjectLiteralExpression(current)) {
        literal = current;
        return;
      }
    }
    ts.forEachChild(node, find);
  };
  find(source);
  if (!literal) {
    throw new Error(`${file}: no catalogue object literal found`);
  }
  if (literal.properties.length > 0 && literal.properties.every((property) => ts.isSpreadAssignment(property))) {
    return null;
  }
  const entries = [];
  for (const property of literal.properties) {
    if (!ts.isPropertyAssignment(property)) {
      throw new Error(`${file}: a catalogue entry that is not 'key: value'`);
    }
    if (!ts.isStringLiteral(property.name) && !ts.isIdentifier(property.name)) {
      throw new Error(`${file}: a catalogue entry with a computed name`);
    }
    const trivia = text.slice(property.getFullStart(), property.getStart(source));
    const comments = (ts.getLeadingCommentRanges(text, property.getFullStart()) ?? []).map((range) =>
      text.slice(range.pos, range.end),
    );
    const commaAt = text[property.getEnd()] === ',' ? property.getEnd() + 1 : property.getEnd();
    const trailing = (ts.getTrailingCommentRanges(text, commaAt) ?? []).map((range) =>
      text.slice(range.pos, range.end),
    );
    entries.push({
      key: property.name.text,
      comments,
      blankBefore: /\n[ \t]*\n/.test(trivia),
      text: text.slice(property.getStart(source), property.getEnd()),
      trailing,
    });
  }
  if (entries.length === 0) {
    return entries;
  }
  // A comment after the last entry has no entry to travel with.
  const lastEnd = literal.properties[literal.properties.length - 1].getEnd();
  const tail = text.slice(lastEnd, literal.getEnd());
  if (/\/\/|\/\*/.test(tail)) {
    throw new Error(`${file}: a comment after the last entry; move it above an entry`);
  }
  return entries;
}

/** Wraps a sentence into ` * ` comment lines of at most 100 columns. */
function wrapComment(sentence) {
  const lines = [];
  let line = '';
  for (const word of sentence.split(' ')) {
    if (line && line.length + word.length + 1 > 96) {
      lines.push(` * ${line}`);
      line = word;
    } else {
      line = line ? `${line} ${word}` : word;
    }
  }
  lines.push(` * ${line}`);
  return lines;
}

/**
 * A locale's entries: from its single-file catalogue when that still holds entries (a tree that has not
 * been split, or a merge that brought entries back into the aggregate), else from its area modules, in
 * the table's area order. Reading the modules makes the tool the way to move a key between areas: change
 * the table in `message-areas.ts`, run this, and the entry moves with its comments.
 */
function readLocale(i18nDir, suffix, areas) {
  const single = readEntriesFrom(path.join(i18nDir, `messages.${suffix}.ts`));
  if (single !== null) {
    return single;
  }
  const messagesDir = path.join(i18nDir, 'messages');
  const present = fs.existsSync(messagesDir)
    ? fs.readdirSync(messagesDir).filter((name) => name.endsWith(`.${suffix}.ts`))
    : [];
  const order = (name) => {
    const index = areas.indexOf(name.slice(0, -`.${suffix}.ts`.length));
    return index < 0 ? areas.length : index;
  };
  return present
    .sort((a, b) => order(a) - order(b) || a.localeCompare(b))
    .flatMap((name) => readEntriesFrom(path.join(messagesDir, name)) ?? []);
}

function areaModuleSource(area, suffix, entries, namespaces) {
  const { label } = LOCALES[suffix];
  const constant = constantName(area, suffix);
  const body = entries
    .map((entry, index) => {
      const lines = [];
      if (index > 0 && (entry.blankBefore || entry.comments.length > 0)) {
        lines.push('');
      }
      for (const comment of entry.comments) {
        lines.push(`  ${comment}`);
      }
      lines.push(`  ${entry.text},${entry.trailing.length ? ` ${entry.trailing.join(' ')}` : ''}`);
      return lines.join('\n');
    })
    .join('\n');
  const scope = namespaces.length
    ? `namespaces ${namespaces.map((name) => `\`${name}\``).join(', ')}`
    : 'no namespace yet';
  const intro = wrapComment(`${label} messages of the \`${area}\` area (${scope}).`);
  const header =
    suffix === 'en'
      ? [
          '/**',
          ...intro,
          ' *',
          ` * This file defines the key set of its area: \`${constantName(area, 'ru')}\` and \`${constantName(area, 'uz-latn')}\``,
          ' * are typed against it, so a key missing from either is a compile error. Which area a key belongs to is',
          ' * decided by its prefix, in `../message-areas.ts`; `../messages.en.ts` puts the areas back together.',
          ' */',
        ]
      : [
          '/**',
          ...intro,
          ' *',
          ` * Typed against \`${constantName(area, 'en')}\`: a key missing here, or one that does not exist there, is a`,
          ' * compile error. `../messages.en.ts` documents the layout.',
          ' */',
        ];
  const head =
    suffix === 'en'
      ? ''
      : [
          `import type { AreaMessages } from '../message-areas';`,
          `import type { ${constantName(area, 'en')} } from './${area}.en';`,
          '',
          '',
        ].join('\n');
  const declaration =
    suffix === 'en'
      ? `export const ${constant} = {`
      : `export const ${constant}: AreaMessages<typeof ${constantName(area, 'en')}> = {`;
  const close = suffix === 'en' ? '} as const;' : '};';
  const bodyBlock = body ? `${body}\n` : '';
  return `${head}${header.join('\n')}\n${declaration}\n${bodyBlock}${close}\n`;
}

function aggregateSource(areas, suffix) {
  const { constant } = LOCALES[suffix];
  const imports = areas
    .map((area) => `import { ${constantName(area, suffix)} } from './messages/${area}.${suffix}';`)
    .join('\n');
  const spreads = areas.map((area) => `  ...${constantName(area, suffix)},`).join('\n');
  if (suffix === 'en') {
    return `${imports}

/**
 * The canonical message catalogue.
 *
 * **The key set is defined by the area modules** (\`messages/<area>.en.ts\`); this file puts them back
 * together so that \`MessageKey\`, \`MessageCatalogue\` and a whole-catalogue \`messagesEn\` (what the specs
 * read) have one import path. \`MessageKey\` is derived from it, and the other two locales' area
 * modules are typed against the English ones, so a key added here and not translated fails \`tsc\`,
 * which means it fails \`ng build\` and \`ng test\` -- not at runtime, in front of an operator, as the
 * English string leaking through a Russian screen.
 *
 * That is the whole mechanism, and it is deliberately not a library. A runtime translation loader
 * cannot fail a build, because at build time it has nothing to check; every such loader ships a
 * "missing key" fallback for exactly this reason, and a fallback is the failure mode this project
 * is trying to avoid.
 *
 * **This module is not part of the application's initial bundle**, and must not become so: nothing in
 * \`src/\` outside the specs imports a value from it. \`core/i18n/i18n.ts\` loads the area modules
 * one by one -- \`core\` up front, every other area when a route asks for it -- see \`message-areas.ts\`.
 *
 * Rules for keys:
 *
 *  - Dot-separated and namespaced by where they are used: \`shell.*\`, \`error.*\`. The first segment
 *    decides which area the key lives in (\`message-areas.ts\`), so a new namespace needs a line there.
 *  - Named for meaning, not for text. \`orders.late\` survives a copy change; \`orders.six_late\` does not.
 *  - \`{placeholder}\` for interpolation. See \`interpolate\` in i18n.ts.
 *
 * Content names -- dishes, brands, branches, people -- are never keys. They are tenant data in whatever
 * language the tenant wrote them, and translating them would be inventing a name the restaurant does
 * not use.
 */
export const ${constant} = {
${spreads}
} as const;

/** Every key the application may ask for. Derived, never hand-maintained. */
export type MessageKey = keyof typeof ${constant};

/** The shape every other locale must satisfy in full. */
export type MessageCatalogue = Record<MessageKey, string>;
`;
  }
  const doc =
    suffix === 'ru'
      ? `/**
 * Russian, every area together. Typed as the complete catalogue, so a key added to the English
 * catalogue and forgotten here is a compile error naming the missing key.
 *
 * Status vocabulary follows \`docs/operations-spec/orders.md\` §1.1, which fixes the operator-facing
 * word for every canonical status. Where that table and a dictionary disagree, the table wins: it is
 * the word the staff already use.
 *
 * Like \`messages.en.ts\`, this module is for the specs and the types; the application loads the area
 * modules (\`messages/<area>.ru.ts\`) itself.
 */`
      : `/**
 * Uzbek in the Latin script, every area together.
 *
 * The script subtag is carried in the locale tag and in this filename because uz-Latn and uz-Cyrl are
 * not the same locale, and a bare \`uz\` is ambiguous -- which is exactly the ambiguity the legacy
 * application's \`LanType\` enum shipped with (ADR 0035). A Cyrillic-script Uzbek catalogue would be a
 * third set of modules, not a runtime transliteration of this one.
 *
 * The apostrophe in \`oʻ\` and \`gʻ\` is U+02BB MODIFIER LETTER TURNED COMMA, the correct character for
 * the Uzbek Latin alphabet. A typewriter apostrophe (') is a different character that breaks search
 * and sorting.
 *
 * Like \`messages.en.ts\`, this module is for the specs and the types; the application loads the area
 * modules (\`messages/<area>.uz-latn.ts\`) itself.
 */`;
  return `import type { MessageCatalogue } from './messages.en';
${imports}

${doc}
export const ${constant}: MessageCatalogue = {
${spreads}
};
`;
}

async function format(file, source) {
  const options = (await prettier.resolveConfig(file)) ?? {};
  return prettier.format(source, { ...options, filepath: file });
}

export async function splitCatalogues(appDir, { write = true } = {}) {
  const i18nDir = path.join(appDir, I18N_DIR);
  const { MESSAGE_AREAS, areaOfKey, namespacesOfArea, prefixesOfArea } = await loadMessageAreas(appDir);
  const read = Object.fromEntries(
    LOCALE_SUFFIXES.map((suffix) => [suffix, readLocale(i18nDir, suffix, MESSAGE_AREAS)]),
  );

  const keysOf = (suffix) => read[suffix].map((entry) => entry.key);
  const english = new Set(keysOf('en'));
  for (const suffix of ['ru', 'uz-latn']) {
    const other = new Set(keysOf(suffix));
    const missing = [...english].filter((key) => !other.has(key));
    const extra = [...other].filter((key) => !english.has(key));
    if (missing.length || extra.length) {
      throw new Error(
        `${suffix} and en define different keys: missing ${JSON.stringify(missing.slice(0, 5))} (${missing.length}), ` +
          `extra ${JSON.stringify(extra.slice(0, 5))} (${extra.length})`,
      );
    }
  }
  const unplaced = [...english].filter((key) => areaOfKey(key) === undefined);
  if (unplaced.length) {
    const namespaces = [...new Set(unplaced.map((key) => key.split('.')[0]))].sort();
    throw new Error(
      `no area for ${unplaced.length} key(s), e.g. '${unplaced[0]}': add the namespace(s) ${namespaces.join(', ')} ` +
        `to AREA_BY_NAMESPACE in ${path.join(I18N_DIR, 'message-areas.ts')}`,
    );
  }

  const namespacesOf = (area) => [...namespacesOfArea(area), ...prefixesOfArea(area)];

  const outputs = new Map();
  for (const suffix of LOCALE_SUFFIXES) {
    for (const area of MESSAGE_AREAS) {
      const entries = read[suffix].filter((entry) => areaOfKey(entry.key) === area);
      const file = path.join(i18nDir, 'messages', `${area}.${suffix}.ts`);
      outputs.set(file, areaModuleSource(area, suffix, entries, namespacesOf(area)));
    }
    outputs.set(path.join(i18nDir, `messages.${suffix}.ts`), aggregateSource([...MESSAGE_AREAS], suffix));
  }

  const changed = [];
  for (const [file, source] of outputs) {
    const formatted = await format(file, source);
    const current = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
    if (current !== formatted) {
      changed.push(file);
      if (write) {
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, formatted);
      }
    }
  }
  // A stale area module (an area removed from the table) would keep its keys alive in the tooling.
  const wanted = new Set(outputs.keys());
  const messagesDir = path.join(i18nDir, 'messages');
  if (fs.existsSync(messagesDir)) {
    for (const name of fs.readdirSync(messagesDir)) {
      const file = path.join(messagesDir, name);
      if (/\.(?:en|ru|uz-latn)\.ts$/.test(name) && !wanted.has(file)) {
        changed.push(file);
        if (write) {
          fs.rmSync(file);
        }
      }
    }
  }
  return { split: true, files: changed };
}

async function main() {
  const argv = process.argv.slice(2);
  const option = (name) => (argv.includes(name) ? argv[argv.indexOf(name) + 1] : undefined);
  const appDir = path.resolve(
    option('--app-dir') ?? path.join(path.dirname(fileURLToPath(import.meta.url)), '../..'),
  );
  const check = argv.includes('--check');
  const { split, files } = await splitCatalogues(appDir, { write: !check });
  if (!split || files.length === 0) {
    console.log('nothing to split');
    return;
  }
  for (const file of files) {
    console.log(`${check ? 'would write' : 'wrote'} ${path.relative(appDir, file)}`);
  }
  if (check && files.length > 0) {
    process.exit(1);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
