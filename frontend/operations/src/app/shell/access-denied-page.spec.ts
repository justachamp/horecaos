import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../core/i18n/i18n';
import { AccessDeniedPage } from './access-denied-page';

async function open(
  queryParams: Record<string, string>,
): Promise<ComponentFixture<AccessDeniedPage>> {
  await TestBed.configureTestingModule({
    imports: [AccessDeniedPage],
    providers: [
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
      },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(AccessDeniedPage);
  fixture.detectChanges();
  return fixture;
}

describe('AccessDeniedPage', () => {
  it('names the missing capability, via q-denied-state', async () => {
    const fixture = await open({ capability: 'IAM_GRANT_MANAGE' });

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="q-denied-state"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="q-denied-state-capability"]')?.textContent).toContain(
      'IAM_GRANT_MANAGE',
    );
  });

  it('names the denied section, when the guard could resolve one', () => {
    return open({ capability: 'IAM_GRANT_MANAGE', section: 'shell.nav.staff' }).then((fixture) => {
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('h1')?.textContent?.trim()).toBe('Staff and access');
    });
  });

  it('offers a way back to Today', async () => {
    const fixture = await open({ capability: 'ORDER_READ' });

    const link = (fixture.nativeElement as HTMLElement).querySelector('a[href="/today"]');
    expect(link).not.toBeNull();
  });

  it('renders no capability line when the guard could not name one', async () => {
    const fixture = await open({});

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="q-denied-state-capability"]')).toBeNull();
  });
});
