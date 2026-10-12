import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { LangService } from '../../services/lang.service';
import type { OfferedTag } from '../../services/reviews.service';
import { TagPickerComponent } from './tag-picker.component';

const ICON = '/api/v1/storefront/tenants/t-1/media/a-1';

function tag(id: string, en: string, iconUrl: string | null = null): OfferedTag {
  return { id, code: id, category: 'FOOD', labels: { en }, iconUrl };
}

function setUp(tags: readonly OfferedTag[]) {
  TestBed.configureTestingModule({
    imports: [TagPickerComponent],
    providers: [{ provide: LangService, useValue: { langId: signal('en') } }],
  });
  const fixture = TestBed.createComponent(TagPickerComponent);
  fixture.componentRef.setInput('tags', tags);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

describe('TagPickerComponent', () => {
  it('draws a tag’s icon beside its wording, and a tag without one is its wording alone', () => {
    const { host } = setUp([tag('tasty', 'Tasty', ICON), tag('fast', 'Fast')]);

    const [first, second] = Array.from(host.querySelectorAll('[data-testid="tag-chip"]'));
    const icon = first.querySelector('[data-testid="tag-icon"]');
    expect(icon?.getAttribute('src')).toBe(ICON);
    expect(icon?.getAttribute('alt')).toBe('');
    expect(first.textContent?.trim()).toBe('Tasty');
    expect(second.querySelector('[data-testid="tag-icon"]')).toBeNull();
    expect(second.textContent?.trim()).toBe('Fast');
  });

  it('still reports the tag tapped', () => {
    const { fixture, host } = setUp([tag('tasty', 'Tasty', ICON)]);
    const tapped: OfferedTag[] = [];
    fixture.componentInstance.toggled.subscribe((t) => tapped.push(t));

    (host.querySelector('[data-testid="tag-chip"]') as HTMLButtonElement).click();

    expect(tapped.map((t) => t.id)).toEqual(['tasty']);
  });
});
