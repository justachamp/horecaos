import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { OtpInput } from './otp-input';

function render(length = 6): ReturnType<typeof TestBed.createComponent<OtpInput>> {
  const fixture = TestBed.createComponent(OtpInput);
  // Attached to the document so `document.activeElement` assertions below
  // (auto-advance, backspace-back) reflect real focus — matching
  // `overlay.spec.ts`'s own setup for the same reason.
  document.body.appendChild(fixture.nativeElement);
  fixture.componentRef.setInput('length', length);
  fixture.componentRef.setInput('cellLabelPrefix', 'Digit');
  fixture.detectChanges();
  return fixture;
}

function cells(fixture: ReturnType<typeof TestBed.createComponent<OtpInput>>): HTMLInputElement[] {
  return Array.from(
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>(
      '[data-testid="q-otp-input-cell"]',
    ),
  );
}

function typeDigit(cell: HTMLInputElement, digit: string): void {
  cell.value = digit;
  cell.dispatchEvent(new Event('input'));
}

function pasteInto(cell: HTMLInputElement, text: string): void {
  const event = new Event('paste', { cancelable: true }) as ClipboardEvent & {
    clipboardData: { getData: (type: string) => string };
  };
  Object.defineProperty(event, 'clipboardData', { value: { getData: () => text } });
  cell.dispatchEvent(event);
}

describe('OtpInput', () => {
  it('renders one cell per digit of the configured length', () => {
    const fixture = render(6);
    expect(cells(fixture)).toHaveLength(6);
  });

  it('labels each cell for a screen reader', () => {
    const fixture = render(4);
    const rendered = cells(fixture);
    expect(rendered[0].getAttribute('aria-label')).toBe('Digit 1');
    expect(rendered[3].getAttribute('aria-label')).toBe('Digit 4');
  });

  it('auto-advances to the next cell after a digit is typed', () => {
    const fixture = render(4);
    const rendered = cells(fixture);

    typeDigit(rendered[0], '1');
    fixture.detectChanges();

    expect(document.activeElement).toBe(rendered[1]);
  });

  it('moves back and clears the previous cell on backspace from an empty cell', () => {
    const fixture = render(4);
    const rendered = cells(fixture);
    typeDigit(rendered[0], '1');
    fixture.detectChanges();
    rendered[1].focus();

    rendered[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));
    fixture.detectChanges();

    expect(document.activeElement).toBe(rendered[0]);
    expect(rendered[0].value).toBe('');
  });

  it('emits the joined value on every change', () => {
    const fixture = render(4);
    const emitted: string[] = [];
    fixture.componentInstance.valueChange.subscribe((v) => emitted.push(v));

    const rendered = cells(fixture);
    typeDigit(rendered[0], '1');
    fixture.detectChanges();
    typeDigit(rendered[1], '2');
    fixture.detectChanges();

    expect(emitted).toEqual(['1', '12']);
  });

  it('fills every cell from a single paste, and auto-submits', () => {
    const fixture = render(6);
    const complete = vi.fn();
    fixture.componentInstance.complete.subscribe(complete);

    const rendered = cells(fixture);
    pasteInto(rendered[0], '482913');
    fixture.detectChanges();

    expect(rendered.map((c) => c.value)).toEqual(['4', '8', '2', '9', '1', '3']);
    expect(complete).toHaveBeenCalledWith('482913');
  });

  it('strips non-digit characters from a paste', () => {
    const fixture = render(6);
    const rendered = cells(fixture);

    pasteInto(rendered[0], '48-29 13');
    fixture.detectChanges();

    expect(rendered.map((c) => c.value)).toEqual(['4', '8', '2', '9', '1', '3']);
  });

  it('emits complete exactly once, only when the last cell is filled by typing', () => {
    const fixture = render(3);
    const complete = vi.fn();
    fixture.componentInstance.complete.subscribe(complete);

    const rendered = cells(fixture);
    typeDigit(rendered[0], '1');
    fixture.detectChanges();
    typeDigit(rendered[1], '2');
    fixture.detectChanges();
    expect(complete).not.toHaveBeenCalled();

    typeDigit(rendered[2], '3');
    fixture.detectChanges();
    expect(complete).toHaveBeenCalledTimes(1);
    expect(complete).toHaveBeenCalledWith('123');
  });

  it('does not reset the operator’s progress when the caller echoes the emitted value straight back', () => {
    const fixture = render(4);
    fixture.componentInstance.valueChange.subscribe((v) => {
      fixture.componentRef.setInput('value', v);
      fixture.detectChanges();
    });

    const rendered = cells(fixture);
    typeDigit(rendered[0], '1');
    fixture.detectChanges();
    typeDigit(rendered[1], '2');
    fixture.detectChanges();

    const stillRendered = cells(fixture);
    expect(stillRendered.every((cell, i) => cell === rendered[i])).toBe(true);
    expect(rendered.map((c) => c.value)).toEqual(['1', '2', '', '']);
  });

  it('disables every cell when disabled', () => {
    const fixture = render(4);
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();

    expect(cells(fixture).every((c) => c.disabled)).toBe(true);
  });
});
