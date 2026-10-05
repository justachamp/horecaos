/**
 * Throwaway app trees for the i18n tool tests: `app({ 'relative/path': 'text' })` writes the files under a
 * fresh temp directory and returns its path. Not a test file (the runner only picks up `*.test.mjs`).
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export function app(files) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-tools-'));
  for (const [relative, text] of Object.entries(files)) {
    const file = path.join(dir, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text);
  }
  return dir;
}

export function read(dir, relative) {
  return fs.readFileSync(path.join(dir, relative), 'utf8');
}

/** A small `message-areas.ts`: namespaces `shell` (core), `orders`, `kitchen`; `orders.status` is core. */
export const AREA_TABLE = `
export const MESSAGE_AREAS = ['core', 'orders', 'kitchen'] as const;
export type MessageArea = (typeof MESSAGE_AREAS)[number];
export const CORE_AREA: MessageArea = 'core';
const AREA_BY_NAMESPACE: Readonly<Record<string, MessageArea>> = {
  shell: 'core',
  orders: 'orders',
  kitchen: 'kitchen',
};
const AREA_BY_PREFIX: Readonly<Record<string, MessageArea>> = {
  'orders.status': 'core',
};
export function areaOfKey(key: string): MessageArea | undefined {
  let prefix = key;
  for (;;) {
    const area = AREA_BY_PREFIX[prefix];
    if (area) {
      return area;
    }
    const dot = prefix.lastIndexOf('.');
    if (dot < 0) {
      break;
    }
    prefix = prefix.slice(0, dot);
  }
  return AREA_BY_NAMESPACE[key.split('.', 1)[0] ?? ''];
}
export function namespacesOfArea(area: MessageArea): readonly string[] {
  return Object.entries(AREA_BY_NAMESPACE).filter(([, a]) => a === area).map(([n]) => n);
}
export function prefixesOfArea(area: MessageArea): readonly string[] {
  return Object.entries(AREA_BY_PREFIX).filter(([, a]) => a === area).map(([p]) => p);
}
`;
