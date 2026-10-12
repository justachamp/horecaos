import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';

import { ContentEventsService } from '../../services/content-events.service';
import type { ContentCategoryRef, ContentPopup } from '../../types/content.types';
import { resolveContentLink } from '../../utils/content-links';
import {
  capAllows,
  readShownRecord,
  withinDailyWindow,
  writeShownRecord,
} from '../../utils/popup-schedule';
import { ContentLinkComponent } from '../content-link/content-link.component';
import { ContentMediaComponent } from '../content-media/content-media.component';
import { FocusTrapDirective } from '../focus-trap/focus-trap.directive';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The entry pop-up (ADR 0157, Decision 10): shown once, `showAfterSeconds` after the page opens,
 * if the device says it may be.
 *
 * <h2>The device decides</h2>
 * The document publishes a frequency cap and a daily window, but an anonymous browser has no
 * identity the server could count against, so the cap is enforced *here*: the last show is kept in
 * `localStorage` under a key of item id and revision (editing the item publishes a new revision,
 * which starts with a clean cap), and the window is read off the device's own clock. Eligibility
 * is checked when the page starts waiting and again when the delay is up, because a window can
 * close while the visitor reads the menu. A pop-up kept out by its window is not recorded as shown.
 *
 * Storage may throw (private browsing, a blocked WebView); every access is guarded, and the page
 * is correct without it: the pop-up is then governed by its window and by an in-memory "already
 * shown this visit" flag, so it still appears at most once per visit.
 *
 * <h2>The dialog</h2>
 * `role="dialog"`, `aria-modal`, named by its title; focus moves to the close button and returns
 * to what had it; the close button, a click on the backdrop and Escape all dismiss it and count a
 * dismissal. Its text is written with interpolation only.
 */
@Component({
  selector: 'app-content-popup',
  standalone: true,
  imports: [ContentLinkComponent, ContentMediaComponent, FocusTrapDirective, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'onEscape()' },
  template: `
    @if (open(); as p) {
      <div
        class="fixed inset-0 z-[70] flex items-center justify-center bg-black/60 p-6"
        data-testid="popup-backdrop"
        (click)="onBackdrop($event)"
      >
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="content-popup-title"
          appFocusTrap
          tabindex="-1"
          class="relative w-full max-w-[360px] overflow-hidden rounded-2xl bg-surface-raised text-text-primary shadow-elevated"
          [attr.lang]="p.textLocale || null"
        >
          <button
            type="button"
            data-autofocus
            class="absolute right-2 top-2 z-10 flex h-9 w-9 items-center justify-center rounded-full bg-black/50 text-white"
            [attr.aria-label]="'content.popupClose' | t"
            (click)="dismiss()"
          >
            <svg
              class="h-4 w-4"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
              <path stroke-linecap="round" stroke-linejoin="round" d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
          @if (p.media && !mediaFailed()) {
            <div class="aspect-[3/2] w-full bg-surface-raised-2">
              <app-content-media
                [media]="p.media"
                [eager]="true"
                sizes="(min-width: 430px) 360px, 90vw"
                (failed)="mediaFailed.set(true)"
              />
            </div>
          }
          <div class="space-y-2 p-4">
            <h2 id="content-popup-title" class="pr-8 text-lg font-semibold leading-snug">
              {{ p.title }}
            </h2>
            @if (p.body) {
              <p class="whitespace-pre-line text-sm leading-relaxed text-zinc-300">{{ p.body }}</p>
            }
            @if (target(); as link) {
              <app-content-link
                [target]="link"
                anchorClass="mt-2 block w-full rounded-full bg-brand-accent px-4 py-3 text-center text-sm font-semibold text-white"
                (activated)="onCta(p)"
              >
                {{ p.ctaLabel || ('content.open' | t) }}
              </app-content-link>
            }
          </div>
        </div>
      </div>
    }
  `,
})
export class ContentPopupComponent {
  private readonly events = inject(ContentEventsService);
  /** Revisions already shown during this visit, so a re-read document cannot show one twice. */
  private readonly shown = new Set<string>();

  readonly popup = input<ContentPopup | null>(null);
  readonly categories = input<readonly ContentCategoryRef[]>([]);

  protected readonly open = signal<ContentPopup | null>(null);
  protected readonly mediaFailed = signal(false);
  protected readonly target = computed(() => {
    const p = this.open();
    return p ? resolveContentLink(p.link, this.categories()) : null;
  });

  constructor() {
    effect((onCleanup) => {
      const popup = this.popup();
      if (!popup || this.shown.has(this.keyOf(popup)) || !this.eligible(popup, new Date())) {
        return;
      }
      const timer = setTimeout(() => this.show(popup), popup.showAfterSeconds * 1000);
      onCleanup(() => clearTimeout(timer));
    });
  }

  protected onEscape(): void {
    if (this.open()) {
      this.dismiss();
    }
  }

  protected onBackdrop(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.dismiss();
    }
  }

  protected dismiss(): void {
    const popup = this.open();
    if (popup) {
      this.events.dismissal(popup.id, popup.revisionNo);
      this.open.set(null);
    }
  }

  protected onCta(popup: ContentPopup): void {
    this.events.click(popup.id, popup.revisionNo);
    this.open.set(null);
  }

  private keyOf(popup: ContentPopup): string {
    return `${popup.id}#${popup.revisionNo}`;
  }

  private eligible(popup: ContentPopup, now: Date): boolean {
    return (
      withinDailyWindow(popup.daily, now) &&
      capAllows(popup.cap, readShownRecord(popup.id, popup.revisionNo), now)
    );
  }

  private show(popup: ContentPopup): void {
    const now = new Date();
    if (!this.eligible(popup, now)) {
      return;
    }
    this.shown.add(this.keyOf(popup));
    writeShownRecord(popup.id, popup.revisionNo, now);
    this.events.impression(popup.id, popup.revisionNo);
    this.mediaFailed.set(false);
    this.open.set(popup);
  }
}
