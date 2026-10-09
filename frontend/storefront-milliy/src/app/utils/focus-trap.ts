const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]';

/**
 * Keeps Tab inside a modal dialog: from the last control it goes to the first, from the first back
 * to the last on Shift+Tab, and a focus that has strayed outside comes back in. Call it from the
 * dialog's `keydown`; it only acts on Tab.
 *
 * A dialog with nothing to focus holds focus on itself, so it must have `tabindex="-1"`.
 */
export function trapTab(event: KeyboardEvent, container: HTMLElement): void {
  if (event.key !== 'Tab') {
    return;
  }
  const controls = Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
    (element) => element.tabIndex >= 0,
  );
  const first = controls[0];
  const last = controls[controls.length - 1];
  if (!first || !last) {
    event.preventDefault();
    container.focus();
    return;
  }
  const active = container.ownerDocument.activeElement;
  const inside = active !== null && container.contains(active);
  if (event.shiftKey ? !inside || active === first : !inside || active === last) {
    event.preventDefault();
    (event.shiftKey ? last : first).focus();
  }
}
