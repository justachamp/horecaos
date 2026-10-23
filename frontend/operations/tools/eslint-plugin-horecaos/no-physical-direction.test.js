'use strict';

/**
 * Fixture proof for `no-physical-direction` (ADR 0149, Decision 6): the logical-properties lint.
 *
 * Run with `node tools/eslint-plugin-horecaos/no-physical-direction.test.js` from
 * `frontend/operations/`, the way its sibling is run (`npm run lint:rules`).
 *
 * Four things are proved: the rule names each physical spelling and the logical one it becomes, and
 * `--fix` rewrites it; it lets a logical stylesheet through (and does not mistake `left: auto` or a
 * class called `.table-left-column` for an offset); a declaration that says `physical-ok: <why>`
 * is allowed and one with no reason is not; and, whatever any app's lint config says, no
 * stylesheet of any app says `margin-left` any more -- the ratchet has no baseline list, only the
 * reasoned exceptions, and their number is capped here so it cannot grow unseen.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { RuleTester } = require('eslint');

const rule = require('./rules/no-physical-direction');

const PARSER_PATH = path.join(__dirname, 'raw-text-parser.js');

function fixture(name) {
  return fs.readFileSync(path.join(__dirname, '__fixtures__', name), 'utf8');
}

// The rule skips a file named in the baseline; the fixtures are named in nothing.
const ruleTester = new RuleTester();

ruleTester.run('no-physical-direction', rule, {
  valid: [
    {
      code: fixture('logical-direction.css'),
      parser: PARSER_PATH,
      filename: 'fixtures/logical.css',
    },
    {
      // A declaration that says why it must stay physical: on its own line, on the line above, or
      // in SCSS's own comment syntax.
      code: fixture('allowed-physical.css'),
      parser: PARSER_PATH,
      filename: 'fixtures/allowed.css',
    },
  ],
  invalid: [
    {
      // The fixer: each spelling becomes its one logical twin, spacing and the rest untouched, and
      // the width/style/colour suffix of a border side survives.
      code: [
        '.a { margin-left: 4px; margin-right:0; padding-left : 2px; }',
        '.b { border-right-color: red; border-left: 1px solid; text-align:right; }',
        '.c { left: 1px; right:\n    2px; float: right; border-bottom-right-radius: 3px; }',
      ].join('\n'),
      parser: PARSER_PATH,
      filename: 'fixtures/fixer.css',
      output: [
        '.a { margin-inline-start: 4px; margin-inline-end:0; padding-inline-start : 2px; }',
        '.b { border-inline-end-color: red; border-inline-start: 1px solid; text-align:end; }',
        '.c { inset-inline-start: 1px; inset-inline-end:\n    2px; float: inline-end; border-end-end-radius: 3px; }',
      ].join('\n'),
      errors: 10,
    },
    {
      // A `physical-ok` with no reason allows nothing, and one declaration's reason does not cover
      // its neighbour on the next line.
      code: [
        '.a {',
        '  left: 50%; /* physical-ok: centred */',
        '  right: 0;',
        '  margin-left: 1px; /* physical-ok: */',
        '}',
      ].join('\n'),
      parser: PARSER_PATH,
      filename: 'fixtures/unreasoned.css',
      output: [
        '.a {',
        '  left: 50%; /* physical-ok: centred */',
        '  inset-inline-end: 0;',
        '  margin-inline-start: 1px; /* physical-ok: */',
        '}',
      ].join('\n'),
      errors: [
        { messageId: 'physicalDirection', data: { found: 'right:', logical: 'inset-inline-end' } },
        {
          messageId: 'physicalDirection',
          data: { found: 'margin-left:', logical: 'margin-inline-start' },
        },
      ],
    },
    {
      code: fixture('physical-direction.css'),
      parser: PARSER_PATH,
      filename: 'fixtures/physical.css',
      errors: [
        {
          messageId: 'physicalDirection',
          data: { found: 'margin-left:', logical: 'margin-inline-start' },
        },
        {
          messageId: 'physicalDirection',
          data: { found: 'padding-right:', logical: 'padding-inline-end' },
        },
        {
          messageId: 'physicalDirection',
          data: { found: 'border-left:', logical: 'border-inline-start' },
        },
        {
          messageId: 'physicalDirection',
          data: { found: 'text-align: right', logical: 'text-align: end' },
        },
        { messageId: 'physicalDirection', data: { found: 'right:', logical: 'inset-inline-end' } },
        {
          messageId: 'physicalDirection',
          data: { found: 'float: left', logical: 'float: inline-start' },
        },
        {
          messageId: 'physicalDirection',
          data: { found: 'border-top-left-radius:', logical: 'border-start-start-radius' },
        },
      ],
      output: fixture('physical-direction.fixed.css'),
    },
  ],
});

// The ratchet, across the apps: every stylesheet under every app's `src/`, read from disk.
// `lint:rules` of an app proves that app's lint config still carries the rule
// (`frontend/tools/lint-config.test.mjs`); this proves the tree is clean whichever config is read.
const FRONTEND = path.join(__dirname, '..', '..', '..');
const APPS = ['operations', 'control-plane', 'storefront', 'storefront-milliy', 'courier'];
const STYLESHEET = /\.(?:css|scss)$/u;

/** The exceptions that were agreed, by file. Growing this list is a decision a reviewer sees. */
const ALLOWED = {
  'operations/src/app/shared/ui/charts/donut-chart.css': 1,
  'operations/src/app/shared/ui/kiosk-frame.css': 1,
  'operations/src/app/shared/ui/phone-frame.css': 1,
  'operations/src/app/shared/ui/telegram-mini-app-frame.css': 1,
};

function stylesheets(dir) {
  const found = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      found.push(...stylesheets(full));
    } else if (STYLESHEET.test(entry.name)) {
      found.push(full);
    }
  }
  return found;
}

const violations = [];
const allowed = {};
let scanned = 0;
for (const app of APPS) {
  for (const file of stylesheets(path.join(FRONTEND, app, 'src'))) {
    scanned += 1;
    const key = path.relative(FRONTEND, file).split(path.sep).join('/');
    for (const hit of rule.findPhysical(fs.readFileSync(file, 'utf8'))) {
      if (hit.allowed) {
        allowed[key] = (allowed[key] ?? 0) + 1;
      } else {
        violations.push(`${key}: ${hit.found} (use ${hit.logical})`);
      }
    }
  }
}
assert.deepStrictEqual(
  violations,
  [],
  `physical directions in a stylesheet: run \`npx eslint --fix "src/**/*.{css,scss}"\` in the app, or say why it must stay with a \`physical-ok: <reason>\` comment`,
);
assert.deepStrictEqual(
  allowed,
  ALLOWED,
  'the stylesheets that keep a physical declaration with a reason changed: update ALLOWED here, so the exception is seen in review',
);

// The old escape hatch is gone and stays gone: a per-app baseline list is a silent exception.
const leftovers = fs
  .readdirSync(__dirname)
  .filter((name) => name.startsWith('physical-direction-baseline'));
assert.deepStrictEqual(leftovers, [], 'the physical-direction baselines were removed (X.40)');

console.log(
  `no-physical-direction: fixtures pass; ${scanned} stylesheets across ${APPS.length} apps, no physical declaration, ${Object.keys(allowed).length} stylesheets with a stated exception (${Object.values(allowed).reduce((a, b) => a + b, 0)} declarations).`,
);
