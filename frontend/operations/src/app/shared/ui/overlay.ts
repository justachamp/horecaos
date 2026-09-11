import { DestroyRef, Signal, inject } from '@angular/core';

/**
 * Escape, a focus trap and focus restore for one overlay — the three things
 * every dialog in this application is missing (ADR 0101).
 *
 * Fifteen templates carry `aria-modal="true"` today and not one of them closes
 * on Escape or holds focus: the whole console has a single `keydown` handler,
 * `shell.ts`'s F2. `aria-modal` is a promise to a screen reader that the rest
 * of the page is inert, and a dialog a keyboard user can Tab straight out of
 * breaks that promise silently — the reader keeps announcing the dialog while
 * the caret is somewhere behind it.
 *
 * **Hand-rolled rather than `@angular/cdk`'s `FocusTrap`, deliberately and
 * reversibly.** The console has no CDK today (`package.json` carries
 * `@angular/*`, `rxjs` and two font packages, nothing else), and the wave that
 * genuinely cannot be built without it — a virtualised ten-thousand-row grid —
 * is the one that should pay for it. Everything trap-shaped lives in this one
 * class precisely so that decision stays cheap: when the CDK lands, this body
 * is replaced and `q-modal`, `q-drawer` and `q-confirm-dialog` do not change.
 *
 * **Not a directive and not a base class.** A host component owns its own
 * panel element and its own close semantics; this takes both as parameters and
 * has no opinion about markup. That is what lets a drawer, a modal and a
 * confirm dialog share it without sharing a template.
 */
export interface OverlayBehaviourOptions {
  /** The overlay panel. Tracked as a signal because `viewChild` resolves after the first render. */
  readonly panel: Signal<HTMLElement | undefined>;
  /** Called on Escape, and on nothing else. The host decides what closing means. */
  readonly onEscape: () => void;
  /**
   * Whether Escape closes at all. A confirmation whose refusal would lose work
   * can pass `false`; every overlay in this console passes the default.
   */
  readonly closeOnEscape?: () => boolean;
}

/**
 * `:where()`-free on purpose — this list is queried against a panel's subtree,
 * not used as a stylesheet selector, and `querySelectorAll` has no support
 * question to answer.
 */
const TABBABLE = 'a[href], button, input, select, textarea, [tabindex]';

/**
 * Tabbable descendants of `root`, in document order.
 *
 * "Tabbable", not "focusable": `tabindex="-1"` is focusable by script and is
 * exactly what a panel itself carries, so including it would make the trap wrap
 * onto the panel instead of onto the first control. A zero-sized client rect
 * stands in for "hidden" — `offsetParent` lies about `position: fixed`
 * elements, which every backdrop in this console is.
 */
export function tabbableWithin(root: HTMLElement): readonly HTMLElement[] {
  // Whether this environment lays anything out at all. jsdom reports zero
  // boxes for every element, so "no box" cannot mean "hidden" there — asking
  // the question of the panel itself is what tells the two apart. Without this
  // check the geometry filter removes *everything* under test, and the trap
  // silently degrades to focusing the panel: the failure looks like a broken
  // test rather than a broken environment assumption, which is exactly how it
  // first showed up here.
  const laidOut = root.getClientRects().length > 0 || root.getBoundingClientRect().width > 0;

  return [...root.querySelectorAll<HTMLElement>(TABBABLE)].filter((element) => {
    if (element.hasAttribute('disabled') || element.getAttribute('tabindex') === '-1') {
      return false;
    }
    if (element.hasAttribute('hidden') || element.getAttribute('aria-hidden') === 'true') {
      return false;
    }
    return laidOut ? element.getClientRects().length > 0 : true;
  });
}

/**
 * Every currently-open overlay, in the order it was constructed — i.e. the
 * order it was opened, since `OverlayBehaviour` is built as a field
 * initializer on the host component. The last entry is the topmost one.
 *
 * Exists only to answer "is this the topmost overlay?" for Escape. A
 * `keydown` listener on `document` does not carry that answer on its own:
 * every open overlay's listener is on the same node, so `stopPropagation()`
 * (and even `stopImmediatePropagation()`, which only stops *later*-registered
 * listeners on that node — and the earlier-opened, outer overlay always
 * registers first) cannot by itself make the *later*-opened, inner overlay
 * the one that wins. A small shared stack makes it explicit instead of
 * relying on registration order.
 */
const openOverlays: OverlayBehaviour[] = [];

let domIdSequence = 0;

/**
 * A document-unique `id`, for the one job an Angular template cannot do
 * without one: pointing `aria-labelledby` at a heading this component renders.
 *
 * Not `Ids.newId()` and not `crypto.randomUUID()` — this value never leaves the
 * DOM, is never stored, and is never compared across two page loads, so a
 * process-local counter is the whole requirement. It is also short enough to
 * read in a devtools inspector, which a UUID is not.
 */
export function nextDomId(prefix: string): string {
  domIdSequence += 1;
  return `q-${prefix}-${domIdSequence}`;
}

export class OverlayBehaviour {
  private readonly invoker: HTMLElement | null;

  constructor(private readonly options: OverlayBehaviourOptions) {
    const destroyRef = inject(DestroyRef);

    // The invoker is read in the constructor, before anything touches the
    // panel, because moving focus into the panel is what destroys the evidence
    // of where focus came from. `document.activeElement` is `<body>` when
    // nothing was focused, and restoring to `<body>` is the same as restoring
    // to nothing.
    const active = document.activeElement;
    this.invoker = active instanceof HTMLElement && active !== document.body ? active : null;

    const onKeydown = (event: KeyboardEvent): void => this.onKeydown(event);
    document.addEventListener('keydown', onKeydown, true);
    openOverlays.push(this);

    destroyRef.onDestroy(() => {
      document.removeEventListener('keydown', onKeydown, true);
      const index = openOverlays.indexOf(this);
      if (index !== -1) {
        openOverlays.splice(index, 1);
      }
      // Only if it is still in the document: an overlay opened from a row that
      // the mutation it performed has just removed has nowhere to go back to,
      // and calling `focus()` on a detached node silently focuses `<body>`,
      // which loses the caret without saying so.
      const invoker = this.invoker;
      if (invoker !== null && invoker.isConnected) {
        invoker.focus();
      }
    });
  }

  /**
   * Moves focus into the panel. Called from the host's `ngAfterViewInit`, not
   * from an `effect`, and the difference is not stylistic: `viewChild` resolves
   * during the view's own creation, so an effect that reads it has to be
   * *re-run* after the first pass to see anything — which makes "did the caret
   * move?" depend on how many change-detection passes happened to occur.
   * `ngAfterViewInit` runs exactly once, after the view exists, by contract.
   */
  focusInitial(): void {
    const panel = this.options.panel();
    if (panel === undefined) {
      return;
    }
    const first = tabbableWithin(panel)[0];
    (first ?? panel).focus();
  }

  private onKeydown(event: KeyboardEvent): void {
    const panel = this.options.panel();
    if (panel === undefined || event.defaultPrevented) {
      return;
    }

    if (event.key === 'Escape') {
      // Only the topmost overlay answers Escape. Every open overlay's
      // listener is on `document` and all of them see this same keydown —
      // without this check, a confirm dialog opened on top of a drawer would
      // dismiss both at once on a single Escape press, discarding the
      // drawer's state instead of just canceling the confirmation.
      if (openOverlays[openOverlays.length - 1] !== this) {
        return;
      }
      if (this.options.closeOnEscape?.() === false) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      this.options.onEscape();
      return;
    }

    if (event.key !== 'Tab') {
      return;
    }

    const tabbable = tabbableWithin(panel);
    if (tabbable.length === 0) {
      // Nothing to move to, so the only correct Tab is no Tab at all —
      // otherwise focus leaves for the page behind and never comes back.
      event.preventDefault();
      panel.focus();
      return;
    }

    const first = tabbable[0];
    const last = tabbable[tabbable.length - 1];
    const active = document.activeElement;

    // `!panel.contains(active)` covers the case the trap exists for: focus is
    // somewhere behind the overlay (a click landed there, or the browser
    // restored it) and the next Tab must pull it back in rather than walk it
    // further away.
    if (!(active instanceof HTMLElement) || !panel.contains(active)) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
      return;
    }

    if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    } else if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    }
  }
}
