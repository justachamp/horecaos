'use strict';

/**
 * Fixture proof for `no-physical-direction` (ADR 0149, Decision 6): the logical-properties lint.
 *
 * Run with `node tools/eslint-plugin-horecaos/no-physical-direction.test.js` from
 * `frontend/operations/`, the way its sibling is run (`npm run lint:rules`).
 *
 * Three things are proved: the rule names each physical spelling and the logical one it becomes; it
 * lets a logical stylesheet through (and does not mistake `left: auto` or a class called
 * `.table-left-column` for an offset); and the baseline is a ratchet that can only shrink, because a
 * stylesheet listed in it that no longer needs to be is a failure here.
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

// The ratchet, per app: every file on a baseline exists and still needs to be there.
const apps = [
  { app: 'operations', dir: process.cwd() },
  { app: 'control-plane', dir: path.join(process.cwd(), '..', 'control-plane') },
  { app: 'storefront', dir: path.join(process.cwd(), '..', 'storefront') },
  { app: 'storefront-milliy', dir: path.join(process.cwd(), '..', 'storefront-milliy') },
];
const sizes = [];
for (const { app, dir } of apps) {
  const baseline = rule.loadBaseline(app);
  const stale = [];
  const missing = [];
  for (const file of baseline) {
    const full = path.join(dir, file);
    if (!fs.existsSync(full)) {
      missing.push(file);
      continue;
    }
    if (rule.findPhysical(fs.readFileSync(full, 'utf8')).length === 0) {
      stale.push(file);
    }
  }
  assert.deepStrictEqual(
    missing,
    [],
    `${app}'s physical-direction baseline names files that do not exist: ${missing}`,
  );
  assert.deepStrictEqual(
    stale,
    [],
    `${app}'s physical-direction baseline lists stylesheets that no longer use a physical direction; remove them so the list only shrinks: ${stale}`,
  );
  // Sorted and free of duplicates, so a diff of it reads as additions and removals only.
  assert.deepStrictEqual([...baseline], [...baseline].sort(), `${app}'s baseline must stay sorted`);
  sizes.push(`${app} ${baseline.size}`);
}

console.log(
  `no-physical-direction: fixtures pass; stylesheets remaining on the baselines (${sizes.join(', ')}) and none of them is stale.`,
);
