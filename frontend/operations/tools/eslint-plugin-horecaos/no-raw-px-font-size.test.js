'use strict';

/**
 * Fixture proof for `no-raw-px-font-size` (ADR 0101, wave P02's ESLint rule).
 *
 * Run with `node tools/eslint-plugin-horecaos/no-raw-px-font-size.test.js`
 * from `frontend/operations/` — plain Node, no Angular/vitest harness,
 * because `ng test` only discovers specs under `src/` and this rule's own
 * dependency (`eslint`) is a Node CLI tool, not application code.
 *
 * Uses `RuleTester` directly against `__fixtures__/raw-px.css` (must fail,
 * naming the exact raw declaration) and `__fixtures__/clean.css` (must
 * pass), through the same `raw-text-parser.js` the real `.eslintrc.json`
 * points at — this is the rule and the fixture the wave brief asks for, not
 * a paraphrase of them.
 */

const fs = require('fs');
const path = require('path');
const { RuleTester } = require('eslint');

const rule = require('./rules/no-raw-px-font-size');

const PARSER_PATH = path.join(__dirname, 'raw-text-parser.js');

function fixture(name) {
  return fs.readFileSync(path.join(__dirname, '__fixtures__', name), 'utf8');
}

const ruleTester = new RuleTester();

ruleTester.run('no-raw-px-font-size', rule, {
  valid: [{ code: fixture('clean.css'), parser: PARSER_PATH }],
  invalid: [
    {
      code: fixture('raw-px.css'),
      parser: PARSER_PATH,
      errors: [{ messageId: 'rawPxFontSize', data: { found: 'font-size: 13px' } }],
    },
    // The rule reads raw text regardless of file kind — it has to catch the
    // same declaration inline in a component's `.ts` string, not only in a
    // dedicated stylesheet.
    {
      code: "const style = 'font-size: 11px; color: red;';",
      parser: PARSER_PATH,
      errors: [{ messageId: 'rawPxFontSize' }],
    },
    // A decimal size and a unitless zero-adjacent value both still count.
    {
      code: '.x { font-size: 13.5px; }',
      parser: PARSER_PATH,
      errors: [{ messageId: 'rawPxFontSize' }],
    },
  ],
});

console.log('no-raw-px-font-size: fixtures pass (fails on raw px, passes on tokens.css classes).');
