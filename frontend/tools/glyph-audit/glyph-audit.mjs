#!/usr/bin/env node
/* The glyph audit (ADR 0149, Decision 7 and its checklist; gap-map row X.40).
 *
 * "Does the face we ship contain the letters this language is written in" is a question about font
 * FILES, and it was being answered from memory: the operations README said Plex covers neither
 * Kazakh's extra Cyrillic letters nor Georgian, the record deferred the check to the first
 * activation, and nothing would have failed the day somebody declared `kk` live over a face that
 * could not draw it. This reads what is actually shipped and fails.
 *
 * Run from an app directory (its `lint:rules` script does, and so does CI):
 *
 *   node ../tools/glyph-audit/glyph-audit.mjs [--json]
 *
 * For every language in alphabets.json whose face is shipped it takes the stylesheet tree the app
 * ships, finds -- for each weight and style of that face and for each letter the language needs --
 * the @font-face rule the browser would choose (the LAST rule of the family whose unicode-range
 * includes the letter) and reads the cmap of the file that rule names. A letter no rule claims, or
 * one a claiming rule's file does not draw, is a failure: the browser would paint it in a system
 * face mid-word.
 *
 * It also pins, as a performance fact, which letters are served by a rule with NO unicode-range
 * (a "complete" file, 60 KB a weight) instead of a split subset: in the operations console only the
 * Uzbek modifier letters, because no split subset of @ibm/plex-sans contains them (the control plane's
 * @fontsource subsets do). The list is per app in alphabets.json; a letter that joins it, or an entry
 * that no longer applies, fails, so the cost is never silent.
 *
 * Languages whose face is not shipped (Georgian, `georgian-lazy`) are reported as declared and
 * skipped. The backend's PlatformLocalesTests is what makes that safe: it fails if such a language
 * is made live in any tier.
 */
import { readFileSync, existsSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { shippedFontFaces, parseUnicodeRange } from './css-faces.mjs';
import { codePointsInFont } from './font-cmap.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

/** @param {string} value @returns {number[]} */
export function expand(value) {
  return parseUnicodeRange(value.join ? value.join(',') : value).flatMap(([low, high]) =>
    Array.from({ length: high - low + 1 }, (_, index) => low + index),
  );
}

const hex = (codePoint) => `U+${codePoint.toString(16).toUpperCase().padStart(4, '0')}`;

/** Collapses sorted code points to U+ ranges for a readable report. */
export function describeCodePoints(codePoints) {
  const sorted = [...new Set(codePoints)].sort((a, b) => a - b);
  const parts = [];
  for (let index = 0; index < sorted.length; ) {
    let end = index;
    while (end + 1 < sorted.length && sorted[end + 1] === sorted[end] + 1) {
      end += 1;
    }
    parts.push(end === index ? hex(sorted[index]) : `${hex(sorted[index])}-${hex(sorted[end]).slice(2)}`);
    index = end + 1;
  }
  return parts.join(' ');
}

/**
 * @param {{ appDir: string, appName?: string, config?: any }} options
 * @returns {{ ok: boolean, languages: any[], completeFace: any, remote: string[], failures: string[] }}
 */
export function auditApp({ appDir, appName = basename(appDir), config }) {
  const alphabets = config ?? JSON.parse(readFileSync(join(HERE, 'alphabets.json'), 'utf8'));
  const app = alphabets.apps[appName];
  if (!app) {
    throw new Error(`alphabets.json has no entry for the app "${appName}" under "apps"`);
  }

  const faces = [];
  const remote = [];
  for (const entry of app.stylesheets) {
    const found = shippedFontFaces(entry, appDir);
    faces.push(...found.faces);
    remote.push(...found.remote);
  }

  const cmaps = new Map();
  const drawn = (file) => {
    if (!cmaps.has(file)) {
      if (!existsSync(file)) {
        throw new Error(`a @font-face rule names ${file}, which is not there`);
      }
      cmaps.set(file, codePointsInFont(readFileSync(file)));
    }
    return cmaps.get(file);
  };

  const failures = [];
  const languages = [];
  const servedByCompleteFace = new Map();

  for (const [tag, alphabet] of Object.entries(alphabets.alphabets)) {
    const face = alphabets.faces[alphabet.face];
    if (!face) {
      failures.push(`${tag}: its face "${alphabet.face}" is not declared under "faces"`);
      continue;
    }
    if (!face.shipped) {
      languages.push({ tag, face: alphabet.face, status: 'declared, no face shipped', missing: [] });
      continue;
    }
    const rules = faces.filter((rule) => rule.family === face.family);
    if (rules.length === 0) {
      failures.push(`${tag}: the app ships no @font-face for ${face.family}`);
      languages.push({ tag, face: alphabet.face, status: 'FAILED: face not shipped', missing: [] });
      continue;
    }
    const variants = [...new Set(rules.map((rule) => `${rule.weight} ${rule.style}`))];
    const required = expand(alphabet.ranges);
    const missing = [];
    for (const variant of variants) {
      const ofVariant = rules.filter((rule) => `${rule.weight} ${rule.style}` === variant);
      const lacking = [];
      for (const codePoint of required) {
        const claiming = ofVariant.filter(
          (rule) => rule.ranges === null || rule.ranges.some(([low, high]) => codePoint >= low && codePoint <= high),
        );
        const serving = claiming.at(-1);
        if (!serving) {
          lacking.push([codePoint, 'no @font-face rule claims it']);
          continue;
        }
        const file = serving.files.find((candidate) => /\.(woff2?|ttf|otf)$/i.test(candidate));
        if (!file) {
          lacking.push([codePoint, 'its rule names no font file']);
          continue;
        }
        if (!drawn(file).has(codePoint)) {
          lacking.push([codePoint, `the file ${basename(file)} has no glyph for it`]);
          continue;
        }
        if (serving.ranges === null) {
          const key = alphabet.face;
          servedByCompleteFace.set(key, new Set([...(servedByCompleteFace.get(key) ?? []), codePoint]));
        }
      }
      if (lacking.length > 0) {
        missing.push({ variant, codePoints: lacking.map(([codePoint]) => codePoint), reasons: [...new Set(lacking.map(([, reason]) => reason))] });
      }
    }
    for (const gap of missing) {
      failures.push(
        `${tag}: ${face.family} ${gap.variant} cannot draw ${describeCodePoints(gap.codePoints)} (${gap.reasons.join('; ')})`,
      );
    }
    languages.push({
      tag,
      face: alphabet.face,
      status: missing.length === 0 ? 'covered' : 'FAILED',
      variants: variants.length,
      letters: required.length,
      missing,
    });
  }

  // The performance pin: which letters ride a complete (unrestricted) file.
  const completeFace = {};
  for (const [faceId, allowedRanges] of Object.entries(app.completeFaceAllowed ?? {})) {
    const allowed = new Set(expand(allowedRanges));
    const actual = servedByCompleteFace.get(faceId) ?? new Set();
    const unexpected = [...actual].filter((codePoint) => !allowed.has(codePoint));
    const stale = [...allowed].filter((codePoint) => !actual.has(codePoint));
    completeFace[faceId] = { served: describeCodePoints([...actual]), allowed: describeCodePoints([...allowed]) };
    if (unexpected.length > 0) {
      failures.push(
        `${faceId}: ${describeCodePoints(unexpected)} would be fetched from the COMPLETE face (about 60 KB a weight) because no split subset claims it; add a subset or name it under completeFaceAllowed on purpose`,
      );
    }
    if (stale.length > 0) {
      failures.push(
        `${faceId}: ${describeCodePoints(stale)} is listed under completeFaceAllowed but is no longer served by the complete face; remove the exception`,
      );
    }
  }
  for (const [faceId, actual] of servedByCompleteFace) {
    if (!(faceId in (app.completeFaceAllowed ?? {}))) {
      failures.push(`${faceId}: ${describeCodePoints([...actual])} is served from the complete face and no exception is declared`);
    }
  }

  if (remote.length > 0) {
    failures.push(`the stylesheets import ${remote.join(', ')} at runtime; a face fetched late is not part of the first frame (ADR 0035)`);
  }

  return { ok: failures.length === 0, languages, completeFace, remote, failures };
}

function main() {
  const appDir = process.cwd();
  const result = auditApp({ appDir });
  if (process.argv.includes('--json')) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    console.log(`glyph audit: ${basename(appDir)}`);
    for (const language of result.languages) {
      const detail = language.letters
        ? ` (${language.letters} letters x ${language.variants} weights)`
        : '';
      console.log(`  ${language.tag.padEnd(8)} ${language.status}${detail}`);
    }
    for (const [faceId, served] of Object.entries(result.completeFace)) {
      console.log(`  ${faceId}: served from the complete face: ${served.served || 'nothing'}`);
    }
  }
  for (const failure of result.failures) {
    console.error(`  FAIL ${failure}`);
  }
  process.exit(result.ok ? 0 : 1);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
