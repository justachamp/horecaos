import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { DiffViewer } from './diff-viewer';

function render(
  document: Readonly<Record<string, unknown>> | null,
): ReturnType<typeof TestBed.createComponent<DiffViewer>> {
  const fixture = TestBed.createComponent(DiffViewer);
  fixture.componentRef.setInput('document', document);
  fixture.detectChanges();
  return fixture;
}

function rowsOf(
  fixture: ReturnType<typeof TestBed.createComponent<DiffViewer>>,
): NodeListOf<HTMLElement> {
  return (fixture.nativeElement as HTMLElement).querySelectorAll(
    '[data-testid="q-diff-viewer-row"]',
  );
}

describe('DiffViewer', () => {
  it('renders a per-field {before, after} document', () => {
    // `ChangeDocuments.change(field, before, after)`'s own shape.
    const fixture = render({
      status: { before: 'DRAFT', after: 'ACTIVE' },
      priceMinor: { before: 125000, after: 130000 },
    });

    const rows = rowsOf(fixture);
    expect(rows).toHaveLength(2);
    expect(rows[0].querySelector('[data-testid="q-diff-viewer-before"]')?.textContent).toBe(
      'DRAFT',
    );
    expect(rows[0].querySelector('[data-testid="q-diff-viewer-after"]')?.textContent).toBe(
      'ACTIVE',
    );
    expect(rows[1].querySelector('[data-testid="q-diff-viewer-after"]')?.textContent).toBe(
      '130000',
    );
  });

  it('renders a null before as the same dash every empty value uses', () => {
    const fixture = render({ note: { before: null, after: 'Called back' } });

    const rows = rowsOf(fixture);
    expect(rows[0].querySelector('[data-testid="q-diff-viewer-before"]')?.textContent).toBe('—');
  });

  it('renders a flat, after-only fact as a value rather than a fabricated undefined → value diff', () => {
    // T08's own finding: ~130 of 132 `.changed(...)` call sites still write a
    // flat after-only map. This viewer must not lie about what it is showing.
    const fixture = render({ reasonCode: 'CUSTOMER_REQUEST' });

    const rows = rowsOf(fixture);
    expect(rows).toHaveLength(1);
    expect(rows[0].querySelector('[data-testid="q-diff-viewer-before"]')).toBeNull();
    expect(rows[0].querySelector('[data-testid="q-diff-viewer-after"]')?.textContent).toBe(
      'CUSTOMER_REQUEST',
    );
  });

  it('renders the empty label for a null document', () => {
    const fixture = render(null);
    fixture.componentRef.setInput('emptyLabel', 'Нет изменений');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-diff-viewer-empty"]')
        ?.textContent,
    ).toBe('Нет изменений');
    expect(rowsOf(fixture)).toHaveLength(0);
  });

  it('renders the empty label for an empty document', () => {
    const fixture = render({});
    fixture.componentRef.setInput('emptyLabel', 'Нет изменений');
    fixture.detectChanges();

    expect(rowsOf(fixture)).toHaveLength(0);
  });
});
