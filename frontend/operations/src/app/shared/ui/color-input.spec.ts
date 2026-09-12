import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { ColorInput } from './color-input';

function render(): ReturnType<typeof TestBed.createComponent<ColorInput>> {
  const fixture = TestBed.createComponent(ColorInput);
  fixture.detectChanges();
  return fixture;
}

function textField(
  fixture: ReturnType<typeof TestBed.createComponent<ColorInput>>,
): HTMLInputElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
    '[data-testid="q-color-input-text"]',
  )!;
}

describe('ColorInput', () => {
  beforeEach(() => TestBed.configureTestingModule({}));

  it('starts the text field from the given value', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', '#0f62fe');
    fixture.detectChanges();

    expect(textField(fixture).value).toBe('#0f62fe');
  });

  it('emits a complete, valid hex value as it is typed', () => {
    const fixture = render();
    let emitted: string | undefined;
    fixture.componentInstance.valueChange.subscribe((v) => (emitted = v));
    const field = textField(fixture);

    field.value = '#ff0000';
    field.dispatchEvent(new Event('input'));

    expect(emitted).toBe('#ff0000');
  });

  it('does not emit while the typed text is not yet a complete hex value', () => {
    const fixture = render();
    let emitted = false;
    fixture.componentInstance.valueChange.subscribe(() => (emitted = true));
    const field = textField(fixture);

    field.value = '#ff';
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(emitted).toBe(false);
    expect(field.getAttribute('aria-invalid')).toBe('true');
  });

  it('reverts an incomplete typed value to the last good one on blur', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', '#0f62fe');
    fixture.detectChanges();
    const field = textField(fixture);

    field.value = '#ff';
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    field.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    expect(textField(fixture).value).toBe('#0f62fe');
  });
});
