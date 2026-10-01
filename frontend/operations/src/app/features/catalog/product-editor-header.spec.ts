import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ProductEditorHeader } from './product-editor-header';

function render(saveNotice: string | null = null) {
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ProductEditorHeader);
  fixture.componentRef.setInput('name', 'Plov');
  fixture.componentRef.setInput('code', 'PLOV-01');
  fixture.componentRef.setInput('statusLabel', 'Draft');
  fixture.componentRef.setInput('saveNotice', saveNotice);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host };
}

describe('ProductEditorHeader', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('names the product, its code and its status', () => {
    const { host } = render();

    expect(host.querySelector('h1')?.textContent?.trim()).toBe('Plov');
    expect(host.textContent).toContain('PLOV-01');
    expect(host.querySelector('.status-badge')?.textContent?.trim()).toBe('Draft');
  });

  it('links back to the product list', () => {
    const { host } = render();

    const link = host.querySelector<HTMLAnchorElement>('.editor__back');
    expect(link?.textContent?.trim()).toBe('Back to Products');
    expect(link?.getAttribute('href')).toBe('/catalog/products');
  });

  it('shows the save notice only when there is one', () => {
    expect(render().host.querySelector('.editor__notice')).toBeNull();

    TestBed.resetTestingModule();
    expect(render('Saved').host.querySelector('.editor__notice')?.textContent?.trim()).toBe(
      'Saved',
    );
  });

  it('asks to publish when Publish is pressed', () => {
    const { fixture, host } = render();
    let requests = 0;
    fixture.componentInstance.publishRequested.subscribe(() => requests++);

    host.querySelector<HTMLButtonElement>('[data-testid="editor-publish"]')?.click();

    expect(requests).toBe(1);
  });
});
