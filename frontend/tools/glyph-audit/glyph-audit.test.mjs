/* Proves the glyph audit can fail.
 *
 * An audit that reports "covered" proves nothing until it has been seen to report "missing": the
 * repository's own history is full of guards that checked the adjacent quantity. So these feed it
 * stylesheets and fonts whose answer is known -- a Georgian alphabet against a face that has no
 * Georgian, a rule that claims letters its file does not draw, a letter no rule claims, an exception
 * that no longer applies -- and check the verdicts, then check the real app is clean.
 *
 * Run from the operations app (its `lint:rules` does): the fonts it reads are the ones installed in
 * that app's own node_modules. From another app the font-reading cases skip, because the files
 * they need are not there; the audit itself still runs over that app.
 */
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import { auditApp, describeCodePoints, expand } from './glyph-audit.mjs';
import { parseUnicodeRange } from './css-faces.mjs';
import { codePointsInFont } from './font-cmap.mjs';

const APP = process.cwd();
const PLEX = join(APP, 'node_modules/@ibm/plex-sans/fonts/split/woff2');
const PLEX_COMPLETE = join(APP, 'node_modules/@ibm/plex-sans/fonts/complete/woff2/IBMPlexSans-Regular.woff2');
const HAS_PLEX = existsSync(PLEX_COMPLETE);
const CONFIG = JSON.parse(readFileSync(new URL('./alphabets.json', import.meta.url), 'utf8'));

/** A throwaway app directory with one stylesheet and the given rules. */
function appWith(css, configure = (config) => config) {
  const dir = mkdtempSync(join(tmpdir(), 'glyph-audit-'));
  writeFileSync(join(dir, 'styles.css'), css);
  const config = structuredClone(CONFIG);
  config.apps = { probe: { stylesheets: ['styles.css'], completeFaceAllowed: {} } };
  return { dir, config: configure(config) };
}

function rule(file, range) {
  return `@font-face { font-family: "IBM Plex Sans"; font-weight: 400; font-style: normal;
    src: url("${file}") format("woff2");${range ? ` unicode-range: ${range};` : ''} }`;
}

test('unicode-range is read the way a browser reads it', () => {
  assert.deepEqual(parseUnicodeRange('U+0400-045F, U+0490, U+04A?'), [
    [0x400, 0x45f],
    [0x490, 0x490],
    [0x4a0, 0x4af],
  ]);
  assert.equal(describeCodePoints(expand(['U+0041-0043', 'U+0045'])), 'U+0041-0043 U+0045');
});

test('a font file is read for what it draws: Kazakh is in Plex, Georgian is not', { skip: !HAS_PLEX }, () => {
  const drawn = codePointsInFont(readFileSync(PLEX_COMPLETE));
  for (const kazakh of expand(CONFIG.alphabets.kk.ranges)) {
    assert.ok(drawn.has(kazakh), `U+${kazakh.toString(16)} is in the Regular face`);
  }
  assert.ok(drawn.has(0x2bb) && drawn.has(0x2bc), 'the Uzbek modifier letters are in the complete face');
  assert.ok(!drawn.has(0x10d0), 'Georgian ა is NOT in Plex: this is the case that proves the audit can fail');
});

test('the split subsets do not contain the Uzbek modifier letters, which is why they ride the complete face', { skip: !HAS_PLEX }, () => {
  for (const subset of ['Latin1', 'Latin2', 'Latin3', 'Cyrillic', 'Pi']) {
    const drawn = codePointsInFont(readFileSync(join(PLEX, `IBMPlexSans-Regular-${subset}.woff2`)));
    assert.ok(!drawn.has(0x2bb) && !drawn.has(0x2bc), `${subset} has no U+02BB or U+02BC`);
  }
});

test('the app this runs in is clean', () => {
  const result = auditApp({ appDir: APP });
  assert.deepEqual(result.failures, []);
  assert.ok(result.languages.some((language) => language.tag === 'kk' && language.status === 'covered'));
  assert.ok(result.languages.some((language) => language.tag === 'ka' && language.status === 'declared, no face shipped'));
});

test('a language needing letters the shipped face lacks fails, naming them', { skip: !HAS_PLEX }, () => {
  const { dir, config } = appWith(rule(PLEX_COMPLETE, null), (config) => {
    config.alphabets.ka.face = 'ibm-plex-sans'; // pretend Georgian were claimed by Plex
    return config;
  });
  const result = auditApp({ appDir: dir, appName: 'probe', config });
  assert.equal(result.ok, false);
  assert.match(result.failures.join('\n'), /ka: IBM Plex Sans 400 normal cannot draw U\+10D0-10F0/);
});

test('a rule that claims letters its file does not draw fails', { skip: !HAS_PLEX }, () => {
  const { dir, config } = appWith(
    // The Latin1 subset claiming Cyrillic: the claim is not the glyph.
    rule(join(PLEX, 'IBMPlexSans-Regular-Latin1.woff2'), 'U+0000-052F'),
  );
  const result = auditApp({ appDir: dir, appName: 'probe', config });
  assert.equal(result.ok, false);
  assert.match(result.failures.join('\n'), /ru: .*has no glyph for it/);
});

test('a letter no rule claims fails', { skip: !HAS_PLEX }, () => {
  const { dir, config } = appWith(rule(join(PLEX, 'IBMPlexSans-Regular-Latin1.woff2'), 'U+0000-00FF'));
  const result = auditApp({ appDir: dir, appName: 'probe', config });
  assert.equal(result.ok, false);
  assert.match(result.failures.join('\n'), /kk: .*no @font-face rule claims it/);
});

test('the complete face may serve only the letters an app names on purpose, and the list may not go stale', { skip: !HAS_PLEX }, () => {
  const css = rule(PLEX_COMPLETE, null);

  const noneNamed = appWith(css);
  assert.match(
    auditApp({ appDir: noneNamed.dir, appName: 'probe', config: noneNamed.config }).failures.join('\n'),
    /served from the complete face and no exception is declared/,
  );

  const unnamed = appWith(css, (config) => {
    config.apps.probe.completeFaceAllowed = { 'ibm-plex-sans': ['U+0041'] };
    return config;
  });
  assert.match(
    auditApp({ appDir: unnamed.dir, appName: 'probe', config: unnamed.config }).failures.join('\n'),
    /would be fetched from the COMPLETE face/,
  );

  const named = appWith(css, (config) => {
    config.apps.probe.completeFaceAllowed = { 'ibm-plex-sans': ['U+0041-005A', 'U+0061-007A', 'U+0401', 'U+0410-044F', 'U+0451', 'U+02BB-02BC', 'U+2018-2019', 'U+0406', 'U+0456', 'U+0492-0493', 'U+049A-049B', 'U+04A2-04A3', 'U+04AE-04B1', 'U+04BA-04BB', 'U+04D8-04D9', 'U+04E8-04E9'] };
    return config;
  });
  assert.deepEqual(auditApp({ appDir: named.dir, appName: 'probe', config: named.config }).failures, []);

  const stale = appWith(css, (config) => {
    config.apps.probe.completeFaceAllowed = { 'ibm-plex-sans': ['U+0041-005A', 'U+0061-007A', 'U+0401', 'U+0410-044F', 'U+0451', 'U+02BB-02BC', 'U+2018-2019', 'U+0406', 'U+0456', 'U+0492-0493', 'U+049A-049B', 'U+04A2-04A3', 'U+04AE-04B1', 'U+04BA-04BB', 'U+04D8-04D9', 'U+04E8-04E9', 'U+0030'] };
    return config;
  });
  assert.match(
    auditApp({ appDir: stale.dir, appName: 'probe', config: stale.config }).failures.join('\n'),
    /U\+0030 is listed under completeFaceAllowed but is no longer served/,
  );
});

test('a stylesheet fetched at runtime fails: a face that arrives late is not in the first frame', { skip: !HAS_PLEX }, () => {
  const { dir, config } = appWith(
    `@import url("https://fonts.googleapis.com/css2?family=Urbanist");\n${rule(PLEX_COMPLETE, null)}`,
    (config) => {
      config.apps.probe.completeFaceAllowed = {};
      return config;
    },
  );
  const result = auditApp({ appDir: dir, appName: 'probe', config });
  assert.match(result.failures.join('\n'), /import https:\/\/fonts\.googleapis\.com/);
});
