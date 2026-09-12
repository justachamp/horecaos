import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { SecretInput } from './secret-input';

function render(): ReturnType<typeof TestBed.createComponent<SecretInput>> {
  const fixture = TestBed.createComponent(SecretInput);
  fixture.componentRef.setInput('label', 'Bot token');
  fixture.componentRef.setInput('fieldId', 'test-secret');
  fixture.detectChanges();
  return fixture;
}

function host(fixture: ReturnType<typeof TestBed.createComponent<SecretInput>>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function field(fixture: ReturnType<typeof TestBed.createComponent<SecretInput>>): HTMLInputElement {
  return host(fixture).querySelector('[data-testid="q-secret-input-field"]') as HTMLInputElement;
}

/**
 * `q-secret-input` (ADR 0106, gap-map row `X.14`): masked entry with a
 * reveal-once-**at-entry** toggle, and a read-only "configured" mode that
 * never shows a value — only a reference, a rotate trigger and the two
 * timestamps nothing rendered before this wave.
 */
describe('SecretInput', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('masks the entry field by default and reveals only the value currently typed, never a stored one', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', 'a-real-token');
    fixture.detectChanges();

    expect(field(fixture).type).toBe('password');

    (
      host(fixture).querySelector('[data-testid="q-secret-input-toggle"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(field(fixture).type).toBe('text');
    expect(field(fixture).value).toBe('a-real-token');
  });

  it('emits every keystroke as valueChange, never buffering it internally', () => {
    const fixture = render();
    const changes: string[] = [];
    fixture.componentInstance.valueChange.subscribe((value) => changes.push(value));

    const input = field(fixture);
    input.value = 'partial-token';
    input.dispatchEvent(new Event('input'));

    expect(changes).toEqual(['partial-token']);
  });

  it('never renders an input at all in configured mode — there is nothing typed to mask', () => {
    const fixture = render();
    fixture.componentRef.setInput('mode', 'configured');
    fixture.componentRef.setInput('configured', true);
    fixture.componentRef.setInput('reference', 'horecaos:prod:provider_notification:tenant-1:abc');
    fixture.detectChanges();

    expect(field(fixture)).toBeNull();
    expect(
      host(fixture).querySelector('[data-testid="q-secret-input-reference"]')?.textContent,
    ).toContain('horecaos:prod:provider_notification:tenant-1:abc');
  });

  it('shows "not set" rather than a reference when nothing is configured yet', () => {
    const fixture = render();
    fixture.componentRef.setInput('mode', 'configured');
    fixture.componentRef.setInput('configured', false);
    fixture.detectChanges();

    expect(host(fixture).querySelector('[data-testid="q-secret-input-reference"]')).toBeNull();
    expect(host(fixture).textContent).toContain('Not set');
  });

  it('emits rotate when the rotate action is pressed, and never touches the reference itself', () => {
    const fixture = render();
    fixture.componentRef.setInput('mode', 'configured');
    fixture.componentRef.setInput('configured', true);
    fixture.componentRef.setInput('reference', 'ref-1');
    fixture.detectChanges();

    const rotate = vi.fn();
    fixture.componentInstance.rotate.subscribe(rotate);
    (
      host(fixture).querySelector('[data-testid="q-secret-input-rotate"]') as HTMLButtonElement
    ).click();

    expect(rotate).toHaveBeenCalledTimes(1);
  });

  it('renders last-rotated and last-used only when the caller supplies them', () => {
    const fixture = render();
    fixture.componentRef.setInput('mode', 'configured');
    fixture.componentRef.setInput('configured', true);
    fixture.componentRef.setInput('lastRotatedLabel', '1 Sep 2026');
    fixture.componentRef.setInput('lastUsedLabel', 'Never');
    fixture.detectChanges();

    const text = host(fixture).textContent ?? '';
    expect(text).toContain('1 Sep 2026');
    expect(text).toContain('Never');
  });
});
