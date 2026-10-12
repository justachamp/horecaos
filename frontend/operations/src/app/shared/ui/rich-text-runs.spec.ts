import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { BLOCK_LIMITS, Inline } from './block-document';
import { RichTextRunLabels, RichTextRuns } from './rich-text-runs';

const LABELS: RichTextRunLabels = {
  text: 'Text',
  bold: 'Bold',
  italic: 'Italic',
  link: 'Link',
  linkAddress: 'Link address',
  linkRefused: 'Only https, tel: and mailto: links',
  addRun: 'Add text',
  removeRun: 'Remove text',
};

@Component({
  selector: 'q-runs-host',
  imports: [RichTextRuns],
  template: `<q-rich-text-runs
    [runs]="runs()"
    [labels]="labels"
    [disabled]="disabled()"
    (runsChange)="onChange($event)"
  />`,
})
class Host {
  readonly runs = signal<readonly Inline[]>([{ text: 'one' }]);
  readonly disabled = signal(false);
  readonly labels = LABELS;
  readonly emitted: (readonly Inline[])[] = [];
  onChange(runs: readonly Inline[]): void {
    this.emitted.push(runs);
    this.runs.set(runs);
  }
}

function render(initial?: readonly Inline[]): {
  fixture: ReturnType<typeof TestBed.createComponent<Host>>;
  root: HTMLElement;
} {
  const fixture = TestBed.createComponent(Host);
  if (initial) {
    fixture.componentInstance.runs.set(initial);
  }
  fixture.detectChanges();
  return { fixture, root: fixture.nativeElement as HTMLElement };
}

function click(root: HTMLElement, testid: string, index = 0): void {
  root.querySelectorAll<HTMLElement>(`[data-testid="${testid}"]`)[index].click();
}

describe('RichTextRuns', () => {
  it('shows a row per run and reports a typed change as the complete new list', () => {
    const { fixture, root } = render([{ text: 'a' }, { text: 'b', bold: true }]);
    expect(root.querySelectorAll('[data-testid="q-rich-text-run"]')).toHaveLength(2);

    const second = root.querySelectorAll<HTMLTextAreaElement>(
      '[data-testid="q-rich-text-run-text"]',
    )[1];
    second.value = 'changed';
    second.dispatchEvent(new Event('input'));

    expect(fixture.componentInstance.emitted.at(-1)).toEqual([
      { text: 'a' },
      { text: 'changed', bold: true },
    ]);
  });

  it('turns bold and italic on and off as flags on that run only', () => {
    const { fixture, root } = render([{ text: 'a' }, { text: 'b' }]);

    click(root, 'q-rich-text-run-bold', 1);
    fixture.detectChanges();
    click(root, 'q-rich-text-run-italic', 1);
    expect(fixture.componentInstance.emitted.at(-1)).toEqual([
      { text: 'a' },
      { text: 'b', bold: true, italic: true },
    ]);

    fixture.detectChanges();
    click(root, 'q-rich-text-run-bold', 1);
    expect(fixture.componentInstance.emitted.at(-1)).toEqual([
      { text: 'a' },
      { text: 'b', italic: true },
    ]);
  });

  it('opens a link address on a run, reports it, and drops it again when the link is turned off', () => {
    const { fixture, root } = render([{ text: 'a' }]);
    expect(root.querySelector('[data-testid="q-rich-text-run-href"]')).toBeNull();

    click(root, 'q-rich-text-run-link');
    fixture.detectChanges();
    const href = root.querySelector<HTMLInputElement>('[data-testid="q-rich-text-run-href"]')!;
    expect(href).toBeTruthy();

    href.value = 'https://example.uz/a';
    href.dispatchEvent(new Event('input'));
    expect(fixture.componentInstance.emitted.at(-1)).toEqual([
      { text: 'a', href: 'https://example.uz/a' },
    ]);

    fixture.detectChanges();
    click(root, 'q-rich-text-run-link');
    expect(fixture.componentInstance.emitted.at(-1)).toEqual([{ text: 'a' }]);
  });

  it('flags a link the grammar refuses on its own row while it is typed', () => {
    const { fixture, root } = render([{ text: 'a', href: 'javascript:alert(1)' }]);

    const href = root.querySelector<HTMLInputElement>('[data-testid="q-rich-text-run-href"]')!;
    expect(href.getAttribute('aria-invalid')).toBe('true');
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('https');

    fixture.componentInstance.runs.set([{ text: 'a', href: 'https://example.uz' }]);
    fixture.detectChanges();
    expect(root.querySelector('[role="alert"]')).toBeNull();
  });

  it('keeps at least one run, adds a blank one, and stops adding at the platform limit', () => {
    const { fixture, root } = render([{ text: 'only' }]);
    const remove = root.querySelector<HTMLButtonElement>('[data-testid="q-rich-text-run-remove"]')!;
    expect(remove.disabled).toBe(true);

    click(root, 'q-rich-text-run-add');
    expect(fixture.componentInstance.emitted.at(-1)).toEqual([{ text: 'only' }, { text: '' }]);

    fixture.componentInstance.runs.set(
      Array.from({ length: BLOCK_LIMITS.maxRunsPerBlock }, () => ({ text: 'x' })),
    );
    fixture.detectChanges();
    expect(root.querySelector('[data-testid="q-rich-text-run-add"]')).toBeNull();
  });

  it('removes the run it is asked to', () => {
    const { fixture, root } = render([{ text: 'a' }, { text: 'b' }, { text: 'c' }]);

    click(root, 'q-rich-text-run-remove', 1);

    expect(fixture.componentInstance.emitted.at(-1)).toEqual([{ text: 'a' }, { text: 'c' }]);
  });

  it('is inert while disabled', () => {
    const { fixture, root } = render([{ text: 'a' }, { text: 'b' }]);
    fixture.componentInstance.disabled.set(true);
    fixture.detectChanges();

    for (const el of Array.from(root.querySelectorAll('textarea, button'))) {
      expect((el as HTMLButtonElement | HTMLTextAreaElement).disabled).toBe(true);
    }
  });
});
