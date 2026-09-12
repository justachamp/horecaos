'use strict';

/**
 * A parser that parses nothing — every file it is pointed at becomes one
 * empty `Program`, whatever its actual syntax.
 *
 * Why this exists: the rule this plugin ships (`no-raw-px-font-size`) has to
 * run over `.css` files, and ESLint has no CSS grammar built in. Rather than
 * pull in a full CSS-AST toolchain (or `@angular-eslint`'s template parser,
 * for the `.html` side) for one regex-shaped rule, this parser lets ESLint's
 * plumbing — config resolution, `SourceCode`, `context.report`'s line/column
 * math — do its job on the *raw text* of any file, `.ts`/`.html`/`.css`
 * alike, without caring whether that text parses as anything at all.
 *
 * `SourceCode.getLocFromIndex` computes line/column from the text itself, so
 * accurate error locations do not require a real AST — only `no-raw-px-font-size`
 * is registered against files parsed this way, and it never touches `node`
 * beyond the `Program` it is handed for the one visit it needs.
 */
function parseForESLint(code) {
  const lines = code.split(/\r\n|\r|\n/u);
  return {
    ast: {
      type: 'Program',
      sourceType: 'module',
      body: [],
      comments: [],
      tokens: [],
      range: [0, code.length],
      loc: {
        start: { line: 1, column: 0 },
        end: { line: lines.length, column: lines[lines.length - 1].length },
      },
    },
  };
}

module.exports = { parseForESLint };
