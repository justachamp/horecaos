#!/usr/bin/env node
/**
 * Lists (and with --write removes) message keys no template or TypeScript file
 * references.
 *
 *   node tools/i18n/dead-keys.mjs            list dead keys, grouped by prefix
 *   node tools/i18n/dead-keys.mjs --write    delete them from all three catalogues
 *   node tools/i18n/dead-keys.mjs --check    exit 1 when any key is dead (for a gate)
 *
 * Another app with the same three-catalogue layout (`src/app/core/i18n/messages.{en,ru,uz-latn}.ts`,
 * each exporting one object literal) can be scanned with `--app-dir <path>`; add
 * `--variables '<regex>'` when its constants are not named `messages*`
 * (control-plane: `--variables '^(en|ru|uzLatn)$'`).
 *
 * A key is LIVE when any non-catalogue, non-spec source file under src/
 *
 *   1. contains it as a whole string literal ('a.b', "a.b", `a.b`, or an HTML
 *      attribute value) -- the form every `| t` pipe, `i18n.t()` call and
 *      `MessageKey`-typed input takes; or
 *   2. matches a template literal whose static parts and `${}` holes describe it
 *      (`orders.status.${status}` keeps every `orders.status.*` key), or a literal
 *      ending in `.` that is concatenated onto ('orders.action.' + code).
 *
 * The dynamic rules are deliberately generous: a key kept alive by a pattern that
 * is broader than the code that builds it costs a few bytes, while deleting a key
 * a screen builds at runtime costs a raw key on screen. A key that a spec names as a
 * whole literal but no production file does is reported as SPEC-ONLY and is not
 * removed -- somebody asserts on it, so somebody thinks it is shown. (A spec's own
 * pattern or prefix does not count: specs loop over catalogues and would keep
 * everything alive.)
 *
 * The catalogues are edited through the TypeScript parser, not with regular
 * expressions, so multi-line values and quoting survive. Each dead entry is cut
 * with its own line(s) and trailing comma; comments are left where they are.
 */
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const ts = require('typescript');

const CATALOGUE_NAMES = ['messages.en.ts', 'messages.ru.ts', 'messages.uz-latn.ts'];

const LITERAL = /(['"`])([A-Za-z0-9_][A-Za-z0-9_.\-]*)\1/g;
const TEMPLATE = /`([^`]*\$\{[^`]*)`/g;
const PREFIX_LITERAL = /(['"`])([A-Za-z0-9_][A-Za-z0-9_.\-]*\.)\1/g;

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
    } else if (/\.(ts|html)$/.test(entry.name)) {
      out.push(full);
    }
  }
  return out;
}

/** `{ ... } as const`, `{ ... } satisfies T` and parentheses all still mean the object literal. */
function unwrap(expression) {
  let current = expression;
  while (
    ts.isAsExpression(current) ||
    ts.isSatisfiesExpression(current) ||
    ts.isParenthesizedExpression(current)
  ) {
    current = current.expression;
  }
  return current;
}

/** Every property of a catalogue's object literal (a constant matching `variables`): its key and source range. */
export function readEntries(file, variables = /^messages/) {
  const text = fs.readFileSync(file, 'utf8');
  const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true);
  const entries = [];
  const visit = (node) => {
    const literal =
      ts.isVariableDeclaration(node) &&
      ts.isIdentifier(node.name) &&
      variables.test(node.name.text) &&
      node.initializer
        ? unwrap(node.initializer)
        : null;
    if (literal && ts.isObjectLiteralExpression(literal)) {
      for (const property of literal.properties) {
        if (!ts.isPropertyAssignment(property)) {
          throw new Error(`${file}: a catalogue entry that is not 'key: value'`);
        }
        const name = property.name;
        if (!ts.isStringLiteral(name) && !ts.isIdentifier(name)) {
          throw new Error(`${file}: a catalogue entry with a computed name`);
        }
        entries.push({ key: name.text, start: property.getStart(source), end: property.getEnd() });
      }
      return;
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  if (entries.length === 0) {
    throw new Error(`${file}: no catalogue object literal found`);
  }
  return { text, entries };
}

function scan(files) {
  const exact = new Set();
  const patterns = [];
  const prefixes = new Set();
  for (const file of files) {
    const text = fs.readFileSync(file, 'utf8');
    for (const match of text.matchAll(LITERAL)) {
      exact.add(match[2]);
    }
    for (const match of text.matchAll(PREFIX_LITERAL)) {
      prefixes.add(match[2]);
    }
    for (const match of text.matchAll(TEMPLATE)) {
      const body = match[1];
      if (!/^[A-Za-z0-9_]/.test(body)) {
        continue;
      }
      const source = body
        .split(/\$\{[^}]*\}/)
        .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
        .join('[A-Za-z0-9_.\\-]+');
      patterns.push(new RegExp(`^${source}$`));
    }
  }
  return { exact, patterns, prefixes };
}

function isLive(key, refs) {
  if (refs.exact.has(key)) {
    return true;
  }
  for (const prefix of refs.prefixes) {
    if (key.startsWith(prefix)) {
      return true;
    }
  }
  return refs.patterns.some((pattern) => pattern.test(key));
}

/** @returns {{ keys: string[], dead: string[], specOnly: string[], catalogues: string[] }} */
export function findDeadKeys(appDir, variables = /^messages/) {
  const srcDir = path.join(appDir, 'src');
  const catalogues = CATALOGUE_NAMES.map((name) => path.join(srcDir, 'app/core/i18n', name));
  const sourceFiles = walk(srcDir).filter((file) => !catalogues.includes(file));
  const isSpec = (file) =>
    /\.spec\.ts$/.test(file) || file.includes(`${path.sep}testing${path.sep}`);
  const live = scan(sourceFiles.filter((file) => !isSpec(file)));
  const inSpecs = scan(sourceFiles.filter(isSpec));

  const keys = readEntries(catalogues[0], variables).entries.map((entry) => entry.key);
  const dead = [];
  const specOnly = [];
  for (const key of keys) {
    if (isLive(key, live)) {
      continue;
    }
    (inSpecs.exact.has(key) ? specOnly : dead).push(key);
  }
  return { keys, dead, specOnly, catalogues, variables };
}

/** Deletes `dead` from every catalogue, whole lines at a time. Returns how many entries each lost. */
export function removeKeys(catalogues, dead, variables = /^messages/) {
  const doomed = new Set(dead);
  return catalogues.map((file) => {
    const { text, entries } = readEntries(file, variables);
    const cuts = entries
      .filter((entry) => doomed.has(entry.key))
      .map((entry) => {
        // Widen to whole lines: back to the start of the entry's first line, forward past the
        // comma and the newline. An entry that shares a line with another one is refused.
        const lineStart = text.lastIndexOf('\n', entry.start - 1) + 1;
        let lineEnd = entry.end;
        if (text[lineEnd] === ',') {
          lineEnd += 1;
        }
        const newline = text.indexOf('\n', lineEnd);
        const tail = text.slice(lineEnd, newline === -1 ? text.length : newline);
        if (text.slice(lineStart, entry.start).trim() !== '' || tail.trim() !== '') {
          throw new Error(`${file}: '${entry.key}' shares a line with other text; remove it by hand`);
        }
        return [lineStart, newline === -1 ? text.length : newline + 1];
      })
      .sort((a, b) => a[0] - b[0]);

    // Neighbouring dead entries are one cut; a cut with a blank line above and another below
    // would leave two side by side, so it takes one of them too.
    const ranges = [];
    for (const [from, to] of cuts) {
      const last = ranges[ranges.length - 1];
      if (last && last[1] === from) {
        last[1] = to;
      } else {
        ranges.push([from, to]);
      }
    }
    for (const range of ranges) {
      if (text.slice(0, range[0]).endsWith('\n\n') && text[range[1]] === '\n') {
        range[1] += 1;
      }
    }
    ranges.reverse();
    let edited = text;
    for (const [from, to] of ranges) {
      edited = edited.slice(0, from) + edited.slice(to);
    }
    fs.writeFileSync(file, edited);
    return { file, removed: cuts.length };
  });
}

function main() {
  const argv = process.argv.slice(2);
  const option = (name) => (argv.includes(name) ? argv[argv.indexOf(name) + 1] : undefined);
  const appDir = path.resolve(
    option('--app-dir') ?? path.join(path.dirname(fileURLToPath(import.meta.url)), '../..'),
  );
  const args = new Set(argv);
  const variables = option('--variables') ? new RegExp(option('--variables')) : undefined;
  const { keys, dead, specOnly, catalogues } = findDeadKeys(appDir, variables);

  if (!args.has('--quiet')) {
    const byPrefix = new Map();
    for (const key of dead) {
      const group = key.split('.').slice(0, 2).join('.');
      byPrefix.set(group, [...(byPrefix.get(group) ?? []), key]);
    }
    for (const [group, groupKeys] of [...byPrefix.entries()].sort()) {
      console.log(`${group}  (${groupKeys.length})`);
      for (const key of groupKeys) {
        console.log(`    ${key}`);
      }
    }
    console.log(
      `\n${keys.length} keys, ${dead.length} unreferenced, ${specOnly.length} named only by specs`,
    );
    for (const key of specOnly) {
      console.log(`  SPEC-ONLY  ${key}`);
    }
  }

  if (args.has('--write') && dead.length > 0) {
    for (const { file, removed } of removeKeys(catalogues, dead, variables)) {
      console.log(`${path.relative(appDir, file)}: removed ${removed}`);
    }
  }

  if (args.has('--check') && dead.length > 0) {
    process.exit(1);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
