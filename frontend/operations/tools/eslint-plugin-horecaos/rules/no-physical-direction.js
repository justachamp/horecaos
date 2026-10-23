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
 *
 * **Fixable.** Every spelling has exactly one logical twin, so `eslint --fix` rewrites a stylesheet
 * in place. The one thing it cannot know is intent: `left: 50%` beside `transform: translateX(-50%)`
 * centres in either direction and must stay physical. That is what the allow-list is for -- a
 * comment on the declaration's line (or the line above) that says `physical-ok: <why>`, with a
 * reason. A comment with no reason allows nothing.
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
    // `border-left-color` is `border-inline-start-color`: the width/style/color suffix is kept.
    /(?<![-\w])border-(left|right)((?:-(?:width|style|color))?)\s*:/giu,
    (side, suffix) => `border-inline-${side === 'left' ? 'start' : 'end'}${suffix.toLowerCase()}`,
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
 * A declaration the author has said must stay physical, with the reason: `physical-ok: <why>` in a
 * comment on its line or the line above (a CSS block comment or an SCSS line comment). The reason is
 * the point: without one the comment is just a way to silence the rule.
 */
const ALLOW = /physical-ok:\s*(?!\*\/)\S/u;

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

/**
 * The text a physical spelling is rewritten to. The match is `margin-left:`, `left: ` or
 * `text-align: right`; only its head (the property, or the value of `text-align` and `float`)
 * changes, so spacing and the colon survive.
 */
function replacementFor(matched, logical) {
  const lead = /^(?:text-align|float)\s*:\s*/iu.exec(matched);
  if (lead !== null) {
    const keyword = logical.slice(logical.indexOf(':') + 1).trim();
    return `${lead[0]}${keyword}`;
  }
  const property = /^[a-z-]+/iu.exec(matched)[0];
  return `${logical}${matched.slice(property.length)}`;
}

/**
 * Whether the line a hit is on carries `physical-ok: <reason>`, or the line above it is a comment
 * that does.
 */
function isAllowed(text, index) {
  const lineStart = text.lastIndexOf('\n', index - 1) + 1;
  const lineEndAt = text.indexOf('\n', index);
  const lineEnd = lineEndAt === -1 ? text.length : lineEndAt;
  if (ALLOW.test(text.slice(lineStart, lineEnd))) {
    return true;
  }
  if (lineStart === 0) {
    return false;
  }
  // The line above only speaks for the declaration below it when it is nothing but a comment;
  // otherwise one reason on one declaration would quietly cover its neighbour.
  const previousStart = text.lastIndexOf('\n', lineStart - 2) + 1;
  const previous = text.slice(previousStart, lineStart - 1).trim();
  return /^(?:\/\*|\/\/|\*)/u.test(previous) && ALLOW.test(previous);
}

/**
 * Every physical spelling in a piece of text: `{ index, length, found, logical, replacement,
 * allowed }`. `allowed` is true for a declaration that carries a `physical-ok:` reason.
 */
function findPhysical(text) {
  const found = [];
  for (const [pattern, logical] of PHYSICAL) {
    pattern.lastIndex = 0;
    let match = pattern.exec(text);
    while (match !== null) {
      const logicalName = logical(...match.slice(1));
      found.push({
        index: match.index,
        length: match[0].length,
        found: match[0].trim(),
        logical: logicalName,
        replacement: replacementFor(match[0], logicalName),
        allowed: isAllowed(text, match.index),
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
    fixable: 'code',
    schema: [],
    messages: {
      physicalDirection:
        'Physical direction ("{{found}}") is a layout that does not follow the writing direction. Use the logical property ({{logical}}) -- `eslint --fix` does it; or, to leave a stylesheet as it is, it must be listed in its app baseline (frontend/operations/tools/eslint-plugin-horecaos/physical-direction-baseline*.json), or the declaration must say why in a comment: `physical-ok: <reason>`.',
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
          if (hit.allowed) {
            continue;
          }
          context.report({
            loc: {
              start: sourceCode.getLocFromIndex(hit.index),
              end: sourceCode.getLocFromIndex(hit.index + hit.found.length),
            },
            messageId: 'physicalDirection',
            data: { found: hit.found, logical: hit.logical },
            fix: (fixer) =>
              fixer.replaceTextRange([hit.index, hit.index + hit.length], hit.replacement),
          });
        }
      },
    };
  },
};
