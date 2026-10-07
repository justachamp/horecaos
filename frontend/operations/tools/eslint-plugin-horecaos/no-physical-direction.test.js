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
  ],
  invalid: [
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
    },
  ],
});

// The ratchet: every file on the baseline exists and still needs to be there.
const baseline = rule.loadBaseline();
const stale = [];
const missing = [];
for (const file of baseline) {
  if (!fs.existsSync(file)) {
    missing.push(file);
    continue;
  }
  if (rule.findPhysical(fs.readFileSync(file, 'utf8')).length === 0) {
    stale.push(file);
  }
}
assert.deepStrictEqual(
  missing,
  [],
  `physical-direction-baseline.json names files that do not exist: ${missing}`,
);
assert.deepStrictEqual(
  stale,
  [],
  `physical-direction-baseline.json lists stylesheets that no longer use a physical direction; remove them so the list only shrinks: ${stale}`,
);

// And it is sorted and free of duplicates, so a diff of it reads as additions and removals only.
const sorted = [...baseline].sort();
assert.deepStrictEqual([...baseline], sorted, 'physical-direction-baseline.json must stay sorted');

console.log(
  `no-physical-direction: fixtures pass; ${baseline.size} stylesheets remain on the baseline and none of them is stale.`,
);
