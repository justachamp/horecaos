import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import type { ContentPopup } from '../../types/content.types';
import { resolveContentLink } from '../../utils/content-link';
import { mediaAspectRatio, mediaPlan } from '../../utils/content-media';
import { languageTag } from '../../utils/content-text';
import { trapTab } from '../../utils/focus-trap';
import { mayShowPopup, recordPopupShown } from '../../utils/popup-schedule';
import { ContentLinkComponent } from '../content-link/content-link.component';
import { ContentMediaComponent } from '../content-media/content-media.component';
import { IconComponent } from '../icon/icon.component';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The entry pop-up (ADR 0157, Decision 10): shown once, a few seconds after the page opens, if the
 * device-local rules allow it.
 *
 * <h2>The rules are the device's</h2>
 *
 * The frequency cap and the daily window are published in the document as a hint; this component
 * enforces them against the device's own clock and `localStorage` (`utils/popup-schedule`), under a
 * key of the item and its revision so that editing the item starts the cap over. Storage that
 * throws changes nothing about whether the page works: the pop-up is then shown by its other rules,
 * and not twice in one page session. The rules are checked when the page asks and again when the
 * delay is up, since the window may have closed meanwhile.
 *
 * <h2>The dialog</h2>
 *
 * Dismissible always: a close button, a tap on the backdrop, Escape -- each reports a DISMISSAL, once.
 * It is a modal dialog named by its title, focus goes to the close button, Tab stays inside, and
 * focus returns to where it was. Following the call to action reports a CLICK and closes without
 * calling it a dismissal. The call to action exists only when the link goes somewhere the client
 * allows.
 */
@Component({
  selector: 'app-content-popup',
  standalone: true,
  imports: [ContentLinkComponent, ContentMediaComponent, IconComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'onEscape()' },
  templateUrl: './content-popup.component.html',
  styleUrl: './content-popup.component.scss',
})
export class ContentPopupComponent {
  private readonly events = inject(ContentEventsService);
  private readonly reducedMotion = inject(ReducedMotionService);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  readonly popup = input<ContentPopup | null>(null);

  /** The pop-up on screen; null while nothing is shown. */
  protected readonly shown = signal<ContentPopup | null>(null);
  private readonly pictureFailed = signal(false);
  private readonly dialog = viewChild<ElementRef<HTMLElement>>('dialog');
  private readonly closeButton = viewChild<ElementRef<HTMLButtonElement>>('closeButton');
  private restoreTo: HTMLElement | null = null;

  protected readonly plan = computed(() => {
    const media = this.shown()?.media;
    return media && !this.pictureFailed() ? mediaPlan(media, this.reducedMotion.reduced()) : null;
  });
  protected readonly aspect = computed(() => {
    const media = this.shown()?.media;
    return media ? mediaAspectRatio(media) : null;
  });
  protected readonly lang = computed(() => languageTag(this.shown()?.textLocale));
  protected readonly hasLink = computed(() => resolveContentLink(this.shown()?.link) !== null);

  constructor() {
    effect((onCleanup) => {
      const popup = this.popup();
      if (popup === null || !mayShowPopup(popup, new Date())) {
        return;
      }
      const timer = setTimeout(() => this.show(popup), popup.showAfterSeconds * 1000);
      onCleanup(() => clearTimeout(timer));
    });
  }

  protected titleId(popup: ContentPopup): string {
    return `content-popup-title-${popup.id}`;
  }

  private show(popup: ContentPopup): void {
    const now = new Date();
    // The delay may have outlasted the daily window, or another tab may have shown it meanwhile.
    if (this.shown() !== null || !mayShowPopup(popup, now)) {
      return;
    }
    recordPopupShown(popup, now);
    this.restoreTo = this.document.activeElement as HTMLElement | null;
    this.pictureFailed.set(false);
    this.shown.set(popup);
    this.events.report(popup.id, popup.revisionNo, 'IMPRESSION');
    afterNextRender(() => this.closeButton()?.nativeElement.focus(), { injector: this.injector });
  }

  protected dismiss(): void {
    const popup = this.shown();
    if (popup === null) {
      return;
    }
    this.events.report(popup.id, popup.revisionNo, 'DISMISSAL');
    this.hide();
  }

  protected followedLink(): void {
    const popup = this.shown();
    if (popup === null) {
      return;
    }
    this.events.report(popup.id, popup.revisionNo, 'CLICK');
    this.hide();
  }

  protected onBackdrop(event: Event): void {
    if (event.target === event.currentTarget) {
      this.dismiss();
    }
  }

  protected onEscape(): void {
    this.dismiss();
  }

  protected onKeydown(event: KeyboardEvent): void {
    const dialog = this.dialog();
    if (dialog) {
      trapTab(event, dialog.nativeElement);
    }
  }

  protected pictureError(): void {
    this.pictureFailed.set(true);
  }

  private hide(): void {
    this.shown.set(null);
    if (this.restoreTo?.isConnected) {
      this.restoreTo.focus();
    }
    this.restoreTo = null;
  }
}
