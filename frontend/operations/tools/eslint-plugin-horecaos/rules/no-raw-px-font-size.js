'use strict';

/**
 * `font-size: 14px` and friends — a raw pixel value on the one property
 * `tokens.css`'s closed type scale exists to own.
 *
 * `tokens.css` (ADR 0101's own console primitives wave) says it outright:
 * "Closed type scale. Never set a font-size inline." Nine `.q-*` classes —
 * `q-display` through `q-data-lg` — are the whole vocabulary; 249 raw
 * declarations already exist in `frontend/operations/src` as of this wave
 * (`T22` is the wave that removes them), and every one bypasses whatever the
 * design system does next to the scale. This rule is what stops the count
 * from becoming 250.
 *
 * Deliberately dumb: a regex over the raw text, not a real CSS/SCSS parser.
 * See `../raw-text-parser.js` for why that is enough here.
 */

const PX_FONT_SIZE = /font-size\s*:\s*[0-9]+(?:\.[0-9]+)?px\b/giu;

module.exports = {
  meta: {
    type: 'problem',
    docs: {
      description:
        "Ban a raw `px` font-size — every size lives in tokens.css's closed type scale (the `.q-*` classes), not at the call site.",
    },
    schema: [],
    messages: {
      rawPxFontSize:
        'Raw px font-size ("{{found}}") bypasses the closed type scale. Use one of the .q-* type classes (q-display, q-headline, q-title, q-subhead, q-body, q-body-sm, q-emphasis, q-caption, q-data-lg) from tokens.css instead.',
    },
  },
  create(context) {
    return {
      Program() {
        const sourceCode = context.getSourceCode();
        const text = sourceCode.getText();
        PX_FONT_SIZE.lastIndex = 0;
        let match = PX_FONT_SIZE.exec(text);
        while (match !== null) {
          const start = sourceCode.getLocFromIndex(match.index);
          const end = sourceCode.getLocFromIndex(match.index + match[0].length);
          context.report({
            loc: { start, end },
            messageId: 'rawPxFontSize',
            data: { found: match[0] },
          });
          match = PX_FONT_SIZE.exec(text);
        }
      },
    };
  },
};
