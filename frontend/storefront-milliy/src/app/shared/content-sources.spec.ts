/**
 * The build-time guard ADR 0157 requires: content is data a brand typed, so no file that draws it
 * may ever turn text into markup.
 *
 * Reads the sources of every content file and fails if one mentions `innerHTML` (the property or
 * the Angular binding), `outerHTML`, `insertAdjacentHTML`, `document.write`, `bypassSecurityTrust*`
 * or `DomSanitizer`. Text reaches the page by interpolation only, which Angular escapes; an `href`
 * only after the scheme rule has been re-applied in the client.
 *
 * `IconComponent` is deliberately not on the list: it is the one place that trusts markup, because
 * its markup is a compile-time constant checked into this repository and never content.
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

/**
 * The content files: the `shared/content-*` and `shared/editorial-*` components, the editorial
 * pages, and every content or editorial service, util and type.
 */
async function contentSources(): Promise<string[]> {
  const fs = await loadFs();
  const shared = fs
    .readdirSync(`${APP}/shared`, { withFileTypes: true })
    .filter(
      (entry) =>
        entry.isDirectory() &&
        (entry.name.startsWith('content-') || entry.name.startsWith('editorial-')),
    )
    .flatMap((entry) => sourcesBelow(fs, `${APP}/shared/${entry.name}`));
  const flat = (folder: string, pattern: RegExp) =>
    fs
      .readdirSync(`${APP}/${folder}`, { withFileTypes: true })
      .filter((entry) => !entry.isDirectory() && pattern.test(entry.name))
      .filter((entry) => !entry.name.endsWith('.spec.ts'))
      .map((entry) => `${APP}/${folder}/${entry.name}`);
  return [
    ...shared,
    ...sourcesBelow(fs, `${APP}/pages/editorial`),
    ...flat('services', /^(content|editorial|reduced-motion).*\.ts$/),
    ...flat('utils', /^(content|editorial|popup-schedule|focus-trap|wire).*\.ts$/),
    ...flat('types', /^(content|editorial).*\.ts$/),
  ];
}

describe('the guard itself', () => {
  it.each([
    ['[innerHTML]="body"'],
    ['el.innerHTML = x'],
    ['el.outerHTML = x'],
    ['el.insertAdjacentHTML("beforeend", x)'],
    ['document.write(x)'],
    ['sanitizer.bypassSecurityTrustHtml(x)'],
    ['bypassSecurityTrustResourceUrl'],
    ['constructor(private s: DomSanitizer) {}'],
    ['x: SafeHtml'],
  ])('flags %s', (source) => {
    expect(violations(source)).not.toEqual([]);
  });

  it.each([['{{ item().title }}'], ['[src]="plan.src"'], ['const text = el.textContent;']])(
    'lets %s through',
    (source) => {
      expect(violations(source)).toEqual([]);
    },
  );
});

describe('content files never turn text into markup (ADR 0157)', () => {
  it('finds the content files it is meant to guard', async () => {
    const files = (await contentSources()).map((path) => path.slice(APP.length + 1));

    expect(files).toEqual(
      expect.arrayContaining([
        'shared/content-carousel/content-carousel.component.ts',
        'shared/content-popup/content-popup.component.ts',
        'shared/content-blocks/content-blocks.component.ts',
        'shared/editorial-links/editorial-links.component.ts',
        'pages/editorial/editorial-list.component.ts',
        'pages/editorial/editorial-detail.component.ts',
        'services/content.service.ts',
        'services/editorial.service.ts',
        'utils/content-document.ts',
        'utils/editorial-document.ts',
        'utils/content-href.ts',
        'utils/popup-schedule.ts',
        'types/content.types.ts',
      ]),
    );
  });

  it('has no unsafe HTML, no sanitizer bypass and no DomSanitizer in any of them', async () => {
    const fs = await loadFs();
    const offenders = (await contentSources())
      .map((path) => ({ path, found: violations(fs.readFileSync(path, 'utf8')) }))
      .filter((entry) => entry.found.length > 0)
      .map((entry) => `${entry.path.slice(APP.length + 1)}: ${entry.found.join(', ')}`);

    expect(offenders).toEqual([]);
  });
});
