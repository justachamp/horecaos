#!/usr/bin/env node
/* Drift check for the vendored design-system tokens.
 *
 * The token sheet is generated, not authored (ADR 0035). This compares the
 * vendored copy against the source of record and fails if they differ, so a
 * token that moved in the design system cannot sit unnoticed in this
 * repository until a designer spots two buttons that do not match.
 *
 * The source of record is the design system's token sheet, carried verbatim in
 * the platform repository. This repository has no dependency on that one, so
 * the check is skipped rather than failed when the platform repository is not
 * beside it — a developer who cloned only this application should not be
 * blocked, while CI, which will have both, is.
 *
 * When the design system is published as a package this script is deleted and
 * the version range does the job instead.
 */

import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const vendored = resolve(here, '../src/design-system/tokens.css');
const sourceOfRecord = resolve(
  here,
  '../../prototypes/control-plane/src/tokens.css',
);

/** Both files open with a block comment that is not part of the tokens. */
function body(css) {
  return css.replace(/^\s*\/\*[\s\S]*?\*\/\s*/, '').trimEnd();
}

if (!existsSync(sourceOfRecord)) {
  console.log(
    'check:tokens skipped — the platform repository is not beside this one, ' +
      'so there is nothing to compare against.',
  );
  process.exit(0);
}

const ours = body(readFileSync(vendored, 'utf8'));
const theirs = body(readFileSync(sourceOfRecord, 'utf8'));

if (ours === theirs) {
  console.log('check:tokens ok — the vendored sheet matches the source of record.');
  process.exit(0);
}

console.error(
  'check:tokens FAILED — src/design-system/tokens.css differs from the source of record.\n' +
    '\n' +
    'Do not edit the vendored copy to make this pass. Either the design system\n' +
    'moved, in which case regenerate; or somebody edited the copy, in which case\n' +
    'that edit is the defect.\n' +
    `\n  vendored:        ${vendored}\n  source of record: ${sourceOfRecord}\n`,
);
process.exit(1);
