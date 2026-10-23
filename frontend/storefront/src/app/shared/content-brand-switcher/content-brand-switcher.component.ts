import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';

import type { ContentBrandSwitcherEntry } from '../../types/content.types';
import { ContentMediaComponent } from '../content-media/content-media.component';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The brand switcher (ADR 0157 Release 4, ADR 0183 D17): a row of the tenant's other brands under
 * the top bar, each a link to that brand's own website.
 *
 * It draws nothing for a brand that offers none, so a single-brand storefront is exactly what it
 * was. A link is a plain `https:` anchor to an address the platform resolved from the target
 * website's verified hostname on this read (and `normalizeHomeDocument` has vetted again); it
 * leaves this storefront, so it opens with `rel="noopener noreferrer"`. A logo that cannot load is
 * replaced by the brand's first letter, since the name is still worth following.
 */
@Component({
  selector: 'app-content-brand-switcher',
  standalone: true,
  imports: [ContentMediaComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'contents' },
  template: `
    @if (entries().length > 0) {
      <nav [attr.aria-label]="'content.brandSwitcher' | t" data-testid="brand-switcher">
        <ul class="no-scrollbar -mx-4 flex gap-3 overflow-x-auto px-4 py-1">
          @for (entry of entries(); track entry.targetBrandId) {
            <li class="shrink-0">
              <a
                class="flex items-center gap-2 rounded-full border border-zinc-700 bg-surface-raised py-1 pl-1 pr-4 text-sm font-semibold text-text-primary"
                [attr.href]="entry.url"
                rel="noopener noreferrer"
                [attr.data-testid]="'brand-switcher-' + entry.targetBrandId"
              >
                <span
                  class="flex h-8 w-8 shrink-0 items-center justify-center overflow-hidden rounded-full bg-surface-raised-2"
                >
                  @if (entry.logo && !logoFailed().has(entry.targetBrandId)) {
                    <app-content-media
                      [media]="entry.logo"
                      sizes="32px"
                      (failed)="markLogoFailed(entry)"
                    />
                  } @else {
                    <span data-testid="brand-switcher-initial" class="text-sm font-semibold">{{
                      initialOf(entry)
                    }}</span>
                  }
                </span>
                <span class="whitespace-nowrap">{{ entry.name }}</span>
              </a>
            </li>
          }
        </ul>
      </nav>
    }
  `,
})
export class ContentBrandSwitcherComponent {
  readonly entries = input<readonly ContentBrandSwitcherEntry[]>([]);

  protected readonly logoFailed = signal<ReadonlySet<string>>(new Set());

  protected initialOf(entry: ContentBrandSwitcherEntry): string {
    return Array.from(entry.name.trim())[0]?.toUpperCase() ?? '';
  }

  protected markLogoFailed(entry: ContentBrandSwitcherEntry): void {
    this.logoFailed.update((failed) => new Set(failed).add(entry.targetBrandId));
  }
}
