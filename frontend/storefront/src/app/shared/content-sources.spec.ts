import en from '../../i18n/en.json';
import ru from '../../i18n/ru.json';
import uz from '../../i18n/uz.json';

/**
 * The build-time guard ADR 0157 requires: content is text a brand typed, so no file that draws it
 * may ever turn that text into markup.
 *
 * It reads the sources of every content file and fails if one mentions `innerHTML` (the property
 * or the Angular binding), `outerHTML`, `insertAdjacentHTML`, `document.write`,
 * `bypassSecurityTrust*`, `DomSanitizer` or the `Safe*` types. Text reaches the page by
 * interpolation only, which Angular escapes; an `href` only after the scheme rule has been
 * re-applied in the client (`utils/safe-url.ts`).
 *
 * It also checks that every `content.*` translation key the sources mention exists in all three
 * language files, since `TranslateService.get` falls back to the raw key without a sound.
 *
 * This runs under Vitest's Node runner: the app's tsconfig carries no Node types, so `node:fs` is
 * reached through a dynamic import and typed here.
 */

interface NodeFs {
  readFileSync(path: string, encoding: 'utf8'): string;
  readdirSync(
    path: string,
    options: { withFileTypes: true },
  ): { name: string; isDirectory(): boolean }[];
}

const FORBIDDEN: readonly RegExp[] = [
  /innerHTML/i,
  /outerHTML/i,
  /insertAdjacentHTML/i,
  /document\s*\.\s*write/i,
  /bypassSecurityTrust/i,
  /DomSanitizer/i,
  /\bSafeHtml\b/,
  /\bSafeUrl\b/,
  /\bSafeResourceUrl\b/,
];

/** What a source file contains that content code must never contain. */
function violations(source: string): string[] {
  return FORBIDDEN.filter((pattern) => pattern.test(source)).map((pattern) => pattern.source);
}

async function loadFs(): Promise<NodeFs> {
  const specifier = 'node:fs';
  return (await import(/* @vite-ignore */ specifier)) as NodeFs;
}

const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
const APP = `${root}/src/app`;

/** Every file, below `directory`, that is source and not a spec. */
function sourcesBelow(fs: NodeFs, directory: string): string[] {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = `${directory}/${entry.name}`;
    if (entry.isDirectory()) {
      return sourcesBelow(fs, path);
    }
    return /\.(ts|html|scss)$/.test(entry.name) && !entry.name.endsWith('.spec.ts') ? [path] : [];
  });
}

/** Files directly in `folder` whose name matches, specs excluded. */
function flat(fs: NodeFs, folder: string, pattern: RegExp): string[] {
  return fs
    .readdirSync(`${APP}/${folder}`, { withFileTypes: true })
    .filter((entry) => !entry.isDirectory() && pattern.test(entry.name))
    .filter((entry) => !entry.name.endsWith('.spec.ts'))
    .map((entry) => `${APP}/${folder}/${entry.name}`);
}

/**
 * The content files: the `shared/content-*` components and the `pages/content-*` screens, the
 * focus trap they use, every content service, util and type, and the home page that hosts them.
 */
async function contentSources(): Promise<string[]> {
  const fs = await loadFs();
  const folders = (parent: string, prefix: string) =>
    fs
      .readdirSync(`${APP}/${parent}`, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && entry.name.startsWith(prefix))
      .flatMap((entry) => sourcesBelow(fs, `${APP}/${parent}/${entry.name}`));
  return [
    ...folders('shared', 'content-'),
    ...folders('pages', 'content-'),
    ...sourcesBelow(fs, `${APP}/shared/focus-trap`),
    ...flat(fs, 'services', /^(content|editorial|reduced-motion).*\.ts$/),
    ...flat(fs, 'utils', /^(content|editorial|popup-schedule|safe-url|vacancy).*\.ts$/),
    ...flat(fs, 'types', /^(content|editorial).*\.ts$/),
    ...flat(fs, '.', /^app\.routes\.ts$/),
    ...flat(fs, 'pages/home', /^home\.component\.(ts|html)$/),
  ];
}

describe('the guard itself', () => {
  it.each([
    ['[innerHTML]="body"'],
    ['el.innerHTML = x'],
    ['el.outerHTML = x'],
    ["el.insertAdjacentHTML('beforeend', x)"],
    ['document.write(x)'],
    ['document . write(x)'],
    ['this.sanitizer.bypassSecurityTrustHtml(x)'],
    ['bypassSecurityTrustResourceUrl(x)'],
    ["import { DomSanitizer } from '@angular/platform-browser';"],
    ['const html: SafeHtml = x;'],
    ['const url: SafeUrl = x;'],
    ['const url: SafeResourceUrl = x;'],
  ])('finds %s', (text) => {
    expect(violations(text)).not.toEqual([]);
  });

  it.each([
    ['<p>{{ item().title }}</p>'],
    ['<a [href]="link.href" rel="noopener noreferrer">x</a>'],
    ['const safe = httpsUrlOrNull(value);'],
    ['element.textContent = text;'],
  ])('passes %s', (text) => {
    expect(violations(text)).toEqual([]);
  });
});

describe('content sources never turn content into markup', () => {
  it('finds the content files it is meant to guard', async () => {
    const files = await contentSources();
    const names = files.map((f) => f.slice(APP.length + 1));

    expect(names).toEqual(
      expect.arrayContaining([
        'shared/content-media/content-media.component.ts',
        'shared/content-tile/content-tile.component.ts',
        'shared/content-carousel/content-carousel.component.ts',
        'shared/content-promo-grid/content-promo-grid.component.ts',
        'shared/content-groups/content-groups.component.ts',
        'shared/content-stories/content-stories.component.ts',
        'shared/content-stories/story-viewer.component.ts',
        'shared/content-popup/content-popup.component.ts',
        'shared/content-blocks/content-blocks.component.ts',
        'shared/content-blocks/content-inlines.component.ts',
        'shared/content-entry/content-entry.component.ts',
        'pages/content-list/content-list.component.ts',
        'pages/content-list/content-list.component.html',
        'pages/content-detail/content-detail.component.ts',
        'pages/content-detail/content-detail.component.html',
        'services/content.service.ts',
        'services/content-events.service.ts',
        'services/editorial.service.ts',
        'utils/content-document.ts',
        'utils/editorial-document.ts',
        'utils/editorial-kinds.ts',
        'utils/vacancy-contact.ts',
        'utils/safe-url.ts',
        'types/editorial.types.ts',
        'pages/home/home.component.html',
      ]),
    );
  });

  it('has no forbidden text in any of them', async () => {
    const fs = await loadFs();
    const found: string[] = [];
    for (const file of await contentSources()) {
      const bad = violations(fs.readFileSync(file, 'utf8'));
      if (bad.length > 0) {
        found.push(`${file.slice(APP.length + 1)}: ${bad.join(', ')}`);
      }
    }

    expect(found).toEqual([]);
  });
});

describe('content translation keys exist in every language', () => {
  const LOCALES: Record<string, Record<string, unknown>> = { en, ru, uz };

  function lookup(dictionary: Record<string, unknown>, key: string): unknown {
    return key
      .split('.')
      .reduce<unknown>(
        (node, part) =>
          node !== null && typeof node === 'object'
            ? (node as Record<string, unknown>)[part]
            : undefined,
        dictionary,
      );
  }

  it('every content.* key a content source mentions is a string in en, ru and uz', async () => {
    const fs = await loadFs();
    const keys = new Set<string>();
    for (const file of await contentSources()) {
      for (const match of fs.readFileSync(file, 'utf8').matchAll(/'(content\.[A-Za-z0-9.]+)'/g)) {
        keys.add(match[1]);
      }
    }

    expect(keys.size).toBeGreaterThan(0);
    const missing = [...keys].flatMap((key) =>
      Object.entries(LOCALES)
        .filter(([, dictionary]) => typeof lookup(dictionary, key) !== 'string')
        .map(([locale]) => `${locale}: ${key}`),
    );
    expect(missing).toEqual([]);
  });
});
