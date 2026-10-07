'use strict';

/**
 * `margin-left`, `padding-right`, `left: 0`, `text-align: right` and the rest of the physical
 * spellings -- the ones that mean the wrong thing the day a right-to-left language is registered
 * (ADR 0149, Decision 6).
 *
 * Every language the platform registers is left-to-right today, and `<html dir>` says so
 * (`core/i18n/document-direction.ts`). Nothing here is broken. What this rule keeps cheap is the
 * day that changes: the audit of every screen is the cost, and it grows with every stylesheet
 * written in `left` and `right`. A logical property (`margin-inline-start`, `padding-inline-end`,
 * `inset-inline-start`, `text-align: start`) says the same thing now and follows the writing
 * direction later.
 *
 * **A ratchet, not a purge.** A hundred and forty-odd stylesheets already say it the physical way,
 * and rewriting them is a visual change nobody has asked for. So the rule fails on a stylesheet that
 * is not on `physical-direction-baseline.json`: new files, and any file somebody touches and
 * removes from the list by cleaning it. The test beside it (`no-physical-direction.test.js`) fails
 * when a listed file no longer needs to be, so the list can only shrink.
 *
 * Deliberately dumb, like its sibling: a regex over the raw text, the same parser.
 */

const fs = require('fs');
const path = require('path');

/** Each pattern is one physical spelling and the logical one it becomes. */
const PHYSICAL = [
  [
    /(?<![-\w])margin-(left|right)\s*:/giu,
    (side) => `margin-inline-${side === 'left' ? 'start' : 'end'}`,
  ],
  [
    /(?<![-\w])padding-(left|right)\s*:/giu,
    (side) => `padding-inline-${side === 'left' ? 'start' : 'end'}`,
  ],
  [
    /(?<![-\w])border-(left|right)(?:-(?:width|style|color))?\s*:/giu,
    (side) => `border-inline-${side === 'left' ? 'start' : 'end'}`,
  ],
  [
    /(?<![-\w])(left|right)\s*:\s*(?!\s*(?:auto|inherit|initial|unset)\b)/giu,
    (side) => `inset-inline-${side === 'left' ? 'start' : 'end'}`,
  ],
  [
    /(?<![-\w])text-align\s*:\s*(left|right)\b/giu,
    (side) => `text-align: ${side === 'left' ? 'start' : 'end'}`,
  ],
  [
    /(?<![-\w])float\s*:\s*(left|right)\b/giu,
    (side) => `float: inline-${side === 'left' ? 'start' : 'end'}`,
  ],
  [
    /(?<![-\w])border-(top|bottom)-(left|right)-radius\s*:/giu,
    (block, side) =>
      `border-${block === 'top' ? 'start' : 'end'}-${side === 'left' ? 'start' : 'end'}-radius`,
  ],
];

/**
 * One baseline per app, because a path in it is relative to that app: the operations console's
 * is `physical-direction-baseline.json`, any other app's is `physical-direction-baseline.<app>.json`
 * beside it (the control plane shares this plugin). An app with no baseline file has no exceptions.
 */
function baselineFileFor(app) {
  const name =
    app === 'operations'
      ? 'physical-direction-baseline.json'
      : `physical-direction-baseline.${app}.json`;
  return path.join(__dirname, '..', name);
}

function loadBaseline(app = path.basename(process.cwd())) {
  const file = baselineFileFor(app);
  return new Set(fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : []);
}

/** The path the baseline lists: relative to `frontend/operations`, forward slashes. */
function baselineKey(filename) {
  return path.relative(process.cwd(), filename).split(path.sep).join('/');
}

/** Every physical spelling in a piece of text: `{ index, found, logical }`. */
function findPhysical(text) {
  const found = [];
  for (const [pattern, logical] of PHYSICAL) {
    pattern.lastIndex = 0;
    let match = pattern.exec(text);
    while (match !== null) {
      found.push({
        index: match.index,
        found: match[0].trim(),
        logical: logical(...match.slice(1)),
      });
      match = pattern.exec(text);
    }
  }
  return found.sort((a, b) => a.index - b.index);
}

module.exports = {
  findPhysical,
  baselineKey,
  loadBaseline,
  baselineFileFor,
  meta: {
    type: 'problem',
    docs: {
      description:
        "Ban a physical left/right offset, margin, padding, border or alignment in a stylesheet that is not on the baseline: the writing direction is the registry's to state (ADR 0149).",
    },
    schema: [],
    messages: {
      physicalDirection:
        'Physical direction ("{{found}}") is a layout that does not follow the writing direction. Use the logical property ({{logical}}); or, to leave a stylesheet as it is, it must be listed in its app baseline (frontend/operations/tools/eslint-plugin-horecaos/physical-direction-baseline*.json).',
    },
  },
  create(context) {
    return {
      Program() {
        if (loadBaseline().has(baselineKey(context.getFilename()))) {
          return;
        }
        const sourceCode = context.getSourceCode();
        const text = sourceCode.getText();
        for (const hit of findPhysical(text)) {
          context.report({
            loc: {
              start: sourceCode.getLocFromIndex(hit.index),
              end: sourceCode.getLocFromIndex(hit.index + hit.found.length),
            },
            messageId: 'physicalDirection',
            data: { found: hit.found, logical: hit.logical },
          });
        }
      },
    };
  },
};
