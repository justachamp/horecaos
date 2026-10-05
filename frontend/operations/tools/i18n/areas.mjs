#!/usr/bin/env node
/**
 * Which message areas does each route need, and does the route table ask for them?
 *
 *   node tools/i18n/areas.mjs            a table: every route, the areas it declares, the areas its code needs
 *   node tools/i18n/areas.mjs --check    exit 1 when a route needs an area nothing on its way declares
 *
 * A message area (see `src/app/core/i18n/message-areas.ts`) is fetched when a route is about to open,
 * by `messagesGuard('orders', ...)` in the route's `canActivate`. The guard is a promise that the strings
 * are in memory before the screen draws; a screen whose strings were not declared still works --
 * `I18n.t` loads the missing area on demand -- but shows raw keys until it has arrived. This tool is
 * what keeps that from happening unnoticed.
 *
 * How it decides what a route needs. Starting from the file each `loadComponent: () => import(...)` names,
 * it follows every relative import, static and dynamic (a dialog opened later is part of the screen), and
 * collects the catalogue keys the files mention -- by the rules `dead-keys.mjs` uses: a whole string
 * literal, a `${}` template, a literal ending in `.` that something is concatenated onto, in the
 * `.ts` file and in the `templateUrl` template. The areas of those keys are what the route needs. A
 * route's declared set is every `messagesGuard(...)` argument on the way from the root to it, plus `core`,
 * which is always loaded.
 *
 * Code that runs before any route (everything reachable from `main.ts` by static imports) may only need `core`.
 *
 * The analysis errs on the side of needing more: an import of a module for one constant counts all of the
 * module's keys. That is the right side to err on, and when it overstates, the remedy is to move the
 * shared words into `core` (an `AREA_BY_PREFIX` line) or to split the module.
 */
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { catalogueFiles, readEntries, referencedKeys } from './dead-keys.mjs';
import { loadMessageAreas } from './message-areas.mjs';

const require = createRequire(import.meta.url);
const ts = require('typescript');

const GUARD_NAME = 'messagesGuard';
const ROUTES_FILE = 'src/app/app.routes.ts';

function resolveImport(from, specifier) {
  if (!specifier.startsWith('.')) {
    return null;
  }
  const base = path.resolve(path.dirname(from), specifier);
  for (const candidate of [`${base}.ts`, path.join(base, 'index.ts'), base]) {
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
      return candidate;
    }
  }
  return null;
}

/** `text` with every comment blanked out, found the way the compiler finds them: as trivia around a node. */
function withoutComments(source, text) {
  const ranges = new Map();
  const note = (list) => {
    for (const range of list ?? []) {
      ranges.set(range.pos, range.end);
    }
  };
  const visit = (node) => {
    note(ts.getLeadingCommentRanges(text, node.getFullStart()));
    note(ts.getTrailingCommentRanges(text, node.getEnd()));
    ts.forEachChild(node, visit);
  };
  visit(source);
  note(ts.getLeadingCommentRanges(text, source.endOfFileToken.getFullStart()));
  let result = '';
  let at = 0;
  for (const [pos, end] of [...ranges].sort((a, b) => a[0] - b[0])) {
    if (pos >= at) {
      result += text.slice(at, pos) + ' '.repeat(end - pos);
      at = end;
    }
  }
  return result + text.slice(at);
}

/** Everything the analysis needs to know about one source file: what it imports and which areas it names. */
function fileFacts(file, state) {
  const known = state.facts.get(file);
  if (known) {
    return known;
  }
  const text = fs.readFileSync(file, 'utf8');
  const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true);
  // What a comment says is not a string the screen shows.
  const texts = [withoutComments(source, text)];
  for (const match of text.matchAll(/templateUrl\s*:\s*['"]([^'"]+)['"]/g)) {
    const template = path.resolve(path.dirname(file), match[1]);
    if (fs.existsSync(template)) {
      texts.push(fs.readFileSync(template, 'utf8').replace(/<!--[\s\S]*?-->/g, ' '));
    }
  }
  const imports = { static: [], dynamic: [] };
  const visit = (node) => {
    if (
      (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) &&
      node.moduleSpecifier &&
      ts.isStringLiteral(node.moduleSpecifier) &&
      !(ts.isImportDeclaration(node) && node.importClause?.isTypeOnly)
    ) {
      imports.static.push(node.moduleSpecifier.text);
    } else if (
      ts.isCallExpression(node) &&
      node.expression.kind === ts.SyntaxKind.ImportKeyword &&
      node.arguments[0] &&
      ts.isStringLiteral(node.arguments[0])
    ) {
      imports.dynamic.push(node.arguments[0].text);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);

  const keys = new Set(texts.flatMap((t) => referencedKeys(t, state.keys)));
  const areas = new Map();
  for (const key of keys) {
    const area = state.areaOfKey(key);
    if (area !== undefined) {
      areas.set(area, areas.get(area) ?? key);
    }
  }
  const resolve = (specifiers) =>
    specifiers.map((s) => resolveImport(file, s)).filter((f) => f && !state.ignored.has(f));
  const facts = { static: resolve(imports.static), dynamic: resolve(imports.dynamic), areas };
  state.facts.set(file, facts);
  return facts;
}

/**
 * Walks the import graph from `entry`. Returns, for every area any reachable file names, the first such
 * file and key (`area -> { file, key }`), with the chain of files that led there for the report.
 */
function needsOf(entry, state, { followDynamic }) {
  const parent = new Map([[entry, null]]);
  const queue = [entry];
  const needs = new Map();
  while (queue.length > 0) {
    const file = queue.shift();
    const facts = fileFacts(file, state);
    for (const [area, key] of facts.areas) {
      if (!needs.has(area)) {
        const chain = [];
        for (let at = file; at; at = parent.get(at)) {
          chain.unshift(path.relative(state.appDir, at));
        }
        needs.set(area, { key, chain });
      }
    }
    for (const next of followDynamic ? [...facts.static, ...facts.dynamic] : facts.static) {
      if (!parent.has(next)) {
        parent.set(next, file);
        queue.push(next);
      }
    }
  }
  return needs;
}

/** `import('./x/y')` calls anywhere inside `node`, as specifiers. */
function dynamicImportsIn(node) {
  const found = [];
  const visit = (child) => {
    if (
      ts.isCallExpression(child) &&
      child.expression.kind === ts.SyntaxKind.ImportKeyword &&
      child.arguments[0] &&
      ts.isStringLiteral(child.arguments[0])
    ) {
      found.push(child.arguments[0].text);
    }
    ts.forEachChild(child, visit);
  };
  visit(node);
  return found;
}

/** The string arguments of every `messagesGuard('a', 'b')` call inside `node`. */
function guardAreasIn(node) {
  const areas = [];
  const visit = (child) => {
    if (
      ts.isCallExpression(child) &&
      ts.isIdentifier(child.expression) &&
      child.expression.text === GUARD_NAME
    ) {
      for (const argument of child.arguments) {
        if (ts.isStringLiteral(argument)) {
          areas.push(argument.text);
        }
      }
    }
    ts.forEachChild(child, visit);
  };
  visit(node);
  return areas;
}

function propertyNamed(objectLiteral, name) {
  return objectLiteral.properties.find(
    (property) =>
      ts.isPropertyAssignment(property) && property.name && property.name.getText() === name,
  );
}

/**
 * The route tree in `app.routes.ts`: [{ path, declared (own guard areas), imports (own loadComponent),
 * children }]. A spread of a function call (`...placeholderRoutes()`) stands for the lazy imports inside
 * that function, as an anonymous child route.
 */
function readRouteTree(routesFile) {
  const text = fs.readFileSync(routesFile, 'utf8');
  const source = ts.createSourceFile(routesFile, text, ts.ScriptTarget.Latest, true);
  const functions = new Map();
  let routesArray = null;
  source.forEachChild((node) => {
    if (ts.isFunctionDeclaration(node) && node.name) {
      functions.set(node.name.text, node);
    }
    if (ts.isVariableStatement(node)) {
      for (const declaration of node.declarationList.declarations) {
        if (declaration.name.getText() === 'routes' && declaration.initializer) {
          routesArray = declaration.initializer;
        }
      }
    }
  });
  if (!routesArray || !ts.isArrayLiteralExpression(routesArray)) {
    throw new Error(`${routesFile}: no 'routes' array literal found`);
  }

  const readArray = (array) => {
    const routes = [];
    for (const element of array.elements) {
      if (ts.isObjectLiteralExpression(element)) {
        routes.push(readRoute(element));
      } else if (
        ts.isSpreadElement(element) &&
        ts.isCallExpression(element.expression) &&
        ts.isIdentifier(element.expression.expression)
      ) {
        const declaration = functions.get(element.expression.expression.text);
        if (declaration) {
          routes.push({
            path: `…${element.expression.expression.text}()`,
            declared: [],
            imports: dynamicImportsIn(declaration),
            children: [],
          });
        }
      }
    }
    return routes;
  };
  const readRoute = (objectLiteral) => {
    const pathProperty = propertyNamed(objectLiteral, 'path');
    const load = propertyNamed(objectLiteral, 'loadComponent');
    const guards = propertyNamed(objectLiteral, 'canActivate');
    const children = propertyNamed(objectLiteral, 'children');
    return {
      path: pathProperty && ts.isStringLiteral(pathProperty.initializer) ? pathProperty.initializer.text : '?',
      declared: guards ? guardAreasIn(guards.initializer) : [],
      imports: load ? dynamicImportsIn(load.initializer) : [],
      children:
        children && ts.isArrayLiteralExpression(children.initializer) ? readArray(children.initializer) : [],
    };
  };
  return readArray(routesArray);
}

/**
 * @returns {Promise<{
 *   routes: { path: string, declared: string[], needs: string[], missing: { area: string, key: string, chain: string[] }[] }[],
 *   eager: { needs: string[], outsideCore: { area: string, key: string, chain: string[] }[] },
 *   unknownDeclared: { path: string, area: string }[],
 * }>}
 */
export async function analyseRoutes(appDir) {
  const { MESSAGE_AREAS, CORE_AREA, areaOfKey } = await loadMessageAreas(appDir);
  const files = catalogueFiles(appDir);
  const keys = files.english.flatMap((file) => readEntries(file).entries.map((entry) => entry.key));
  const state = {
    appDir,
    keys,
    areaOfKey,
    facts: new Map(),
    ignored: new Set(files.ignored),
  };
  const routesFile = path.join(appDir, ROUTES_FILE);
  const tree = readRouteTree(routesFile);

  const rows = [];
  const unknownDeclared = [];
  const visit = (route, inherited, trail) => {
    const here = trail === '' ? route.path : `${trail}/${route.path}`;
    const declared = new Set([...inherited, ...route.declared]);
    for (const area of route.declared) {
      if (!MESSAGE_AREAS.includes(area)) {
        unknownDeclared.push({ path: here || '(root)', area });
      }
    }
    if (route.imports.length > 0) {
      const needs = new Map();
      for (const specifier of route.imports) {
        const entry = resolveImport(routesFile, specifier);
        if (!entry) {
          continue;
        }
        for (const [area, why] of needsOf(entry, state, { followDynamic: true })) {
          if (!needs.has(area)) {
            needs.set(area, why);
          }
        }
      }
      rows.push({
        path: here || '(root)',
        declared: [...declared].sort(),
        needs: [...needs.keys()].sort(),
        missing: [...needs]
          .filter(([area]) => area !== CORE_AREA && !declared.has(area))
          .map(([area, why]) => ({ area, ...why })),
      });
    }
    for (const child of route.children) {
      visit(child, declared, here);
    }
  };
  for (const route of tree) {
    visit(route, new Set(), '');
  }

  const mainFile = path.join(appDir, 'src/main.ts');
  const eagerNeeds = needsOf(mainFile, state, { followDynamic: false });
  return {
    routes: rows,
    eager: {
      needs: [...eagerNeeds.keys()].sort(),
      outsideCore: [...eagerNeeds]
        .filter(([area]) => area !== CORE_AREA)
        .map(([area, why]) => ({ area, ...why })),
    },
    unknownDeclared,
  };
}

/** Human-readable findings, one per line; empty when the route table asks for everything it needs. */
export function problemsOf(analysis) {
  const problems = [];
  for (const { path: route, area, key } of analysis.unknownDeclared) {
    problems.push(`route '${route}' declares the unknown message area '${area}'`);
  }
  for (const row of analysis.routes) {
    for (const { area, key, chain } of row.missing) {
      problems.push(
        `route '${row.path}' needs the '${area}' messages (e.g. '${key}', reached via ${chain.join(' -> ')}) ` +
          `but no messagesGuard('${area}') is on its way; add it to the route or to a parent`,
      );
    }
  }
  for (const { area, key, chain } of analysis.eager.outsideCore) {
    problems.push(
      `code that runs before any route needs the '${area}' messages (e.g. '${key}', reached via ${chain.join(' -> ')}); ` +
        `only 'core' is loaded that early: move the key to core (AREA_BY_PREFIX) or load it from a route`,
    );
  }
  return problems;
}

async function main() {
  const argv = process.argv.slice(2);
  const option = (name) => (argv.includes(name) ? argv[argv.indexOf(name) + 1] : undefined);
  const appDir = path.resolve(
    option('--app-dir') ?? path.join(path.dirname(fileURLToPath(import.meta.url)), '../..'),
  );
  const analysis = await analyseRoutes(appDir);
  if (!argv.includes('--quiet')) {
    for (const row of analysis.routes) {
      const extra = row.needs.filter((area) => area !== 'core' && !row.declared.includes(area));
      console.log(
        `${row.path.padEnd(36)} declared [${row.declared.join(' ')}]  needs [${row.needs.filter((a) => a !== 'core').join(' ')}]${extra.length ? `  MISSING [${extra.join(' ')}]` : ''}`,
      );
    }
    console.log(`\nbefore any route: needs [${analysis.eager.needs.join(' ')}]`);
  }
  const problems = problemsOf(analysis);
  for (const problem of problems) {
    console.error(problem);
  }
  if (argv.includes('--check') && problems.length > 0) {
    process.exit(1);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}
