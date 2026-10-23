/* The @font-face rules a stylesheet tree actually ships.
 *
 * The glyph audit has to read what the browser will be told, not what a README says: which family,
 * weight and style each rule declares, which file it points at and, above all, which code points its
 * `unicode-range` claims. This follows `@import` the way the build does (a relative path, or a
 * package specifier resolved inside the app's own `node_modules`) and returns the rules in the order
 * the cascade sees them, because for one character the browser takes the LAST rule of a family whose
 * range includes it.
 *
 * It is a reader for the CSS these apps ship (plain `@import` and `@font-face`), not a CSS parser:
 * comments are stripped, nested blocks other than `@font-face` are ignored, and a construct it does
 * not understand is an error rather than a guess.
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

/** @typedef {{ family: string, weight: string, style: string, files: string[], ranges: Array<[number, number]> | null, from: string }} FontFace */

/** @param {string} css */
function withoutComments(css) {
  return css.replace(/\/\*[\s\S]*?\*\//g, '');
}

/**
 * `U+0400-045F, U+0490, U+04A?` -> [[0x400, 0x45f], [0x490, 0x490], [0x4a0, 0x4af]]
 * @param {string} value
 * @returns {Array<[number, number]>}
 */
export function parseUnicodeRange(value) {
  return value.split(',').map((part) => {
    const token = part.trim().replace(/^u\+/i, '');
    if (token.includes('?')) {
      return [parseInt(token.replace(/\?/g, '0'), 16), parseInt(token.replace(/\?/g, 'F'), 16)];
    }
    const [from, to] = token.split('-');
    const low = parseInt(from, 16);
    return [low, to === undefined ? low : parseInt(to, 16)];
  });
}

function declarations(block) {
  /** @type {Record<string, string>} */
  const found = {};
  // A `url(data:...;base64,...)` carries a `;`, so split on `;` only outside parentheses.
  let depth = 0;
  let start = 0;
  const pieces = [];
  for (let index = 0; index < block.length; index += 1) {
    const char = block[index];
    if (char === '(') depth += 1;
    if (char === ')') depth -= 1;
    if (char === ';' && depth === 0) {
      pieces.push(block.slice(start, index));
      start = index + 1;
    }
  }
  pieces.push(block.slice(start));
  for (const piece of pieces) {
    const colon = piece.indexOf(':');
    if (colon > 0) {
      found[piece.slice(0, colon).trim().toLowerCase()] = piece.slice(colon + 1).trim();
    }
  }
  return found;
}

/** Where an `@import` specifier lives on disk, or null when it is not a file this audit can read (a remote URL). */
function importTarget(specifier, fromFile, appDir) {
  if (/^(https?:)?\/\//.test(specifier)) {
    return null;
  }
  if (specifier.startsWith('.') || specifier.startsWith('/')) {
    return resolve(dirname(fromFile), specifier);
  }
  return join(appDir, 'node_modules', specifier);
}

/**
 * @param {string} entry the stylesheet to start from
 * @param {string} appDir the app, whose `node_modules` bare specifiers resolve in
 * @returns {{ faces: FontFace[], remote: string[] }} `remote` lists stylesheets fetched at runtime, which the audit cannot read
 */
export function shippedFontFaces(entry, appDir) {
  /** @type {FontFace[]} */
  const faces = [];
  const remote = [];
  const seen = new Set();

  const visit = (file) => {
    if (seen.has(file)) {
      return;
    }
    seen.add(file);
    if (!existsSync(file)) {
      throw new Error(`the stylesheet ${file} does not exist`);
    }
    const css = withoutComments(readFileSync(file, 'utf8'));
    // Rules are taken in source order, an @import being replaced by what it imports.
    const token = /@import\s+(?:url\(\s*)?['"]?([^'")\s;]+)['"]?\s*\)?[^;]*;|@font-face\s*\{([^}]*)\}/g;
    for (const match of css.matchAll(token)) {
      if (match[1] !== undefined) {
        const target = importTarget(match[1], file, appDir);
        if (target === null) {
          remote.push(match[1]);
        } else {
          visit(target);
        }
        continue;
      }
      const found = declarations(match[2]);
      const family = (found['font-family'] ?? '').replace(/^['"]|['"]$/g, '');
      const files = [...(found['src'] ?? '').matchAll(/url\(\s*['"]?([^'")]+)['"]?\s*\)/g)]
        .map((hit) => hit[1])
        .filter((url) => !url.startsWith('data:') && !/^(https?:)?\/\//.test(url))
        .map((url) => resolve(dirname(file), url.split('?')[0].split('#')[0]));
      faces.push({
        family,
        weight: found['font-weight'] ?? '400',
        style: found['font-style'] ?? 'normal',
        files,
        ranges: found['unicode-range'] === undefined ? null : parseUnicodeRange(found['unicode-range']),
        from: file,
      });
    }
  };

  visit(resolve(appDir, entry));
  return { faces, remote };
}
