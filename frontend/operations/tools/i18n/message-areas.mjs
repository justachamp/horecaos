/**
 * Loads `src/app/core/i18n/message-areas.ts` -- the one table that says which area a key lives in -- for
 * the node tooling, by transpiling it. The file has no imports on purpose, so the application and the
 * tools read the same code and cannot drift apart.
 */
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';

const require = createRequire(import.meta.url);
const ts = require('typescript');

export const AREA_TABLE_FILE = 'src/app/core/i18n/message-areas.ts';

/** @returns {Promise<{ MESSAGE_AREAS: readonly string[], CORE_AREA: string, areaOfKey: (key: string) => string | undefined }>} */
export async function loadMessageAreas(appDir) {
  const file = path.join(appDir, AREA_TABLE_FILE);
  const { outputText } = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    fileName: file,
  });
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`);
}

/** `ordersEn` for ('orders', 'en'), `settingsUzLatn` for ('settings', 'uz-latn'). */
export function constantName(area, suffix) {
  const locale = { en: 'En', ru: 'Ru', 'uz-latn': 'UzLatn' }[suffix];
  if (!locale) {
    throw new Error(`unknown locale suffix '${suffix}'`);
  }
  return `${area}${locale}`;
}

export const LOCALE_SUFFIXES = ['en', 'ru', 'uz-latn'];
