import { afterNextRender, Directive, ElementRef, OnDestroy, inject } from '@angular/core';

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), ' +
  'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Keeps keyboard focus inside a modal dialog while it is open, and gives it back afterwards.
 *
 * On creation it remembers what had focus (the control that opened the dialog) and moves focus in:
 * to the element marked `data-autofocus` if there is one, otherwise to the first control, otherwise
 * to the dialog itself. Tab and Shift+Tab wrap at the ends so focus cannot wander onto the page
 * behind. When the dialog is destroyed focus returns to the opener if it is still on the page.
 *
 * The dialog must carry `role="dialog"` and `aria-modal="true"` itself; this only does the focus.
 */
@Directive({
  selector: '[appFocusTrap]',
  host: { '(keydown)': 'onKeydown($event)' },
})
export class FocusTrapDirective implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly opener = this.host.ownerDocument.activeElement as HTMLElement | null;

  constructor() {
    afterNextRender(() => {
      const target =
        this.host.querySelector<HTMLElement>('[data-autofocus]') ??
        this.focusables()[0] ??
        this.host;
      target.focus();
    });
  }

  ngOnDestroy(): void {
    if (this.opener?.isConnected) {
      this.opener.focus();
    }
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Tab') {
      return;
    }
    const items = this.focusables();
    if (items.length === 0) {
      event.preventDefault();
      this.host.focus();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = this.host.ownerDocument.activeElement;
    const outside = !this.host.contains(active);
    if (event.shiftKey && (active === first || outside)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (active === last || outside)) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusables(): HTMLElement[] {
    return Array.from(this.host.querySelectorAll<HTMLElement>(FOCUSABLE));
  }
}
