import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { RichText, RichTextBlockKindLabels } from './rich-text';

const KIND_LABELS: RichTextBlockKindLabels = {
  paragraph: 'Paragraph',
  heading: 'Heading',
  bullet: 'Bullet list',
  numbered: 'Numbered list',
};

function render(value = ''): ReturnType<typeof TestBed.createComponent<RichText>> {
  const fixture = TestBed.createComponent(RichText);
  fixture.componentRef.setInput('value', value);
  fixture.componentRef.setInput('kindLabels', KIND_LABELS);
  fixture.detectChanges();
  return fixture;
}

function textareas(
  fixture: ReturnType<typeof TestBed.createComponent<RichText>>,
): NodeListOf<HTMLTextAreaElement> {
  return (fixture.nativeElement as HTMLElement).querySelectorAll(
    '[data-testid="q-rich-text-textarea"]',
  );
}

describe('RichText', () => {
  it('starts with one empty paragraph block when given no value', () => {
    const fixture = render();

    const areas = textareas(fixture);
    expect(areas).toHaveLength(1);
    expect(areas[0].value).toBe('');
  });

  it('parses a saved document into blocks, headings and lists included', () => {
    const fixture = render(
      '<h2>Заголовок</h2><p>Первый абзац.</p><ul><li>Пункт один</li><li>Пункт два</li></ul>',
    );

    const areas = textareas(fixture);
    expect(areas).toHaveLength(4);
    expect(areas[0].value).toBe('Заголовок');
    expect(areas[1].value).toBe('Первый абзац.');
    expect(areas[2].value).toBe('Пункт один');
    expect(areas[3].value).toBe('Пункт два');
  });

  it('parses a legacy bare-text value — no tags at all — as one paragraph, not an empty document', () => {
    // `terms-page.ts`'s three fields are plain `<textarea>`s today; this is
    // the value this editor must open without losing.
    const fixture = render('Мы обязуемся защищать ваши персональные данные.');

    const areas = textareas(fixture);
    expect(areas).toHaveLength(1);
    expect(areas[0].value).toBe('Мы обязуемся защищать ваши персональные данные.');
  });

  it('rejects a script tag in a loaded document — nothing of it reaches a block', () => {
    // The row's whole point, proven at the component boundary: a document
    // loaded from storage (a legacy import, an earlier session) must not be
    // able to seed a block with anything but text.
    const fixture = render('<p>Условия</p><script>alert(document.cookie)</script>');

    const areas = textareas(fixture);
    expect(areas).toHaveLength(1);
    expect(areas[0].value).toBe('Условия');
    expect((fixture.nativeElement as HTMLElement).innerHTML).not.toContain('alert(');
  });

  it('rejects a javascript: href in a loaded document, keeping only the link text', () => {
    const fixture = render('<p><a href="javascript:alert(1)">Нажми меня</a></p>');

    const areas = textareas(fixture);
    expect(areas[0].value).toBe('Нажми меня');
    expect((fixture.nativeElement as HTMLElement).innerHTML).not.toContain('javascript:');
  });

  it('emits sanitized HTML as the operator types, with the typed text HTML-escaped', () => {
    const fixture = render('<p>start</p>');
    const emitted: string[] = [];
    fixture.componentInstance.valueChange.subscribe((html) => emitted.push(html));

    const area = textareas(fixture)[0];
    area.value = '<script>alert(1)</script>';
    area.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(emitted.at(-1)).not.toContain('<script');
    expect(emitted.at(-1)).toContain('&lt;script&gt;');
  });

  it('does not reset the operator’s typed block when the caller echoes the emitted value straight back', () => {
    // The ordinary controlled-component wiring — `[value]="doc()"
    // (valueChange)="doc.set($event)"` — feeds this component's own
    // emission back in as the next `value`. Re-parsing it would mint a new
    // block id and tear down the `<textarea>` mid-keystroke.
    const fixture = render('<p>start</p>');
    let current = '<p>start</p>';
    fixture.componentInstance.valueChange.subscribe((html) => {
      current = html;
      fixture.componentRef.setInput('value', current);
      fixture.detectChanges();
    });

    const firstArea = textareas(fixture)[0];
    firstArea.value = 'start typing';
    firstArea.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(textareas(fixture)[0]).toBe(firstArea);
    expect(textareas(fixture)[0].value).toBe('start typing');
  });

  it('adds a new paragraph block after the last one', () => {
    const fixture = render('<p>first</p>');

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-testid="q-rich-text-add"]')!
      .click();
    fixture.detectChanges();

    expect(textareas(fixture)).toHaveLength(2);
  });

  it('changes a block’s kind through the kind picker', () => {
    const fixture = render('<p>first</p>');

    const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      '[data-testid="q-rich-text-kind"]',
    )!;
    select.value = 'heading';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-rich-text-block"]')
        ?.className,
    ).toContain('q-rich-text__block--heading');
  });

  it('never removes the last remaining block', () => {
    const fixture = render('<p>only</p>');

    const remove = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '[data-testid="q-rich-text-remove"]',
    )!;
    expect(remove.disabled).toBe(true);
  });

  it('disables every control when disabled', () => {
    const fixture = render('<p>first</p>');
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();

    expect(textareas(fixture)[0].disabled).toBe(true);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
        '[data-testid="q-rich-text-add"]',
      )!.disabled,
    ).toBe(true);
  });
});
