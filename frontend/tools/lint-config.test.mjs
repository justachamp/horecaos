#!/usr/bin/env node
/* Proves an app's ESLint configuration still fails on what it exists to fail on.
 *
 * `npm run lint` passing says only that the tree is clean *under the config as it
 * stands*. A config that lost its rules, its `files` globs or its parser would keep
 * passing and keep saying nothing -- so this feeds the configured linter text that is
 * known to be wrong and text that is known to be fine, and checks the verdicts.
 *
 * Run from an app directory (its `lint:rules` script does):
 *
 *   node ../tools/lint-config.test.mjs
 *
 * The app is the working directory, and the expectations are per app: `control-plane`
 * vendors the design system's closed type scale, so it also bans a raw `font-size: Npx`
 * (the rule frontend/operations owns); the two storefronts are Tailwind/SCSS apps with no
 * such scale, so for them a raw px font-size is NOT a finding and this checks that it stays
 * one that is not (a rule that fires where its premise is absent is noise that gets ignored).
 *
 * Node's built-in runner, no dependencies beyond the app's own eslint.
 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { basename, join } from 'node:path';
import { test } from 'node:test';

const appDir = process.cwd();
const app = basename(appDir);
const requireFromApp = createRequire(join(appDir, 'package.json'));
const { loadESLint } = requireFromApp('eslint');

const BANS_RAW_PX = app === 'control-plane';

const eslint = await new (await loadESLint({ useFlatConfig: true }))({ cwd: appDir });

async function verdict(text, filePath) {
  const [result] = await eslint.lintText(text, { filePath: join(appDir, filePath) });
  // A file no config object matches comes back with a rule-less "no matching configuration"
  // warning; that is not a finding, so it is dropped here.
  return result.messages.map((message) => message.ruleId).filter(Boolean);
}

test('a clean TypeScript file has no findings', async () => {
  const clean = [
    "export const answer = (a: number, _unused: string): number => (a === 1 ? 1 : 2);",
    '',
  ].join('\n');
  assert.deepEqual(await verdict(clean, 'src/probe.ts'), []);
});

for (const [name, source, rule] of [
  ['var', 'var a = 1;\nexport { a };\n', 'no-var'],
  ['any', 'export const a: any = 1;\n', '@typescript-eslint/no-explicit-any'],
  ['an unused import', "import { signal } from '@angular/core';\nexport const a = 1;\n", '@typescript-eslint/no-unused-vars'],
  ['loose equality', 'export const a = (x: number) => x == 2;\n', 'eqeqeq'],
  ['a debugger statement', 'export function a() {\n  debugger;\n}\n', 'no-debugger'],
  ['an empty block', 'export function a() {\n  try {\n    JSON.parse("");\n  } catch {}\n}\n', 'no-empty'],
]) {
  test(`TypeScript: ${name} is a finding (${rule})`, async () => {
    assert.ok((await verdict(source, 'src/probe.ts')).includes(rule));
  });
}

test('an underscore-prefixed argument is the way to say "deliberately unused"', async () => {
  assert.deepEqual(
    await verdict('export const a = (_ignored: string): number => 1;\n', 'src/probe.ts'),
    [],
  );
});

const RAW_PX = 'font-size: 14px;';
const PX_RULE = 'horecaos/no-raw-px-font-size';

for (const [where, path, source] of [
  ['a stylesheet', 'src/probe.css', `.a {\n  ${RAW_PX}\n}\n`],
  ['a template', 'src/probe.html', `<p style="${RAW_PX}">x</p>\n`],
  ['a component', 'src/probe.ts', `export const styles = \`.a { ${RAW_PX} }\`;\n`],
]) {
  test(
    BANS_RAW_PX
      ? `a raw px font-size in ${where} is a finding (${PX_RULE})`
      : `a raw px font-size in ${where} is not a finding: this app has no closed type scale`,
    async () => {
      const rules = await verdict(source, path);
      assert.equal(rules.includes(PX_RULE), BANS_RAW_PX);
    },
  );
}

test('a type-scale token reference is fine', async () => {
  assert.deepEqual(
    await verdict('.a {\n  font-size: var(--q-type-caption);\n}\n', 'src/probe.css'),
    [],
  );
});

if (BANS_RAW_PX) {
  test('the vendored token sheet, where the scale is defined, is ignored', async () => {
    assert.equal(await eslint.isPathIgnored(join(appDir, 'src/design-system/tokens.css')), true);
  });
}

// ADR 0149, Decision 6: the logical-properties ratchet is the control plane's too (the operations
// console runs it through its own `.eslintrc.json`, and proves it with its plugin's tests). The
// storefronts are Tailwind apps whose layout is utilities, so for them it is NOT a finding.
const DIRECTION_RULE = 'horecaos/no-physical-direction';

if (BANS_RAW_PX) {
  test('a physical margin in a new stylesheet is a finding, a logical one is not', async () => {
    assert.ok((await verdict('.a {\n  margin-left: 4px;\n}\n', 'src/probe.css')).includes(DIRECTION_RULE));
    assert.deepEqual(await verdict('.a {\n  margin-inline-start: 4px;\n}\n', 'src/probe.css'), []);
  });
} else {
  test('a physical margin is not a finding in an app whose layout is not hand-written CSS', async () => {
    assert.equal((await verdict('.a {\n  margin-left: 4px;\n}\n', 'src/probe.css')).includes(DIRECTION_RULE), false);
  });
}
