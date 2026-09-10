import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { ErrorTaxonomy } from './error-taxonomy';
import { FailureCategoryView, IntegrationOpsApi } from './integration-ops-api';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const CODES = [
  'TRANSIENT_INFRASTRUCTURE', 'TRANSIENT_PROVIDER', 'CONTRACT_UNSUPPORTED', 'PAYLOAD_INVALID',
  'DOMAIN_REJECTED', 'AUTHORIZATION_REJECTED', 'UNCERTAIN_EXTERNAL_OUTCOME', 'UNKNOWN',
] as const;

function category(code: string, overrides: Partial<FailureCategoryView> = {}): FailureCategoryView {
  return {
    code, retryableByTimer: code.startsWith('TRANSIENT'), requiresReconciliation: code === 'UNCERTAIN_EXTERNAL_OUTCOME',
    securityRelevant: code === 'AUTHORIZATION_REJECTED', outboxDeadLettered: 0, outboxWaiting: 0, inboxDeadLettered: 0, inboxWaiting: 0,
    ...overrides,
  };
}

describe('ErrorTaxonomy', () => {
  let fixture: ComponentFixture<ErrorTaxonomy>;

  async function create(categories: FailureCategoryView[]): Promise<void> {
    localStorage.clear();
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ErrorTaxonomy],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: IntegrationOpsApi, useValue: { failureTaxonomy: vi.fn().mockResolvedValue(categories) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ErrorTaxonomy);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function card(code: string): HTMLElement {
    return fixture.nativeElement.querySelector(`[data-code="${code}"]`) as HTMLElement;
  }

  it('has words for every category the platform files failures under', async () => {
    await create(CODES.map((code) => category(code)));

    for (const code of CODES) {
      expect(card(code).textContent).toContain(ru[`errorTaxonomy.${code}.name`]);
      expect(card(code).textContent).toContain(ru[`errorTaxonomy.${code}.action`]);
    }
  });

  it('adds both queues together and links to the dead letters only when some wait on a person', async () => {
    await create([
      category('PAYLOAD_INVALID', { outboxDeadLettered: 2, inboxDeadLettered: 1, inboxWaiting: 4 }),
      category('TRANSIENT_PROVIDER'),
    ]);

    expect(card('PAYLOAD_INVALID').textContent).toContain('3');
    expect(card('PAYLOAD_INVALID').textContent).toContain('4');
    expect(card('PAYLOAD_INVALID').classList.contains('hot')).toBe(true);
    expect(card('PAYLOAD_INVALID').querySelector('a')).not.toBeNull();
    expect(card('TRANSIENT_PROVIDER').querySelector('a')).toBeNull();
  });

  it('marks the categories that need the provider checked first or are a security question', async () => {
    await create([category('UNCERTAIN_EXTERNAL_OUTCOME'), category('AUTHORIZATION_REJECTED'), category('TRANSIENT_PROVIDER')]);

    expect(card('UNCERTAIN_EXTERNAL_OUTCOME').textContent).toContain(ru['errorTaxonomy.reconcileFirst']);
    expect(card('AUTHORIZATION_REJECTED').textContent).toContain(ru['errorTaxonomy.security']);
    expect(card('TRANSIENT_PROVIDER').textContent).toContain(ru['errorTaxonomy.retried']);
  });
});
