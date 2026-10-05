import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ConfigurationKeyView } from '../../../core/api/configuration';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { ShortcutRegistry } from '../../../shared/keyboard/shortcut-registry';
import { ConfigurationApi } from '../configuration-api';
import { ReadinessApi, ValidationOutcome } from './readiness-api';
import { SettingsHomePage } from './settings-home-page';

const TENANT_ID = 'tenant-1';

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

const VAT_RATE_KEY: ConfigurationKeyView = {
  code: 'ordering.vat_rate_percent',
  valueType: 'BigDecimal',
  defaultValue: '12',
  settableScopes: ['TENANT', 'BRAND'],
  owningModule: 'ordering',
  explicitNullTerminates: false,
  description: 'The VAT rate applied at checkout, as a percentage.',
};

/** A key `CONFIGURATION_KEY_ROUTES` names no screen for — must never appear as a search result. */
const UNROUTED_KEY: ConfigurationKeyView = {
  code: 'commercial.enforcement_ceiling',
  valueType: 'String',
  defaultValue: 'METER_ONLY',
  settableScopes: ['PLATFORM', 'TENANT'],
  owningModule: 'commercial',
  explicitNullTerminates: false,
  description: 'The strongest enforcement mode entitlement checks may apply for this tenant.',
};

async function render(
  readiness: Partial<ReadinessApi>,
  configurationKeys: readonly ConfigurationKeyView[] = [VAT_RATE_KEY, UNROUTED_KEY],
): Promise<ComponentFixture<SettingsHomePage>> {
  await TestBed.configureTestingModule({
    imports: [SettingsHomePage],
    providers: [
      provideRouter([]),
      {
        provide: CurrentTenant,
        useValue: {
          tenantId: () => TENANT_ID,
          denied: () => false,
          ensureLoaded: () => Promise.resolve(),
        },
      },
      { provide: ReadinessApi, useValue: readiness },
      {
        provide: ConfigurationApi,
        useValue: { keys: vi.fn().mockResolvedValue(configurationKeys) },
      },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(SettingsHomePage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

const PASSING: ValidationOutcome = {
  allPassed: true,
  checks: [
    {
      stepKey: 'BRANDS_AND_LOCATIONS_VALIDATE',
      passed: true,
      errorCode: null,
      detail: null,
      locationId: null,
    },
  ],
};

function slashKey(target: HTMLElement): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key: '/', bubbles: true, cancelable: true });
  Object.defineProperty(event, 'target', { value: target });
  return event;
}

describe('SettingsHomePage', () => {
  it('renders every P-tier screen and the moved Integrations screen as a tile', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    const labels = [...fixture.nativeElement.querySelectorAll('.tile__label')].map(
      (node: Element) => node.textContent?.trim(),
    );
    expect(labels).toContain('Brand profile');
    expect(labels).toContain('Locations');
    expect(labels).toContain('Sales channels');
    expect(labels).toContain('Order policy');
    expect(labels).toContain('Fiscalization');
    expect(labels).toContain('Notifications');
    expect(labels).toContain('Integrations');
    expect(labels).toContain('Reference data');
    expect(labels).toContain('Data & privacy');
  });

  it('badges a screen that has no built route yet', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    const tiles = [...fixture.nativeElement.querySelectorAll('.tile')];
    const channelSetup = tiles.find((tile: Element) => tile.textContent?.includes('Channel setup'));
    expect(channelSetup?.querySelector('.tile__badge')).toBeTruthy();

    const salesChannels = tiles.find((tile: Element) =>
      tile.textContent?.includes('Sales channels'),
    );
    expect(salesChannels?.querySelector('.tile__badge')).toBeFalsy();
  });

  it('groups tiles under the spec nav groups, not an alphabetical list', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    const groupLabels = [...fixture.nativeElement.querySelectorAll('.group h2')].map(
      (node: Element) => node.textContent?.trim(),
    );
    expect(groupLabels).toEqual([
      'The business',
      'Selling',
      'Money and tax',
      'Messages',
      'Connections',
      'Reference',
      'Privacy',
    ]);
  });

  it('shows the success empty state when every readiness check passes', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    expect(fixture.nativeElement.querySelector('.readiness__clear')?.textContent).toContain(
      'Everything is set up to take orders',
    );
  });

  it('counts every offending location as its own row, not one row for the whole check', async () => {
    const outcome: ValidationOutcome = {
      allPassed: false,
      checks: [
        {
          stepKey: 'PAYMENT_CONFIGURATION_VALIDATE',
          passed: false,
          errorCode: 'NO_LEGAL_ENTITY',
          detail: 'Location CHI has no active legal entity assigned',
          locationId: 'loc-1',
        },
        {
          stepKey: 'PAYMENT_CONFIGURATION_VALIDATE',
          passed: false,
          errorCode: 'NO_LEGAL_ENTITY',
          detail: 'Location YUN has no active legal entity assigned',
          locationId: 'loc-2',
        },
      ],
    };
    const fixture = await render({ validate: () => Promise.resolve(outcome) });

    const rows = fixture.nativeElement.querySelectorAll('.readiness__row');
    expect(rows.length).toBe(2);
  });

  it('deep-links a location-scoped finding into that location', async () => {
    const outcome: ValidationOutcome = {
      allPassed: false,
      checks: [
        {
          stepKey: 'PAYMENT_CONFIGURATION_VALIDATE',
          passed: false,
          errorCode: 'NO_LEGAL_ENTITY',
          detail: 'Location CHI has no active legal entity assigned',
          locationId: 'loc-1',
        },
      ],
    };
    const fixture = await render({ validate: () => Promise.resolve(outcome) });

    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('.readiness__row a');
    expect(link.getAttribute('href')).toBe('/settings/locations/loc-1');
  });

  it('counts every offending brand as its own row for a brand-scoped check (wave 9: catalog readiness)', async () => {
    const outcome: ValidationOutcome = {
      allPassed: false,
      checks: [
        {
          stepKey: 'CATALOG_READINESS_VALIDATE',
          passed: false,
          errorCode: 'NO_PUBLISHED_MENU',
          detail: 'Brand MAIN has no PUBLISHED catalog publication on STOREFRONT',
          locationId: null,
        },
        {
          stepKey: 'CATALOG_READINESS_VALIDATE',
          passed: false,
          errorCode: 'NO_PUBLISHED_MENU',
          detail: 'Brand SECOND has no PUBLISHED catalog publication on STOREFRONT',
          locationId: null,
        },
      ],
    };
    const fixture = await render({ validate: () => Promise.resolve(outcome) });

    const rows = fixture.nativeElement.querySelectorAll('.readiness__row');
    expect(rows.length).toBe(2);
  });

  it('deep-links a brand-scoped catalog finding into the publication screen', async () => {
    const outcome: ValidationOutcome = {
      allPassed: false,
      checks: [
        {
          stepKey: 'CATALOG_READINESS_VALIDATE',
          passed: false,
          errorCode: 'NO_PUBLISHED_MENU',
          detail: 'Brand MAIN has no PUBLISHED catalog publication on STOREFRONT',
          locationId: null,
        },
      ],
    };
    const fixture = await render({ validate: () => Promise.resolve(outcome) });

    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('.readiness__row a');
    expect(link.getAttribute('href')).toBe('/catalog/publication');
  });

  it('deep-links "no brand able to sell" into the brand profile screen', async () => {
    const outcome: ValidationOutcome = {
      allPassed: false,
      checks: [
        {
          stepKey: 'CATALOG_READINESS_VALIDATE',
          passed: false,
          errorCode: 'NO_ACTIVE_BRAND',
          detail: 'The tenant has no brand able to sell, now or once activated',
          locationId: null,
        },
      ],
    };
    const fixture = await render({ validate: () => Promise.resolve(outcome) });

    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('.readiness__row a');
    expect(link.getAttribute('href')).toBe('/settings/brand');
  });

  describe('the three settings.md §10.0 conditions that had no read behind them (row 10.0)', () => {
    const CHANNEL: ValidationOutcome['checks'][number] = {
      stepKey: 'CHANNEL_PAYMENT_COVERAGE_VALIDATE',
      passed: false,
      errorCode: 'CHANNEL_NO_PAYMENT_METHOD',
      detail: 'Sales channel STOREFRONT has no enabled payment method',
      locationId: null,
    };
    const FISCAL: ValidationOutcome['checks'][number] = {
      stepKey: 'FISCAL_CLASSIFICATION_COVERAGE_VALIDATE',
      passed: false,
      errorCode: 'FISCAL_CLASSIFICATION_INCOMPLETE',
      detail: 'Brand MAIN has 2 of 3 menu items without a complete fiscal classification',
      locationId: null,
      advisory: true,
    };
    const INSTALLATION_SECRET: ValidationOutcome['checks'][number] = {
      stepKey: 'SECRET_ROTATION_AGE_VALIDATE',
      passed: false,
      errorCode: 'INSTALLATION_SECRET_ROTATION_DUE',
      detail: 'Provider connection Clopos main (CLOPOS) has a credential 200 days old',
      locationId: null,
      advisory: true,
    };
    const MERCHANT_SECRET: ValidationOutcome['checks'][number] = {
      stepKey: 'SECRET_ROTATION_AGE_VALIDATE',
      passed: false,
      errorCode: 'MERCHANT_SECRET_ROTATION_DUE',
      detail: 'The CLICK merchant account has a credential 300 days old',
      locationId: null,
      advisory: true,
    };

    function outcomeOf(...checks: ValidationOutcome['checks'][number][]): ValidationOutcome {
      return { allPassed: false, checks };
    }

    function hrefs(fixture: ComponentFixture<SettingsHomePage>): (string | null)[] {
      return [...fixture.nativeElement.querySelectorAll('.readiness__row a')].map(
        (link: HTMLAnchorElement) => link.getAttribute('href'),
      );
    }

    it('deep-links a channel with no payment method into the sales-channels screen', async () => {
      const fixture = await render({ validate: () => Promise.resolve(outcomeOf(CHANNEL)) });

      expect(hrefs(fixture)).toEqual(['/settings/sales-channels']);
      expect(fixture.nativeElement.querySelector('.readiness__row')?.textContent).toContain(
        'A sales channel has no payment method enabled',
      );
    });

    it('deep-links incomplete fiscal classification into the fiscalization screen', async () => {
      const fixture = await render({ validate: () => Promise.resolve(outcomeOf(FISCAL)) });

      expect(hrefs(fixture)).toEqual(['/settings/fiscalization']);
      expect(fixture.nativeElement.querySelector('.readiness__row')?.textContent).toContain(
        'fiscal classification',
      );
    });

    it('deep-links both rotation findings — a provider connection and a merchant account — into integrations', async () => {
      const fixture = await render({
        validate: () => Promise.resolve(outcomeOf(INSTALLATION_SECRET, MERCHANT_SECRET)),
      });

      expect(hrefs(fixture)).toEqual(['/settings/integrations', '/settings/integrations']);
      const text = fixture.nativeElement.querySelector('.readiness__list')?.textContent ?? '';
      expect(text).toContain('provider connection');
      expect(text).toContain('merchant account');
    });

    it('lists blocking findings first and tags advisory ones, keeping the server order inside each group', async () => {
      const fixture = await render({
        // Server order: advisory, blocking, advisory, blocking.
        validate: () =>
          Promise.resolve(
            outcomeOf(FISCAL, CHANNEL, INSTALLATION_SECRET, {
              ...CHANNEL,
              detail: 'Sales channel KIOSK has no enabled payment method',
            }),
          ),
      });

      const rows: HTMLElement[] = [...fixture.nativeElement.querySelectorAll('.readiness__row')];
      expect(rows.map((row) => row.classList.contains('readiness__row--advisory'))).toEqual([
        false,
        false,
        true,
        true,
      ]);
      expect(rows.map((row) => row.querySelector('.readiness__advisory') !== null)).toEqual([
        false,
        false,
        true,
        true,
      ]);
      expect(rows[2].textContent).toContain('fiscal classification');
      expect(rows[3].textContent).toContain('provider connection');
    });

    describe('several offending items of one kind', () => {
      const KIOSK: ValidationOutcome['checks'][number] = {
        ...CHANNEL,
        detail: 'Sales channel KIOSK has no enabled payment method',
      };

      /**
       * Angular reports a duplicated `@for` track key (NG0955) on the console in
       * dev mode, but only when it reconciles a list that is already on screen —
       * the first render creates every row without comparing keys.
       */
      function duplicateKeyWarnings(spies: readonly { mock: { calls: unknown[][] } }[]): string[] {
        return spies
          .flatMap((spy) => spy.mock.calls)
          .map((call) => call.map((part) => String(part)).join(' '))
          .filter((line) => line.includes('NG0955'));
      }

      it('tells two channels with no payment method apart instead of repeating one sentence', async () => {
        const fixture = await render({
          validate: () => Promise.resolve(outcomeOf(CHANNEL, KIOSK)),
        });

        const rows: HTMLElement[] = [...fixture.nativeElement.querySelectorAll('.readiness__row')];
        expect(rows.length).toBe(2);
        expect(rows[0].textContent).toContain('STOREFRONT');
        expect(rows[0].textContent).not.toContain('KIOSK');
        expect(rows[1].textContent).toContain('KIOSK');
        expect(rows[1].textContent).not.toContain('STOREFRONT');
      });

      it('names each brand whose fiscal classification is incomplete', async () => {
        const fixture = await render({
          validate: () =>
            Promise.resolve(
              outcomeOf(FISCAL, {
                ...FISCAL,
                detail:
                  'Brand SECOND has 1 of 4 menu items without a complete fiscal classification',
              }),
            ),
        });

        const rows: HTMLElement[] = [...fixture.nativeElement.querySelectorAll('.readiness__row')];
        expect(rows.map((row) => /Brand (\w+) has/.exec(row.textContent ?? '')?.[1])).toEqual([
          'MAIN',
          'SECOND',
        ]);
      });

      it('names the legal entity of each merchant account past its rotation period', async () => {
        const fixture = await render({
          validate: () =>
            Promise.resolve(
              outcomeOf(
                {
                  ...MERCHANT_SECRET,
                  detail:
                    'The CLICK merchant account of legal entity ACME has a credential 300 days old',
                },
                {
                  ...MERCHANT_SECRET,
                  detail:
                    'The CLICK merchant account of legal entity BETA has a credential 250 days old',
                },
              ),
            ),
        });

        const rows: HTMLElement[] = [...fixture.nativeElement.querySelectorAll('.readiness__row')];
        expect(rows[0].textContent).toContain('ACME');
        expect(rows[1].textContent).toContain('BETA');
      });

      it('keeps the fixed sentence and its link beside the detail', async () => {
        const fixture = await render({ validate: () => Promise.resolve(outcomeOf(CHANNEL)) });

        const link: HTMLAnchorElement = fixture.nativeElement.querySelector('.readiness__row a');
        expect(link.textContent).toContain('A sales channel has no payment method enabled');
        expect(link.textContent).not.toContain('STOREFRONT');
        expect(fixture.nativeElement.querySelector('.readiness__scope')?.textContent).toContain(
          'STOREFRONT',
        );
      });

      it('does not repeat the detail of an unknown code, which is already its whole message', async () => {
        const unknown = {
          stepKey: 'SOMETHING_NEW_VALIDATE',
          passed: false,
          errorCode: 'SOMETHING_NEW',
          detail: 'A condition this console has no sentence for',
          locationId: null,
        };
        const fixture = await render({ validate: () => Promise.resolve(outcomeOf(unknown)) });

        const text: string = fixture.nativeElement.querySelector('.readiness__row').textContent;
        expect(text.split('A condition this console has no sentence for').length - 1).toBe(1);
        expect(fixture.nativeElement.querySelector('.readiness__scope')).toBeNull();
      });

      it('gives every row its own track key, even when two findings read exactly alike', async () => {
        const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
        const warnings = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
        try {
          const same = [MERCHANT_SECRET, MERCHANT_SECRET, CHANNEL, KIOSK];
          const fixture = await render({ validate: () => Promise.resolve(outcomeOf(...same)) });
          expect(fixture.nativeElement.querySelectorAll('.readiness__row').length).toBe(4);

          // The list is replaced while rows are on screen: this is what makes Angular compare keys.
          fixture.componentInstance['findings'].set([...same]);
          fixture.detectChanges();

          expect(fixture.nativeElement.querySelectorAll('.readiness__row').length).toBe(4);
          expect(duplicateKeyWarnings([errors, warnings])).toEqual([]);
        } finally {
          errors.mockRestore();
          warnings.mockRestore();
        }
      });
    });

    it('treats a finding from an older server, which sends no advisory flag, as blocking', async () => {
      const legacy = {
        stepKey: 'PAYMENT_CONFIGURATION_VALIDATE',
        passed: false,
        errorCode: 'NO_LEGAL_ENTITY',
        detail: 'Location CHI has no active legal entity assigned',
        locationId: 'loc-1',
      };
      const fixture = await render({ validate: () => Promise.resolve(outcomeOf(legacy)) });

      const row: HTMLElement = fixture.nativeElement.querySelector('.readiness__row');
      expect(row.classList.contains('readiness__row--advisory')).toBe(false);
      expect(row.querySelector('.readiness__advisory')).toBeNull();
    });
  });

  describe('batch 16: fulfilment-mode and service-binding coverage, and per-item links', () => {
    type Check = ValidationOutcome['checks'][number];

    const NO_MODE: Check = {
      stepKey: 'CHANNEL_FULFILLMENT_COVERAGE_VALIDATE',
      passed: false,
      errorCode: 'CHANNEL_NO_FULFILLMENT_MODE',
      detail: 'Sales channel STOREFRONT has no enabled fulfilment mode',
      locationId: null,
      subject: { type: 'SALES_CHANNEL', id: 'channel-storefront' },
    };
    const NO_SERVICEABLE: Check = {
      stepKey: 'CHANNEL_FULFILLMENT_COVERAGE_VALIDATE',
      passed: false,
      errorCode: 'CHANNEL_NO_SERVICEABLE_MODE',
      detail:
        'Sales channel KIOSK has enabled fulfilment modes, but none has a schedule bound at an active location the channel serves',
      locationId: null,
      subject: { type: 'SALES_CHANNEL', id: 'channel-kiosk' },
    };
    const NO_SCHEDULE: Check = {
      stepKey: 'LOCATION_SERVICE_BINDING_COVERAGE_VALIDATE',
      passed: false,
      errorCode: 'LOCATION_NO_SERVICE_SCHEDULE',
      detail: 'Location MAIN01 has no schedule bound for DELIVERY, PICKUP',
      locationId: 'location-main',
    };

    function outcomeOf(...checks: Check[]): ValidationOutcome {
      return { allPassed: false, checks };
    }

    function hrefs(fixture: ComponentFixture<SettingsHomePage>): (string | null)[] {
      return [...fixture.nativeElement.querySelectorAll('.readiness__row a')].map(
        (link: HTMLAnchorElement) => link.getAttribute('href'),
      );
    }

    // The per-channel setup hub configures a Telegram bot, a web hostname or a
    // kiosk stub; it has no control that enables a fulfilment mode, binds a
    // location or sets a payment method, so a channel finding must not open it.
    // The sales-channels screen holds the matrices and the location list.
    it('links a channel with no fulfilment mode to the sales-channels screen where the modes are enabled', async () => {
      const fixture = await render({ validate: () => Promise.resolve(outcomeOf(NO_MODE)) });

      expect(hrefs(fixture)).toEqual(['/settings/sales-channels']);
      const row = fixture.nativeElement.querySelector('.readiness__row')?.textContent ?? '';
      expect(row).toContain('no fulfilment mode enabled');
      expect(row).toContain('STOREFRONT');
    });

    it('links a channel whose modes have no hours at any location to the sales-channels screen as well', async () => {
      const fixture = await render({
        validate: () => Promise.resolve(outcomeOf(NO_SERVICEABLE)),
      });

      expect(hrefs(fixture)).toEqual(['/settings/sales-channels']);
      expect(fixture.nativeElement.querySelector('.readiness__row')?.textContent).toContain(
        'none has opening hours bound',
      );
    });

    it('keeps two channels with the same sentence as two rows, both linking to the sales-channels screen', async () => {
      const kiosk: Check = {
        ...NO_MODE,
        detail: 'Sales channel KIOSK has no enabled fulfilment mode',
        subject: { type: 'SALES_CHANNEL', id: 'channel-kiosk' },
      };
      const fixture = await render({
        validate: () => Promise.resolve(outcomeOf(NO_MODE, kiosk)),
      });

      expect(hrefs(fixture)).toEqual(['/settings/sales-channels', '/settings/sales-channels']);
      const rows = [...fixture.nativeElement.querySelectorAll('.readiness__row')].map(
        (row: HTMLElement) => row.textContent ?? '',
      );
      expect(rows[0]).toContain('STOREFRONT');
      expect(rows[1]).toContain('KIOSK');
    });

    it('keeps a no-payment-method finding that names its channel on the sales-channels screen', async () => {
      const fixture = await render({
        validate: () =>
          Promise.resolve(
            outcomeOf({
              stepKey: 'CHANNEL_PAYMENT_COVERAGE_VALIDATE',
              passed: false,
              errorCode: 'CHANNEL_NO_PAYMENT_METHOD',
              detail: 'Sales channel STOREFRONT has no enabled payment method',
              locationId: null,
              subject: { type: 'SALES_CHANNEL', id: 'channel-storefront' },
            }),
          ),
      });

      expect(hrefs(fixture)).toEqual(['/settings/sales-channels']);
    });

    it('keeps linking by error code when the server sends no subject (an older server)', async () => {
      const { subject: _omitted, ...withoutSubject } = NO_MODE;
      const fixture = await render({
        validate: () => Promise.resolve(outcomeOf(withoutSubject)),
      });

      expect(hrefs(fixture)).toEqual(['/settings/sales-channels']);
    });

    it('does not guess a screen for a subject type it does not know', async () => {
      const fixture = await render({
        validate: () =>
          Promise.resolve(
            outcomeOf({ ...NO_MODE, subject: { type: 'FUTURE_THING', id: 'thing-1' } }),
          ),
      });

      expect(hrefs(fixture)).toEqual(['/settings/sales-channels']);
    });

    it('links a location with no schedule bound into that location and names the missing modes', async () => {
      const fixture = await render({ validate: () => Promise.resolve(outcomeOf(NO_SCHEDULE)) });

      expect(hrefs(fixture)).toEqual(['/settings/locations/location-main']);
      const row = fixture.nativeElement.querySelector('.readiness__row')?.textContent ?? '';
      expect(row).toContain('no opening hours bound');
      expect(row).toContain('DELIVERY, PICKUP');
    });

    it('counts both new checks as blocking rows, not advisory ones', async () => {
      const fixture = await render({
        validate: () => Promise.resolve(outcomeOf(NO_MODE, NO_SCHEDULE)),
      });

      expect(fixture.nativeElement.querySelectorAll('.readiness__row').length).toBe(2);
      expect(fixture.nativeElement.querySelector('.readiness__advisory')).toBeNull();
    });
  });

  describe('batch 18: tiers, count order, the three new conditions and live tile numbers (row 10.0)', () => {
    type Check = ValidationOutcome['checks'][number];

    const FORCED_CLOSED: Check = {
      stepKey: 'LOCATION_FORCED_CLOSED_VALIDATE',
      passed: false,
      errorCode: 'LOCATION_FORCED_CLOSED_NO_EXPIRY',
      detail:
        'Location MAIN01 has been closed by hand for 4 days (reason FRYER_BROKEN) with no time set to reopen',
      locationId: 'loc-1',
      advisory: true,
      severity: 'ADVISORY',
    };
    const NO_CHANNEL: Check = {
      stepKey: 'LOCATION_CHANNEL_REACH_VALIDATE',
      passed: false,
      errorCode: 'LOCATION_NO_SALES_CHANNEL',
      detail: 'Location MAIN01 is not switched on for any active sales channel',
      locationId: 'loc-1',
      advisory: false,
      severity: 'BLOCKING',
    };
    const ENDING: Check = {
      stepKey: 'FISCAL_ASSIGNMENT_EXPIRY_VALIDATE',
      passed: false,
      errorCode: 'LOCATION_FISCAL_ASSIGNMENT_ENDING',
      detail:
        'The fiscal assignment of location MAIN01 ends on 2026-10-14 (11 days) and no later assignment covers it',
      locationId: 'loc-2',
      advisory: true,
      severity: 'EXPIRING',
    };
    const NO_PAYMENT: Check = {
      stepKey: 'CHANNEL_PAYMENT_COVERAGE_VALIDATE',
      passed: false,
      errorCode: 'CHANNEL_NO_PAYMENT_METHOD',
      detail: 'Sales channel STOREFRONT has no enabled payment method',
      locationId: null,
      advisory: false,
      severity: 'BLOCKING',
    };

    function outcomeOf(...checks: Check[]): ValidationOutcome {
      return { allPassed: false, checks };
    }

    function rowsOf(fixture: ComponentFixture<SettingsHomePage>): HTMLElement[] {
      return [...fixture.nativeElement.querySelectorAll('.readiness__row')];
    }

    function tileOf(fixture: ComponentFixture<SettingsHomePage>, label: string): HTMLElement {
      const tiles: HTMLElement[] = [...fixture.nativeElement.querySelectorAll('.tile')];
      const tile = tiles.find(
        (node) => node.querySelector('.tile__label')?.textContent?.trim() === label,
      );
      if (!tile) {
        throw new Error(`no tile labelled ${label}`);
      }
      return tile;
    }

    it('says what a branch closed by hand with no end time means, and opens the branch', async () => {
      const fixture = await render({ validate: () => Promise.resolve(outcomeOf(FORCED_CLOSED)) });

      const row = rowsOf(fixture)[0];
      expect(row.textContent).toContain('closed by hand and nothing says when it reopens');
      expect(row.querySelector('a')?.getAttribute('href')).toBe('/settings/locations/loc-1');
      expect(row.querySelector('.readiness__advisory')).not.toBeNull();
    });

    it('sends a branch no channel reaches to the sales-channels screen, not to the branch', async () => {
      const fixture = await render({ validate: () => Promise.resolve(outcomeOf(NO_CHANNEL)) });

      const row = rowsOf(fixture)[0];
      expect(row.textContent).toContain('not switched on for any sales channel');
      expect(row.querySelector('a')?.getAttribute('href')).toBe('/settings/sales-channels');
      expect(row.querySelector('.readiness__advisory')).toBeNull();
    });

    it('lists blocking, then expiring, then advisory, and tags the middle tier', async () => {
      const fixture = await render({
        validate: () => Promise.resolve(outcomeOf(FORCED_CLOSED, ENDING, NO_CHANNEL)),
      });

      const rows = rowsOf(fixture);
      expect(rows.map((row) => row.querySelector('a')?.getAttribute('href'))).toEqual([
        '/settings/sales-channels',
        '/settings/locations/loc-2',
        '/settings/locations/loc-1',
      ]);
      expect(rows[1].classList.contains('readiness__row--expiring')).toBe(true);
      expect(rows[1].querySelector('.readiness__expiring')?.textContent).toContain('Expiring');
      expect(rows[1].querySelector('.readiness__advisory')).toBeNull();
      expect(rows[0].querySelector('.readiness__expiring')).toBeNull();
    });

    it('lists the condition with the most offending items first within a tier', async () => {
      const fixture = await render({
        validate: () =>
          Promise.resolve(
            outcomeOf(
              NO_PAYMENT,
              NO_CHANNEL,
              { ...NO_CHANNEL, locationId: 'loc-2', detail: 'Location B is not switched on' },
              { ...NO_CHANNEL, locationId: 'loc-3', detail: 'Location C is not switched on' },
            ),
          ),
      });

      expect(rowsOf(fixture).map((row) => row.querySelector('a')?.getAttribute('href'))).toEqual([
        '/settings/sales-channels',
        '/settings/sales-channels',
        '/settings/sales-channels',
        '/settings/sales-channels',
      ]);
      const texts = rowsOf(fixture).map((row) => row.textContent ?? '');
      expect(texts.slice(0, 3).every((text) => text.includes('not switched on'))).toBe(true);
      expect(texts[3]).toContain('no payment method');
    });

    it('summarises the three counts above the list', async () => {
      const fixture = await render({
        validate: () => Promise.resolve(outcomeOf(NO_CHANNEL, NO_PAYMENT, ENDING, FORCED_CLOSED)),
      });

      const text = fixture.nativeElement.querySelector(
        '[data-testid="readiness-summary"]',
      ).textContent;
      expect(text).toContain('2 blocking');
      expect(text).toContain('1 expiring');
      expect(text).toContain('1 advisory');
    });

    it('carries the same numbers on the tiles the findings link to, and on no other', async () => {
      const fixture = await render({
        validate: () => Promise.resolve(outcomeOf(NO_CHANNEL, NO_PAYMENT, ENDING, FORCED_CLOSED)),
      });

      const salesChannels = tileOf(fixture, 'Sales channels').querySelector(
        '[data-testid="tile-numbers"]',
      );
      expect(salesChannels?.textContent).toContain('2 blocking');
      expect(salesChannels?.textContent).not.toContain('expiring');

      const locations = tileOf(fixture, 'Locations').querySelector('[data-testid="tile-numbers"]');
      expect(locations?.textContent).toContain('1 expiring');
      expect(locations?.textContent).toContain('1 advisory');
      expect(locations?.textContent).not.toContain('blocking');

      expect(
        tileOf(fixture, 'Brand profile').querySelector('[data-testid="tile-numbers"]'),
      ).toBeNull();
      expect(
        tileOf(fixture, 'Integrations').querySelector('[data-testid="tile-numbers"]'),
      ).toBeNull();
    });

    it('shows no numbers anywhere when everything is set up', async () => {
      const fixture = await render({ validate: () => Promise.resolve(PASSING) });

      expect(fixture.nativeElement.querySelector('[data-testid="tile-numbers"]')).toBeNull();
      expect(fixture.nativeElement.querySelector('[data-testid="readiness-summary"]')).toBeNull();
    });
  });

  it('renders the denied state on a 403 from the readiness check', async () => {
    const fixture = await render({
      validate: () => Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null)),
    });
    expect(fixture.nativeElement.textContent).toContain('owner and administrators');
  });

  function searchInput(fixture: ComponentFixture<SettingsHomePage>): HTMLInputElement {
    return fixture.nativeElement.querySelector('[data-testid="q-combobox-input"]');
  }

  it('filters the index by "/" find-a-setting text, across label and description', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });

    const input = searchInput(fixture);
    input.value = 'fiscalization';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const labels = [...fixture.nativeElement.querySelectorAll('.tile__label')].map(
      (node: Element) => node.textContent?.trim(),
    );
    expect(labels).toEqual(['Fiscalization']);
  });

  it('shows the empty search state when nothing matches', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });

    const input = searchInput(fixture);
    input.value = 'zzzz-no-such-setting';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('.tile__label').length).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('No setting matches');
  });

  it('focuses the search box when "/" is pressed anywhere on the page', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    document.body.appendChild(fixture.nativeElement);

    // The shell's one keydown listener hands the key to the registry; this drives that hand-off.
    const handled = TestBed.inject(ShortcutRegistry).dispatch(
      slashKey(fixture.nativeElement as HTMLElement),
    );

    expect(handled).toBe(true);
    expect(document.activeElement).toBe(searchInput(fixture));

    fixture.nativeElement.remove();
  });

  it('leaves "/" to a text field the operator is typing in', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    document.body.appendChild(fixture.nativeElement);
    const box = document.createElement('input');
    document.body.appendChild(box);

    expect(TestBed.inject(ShortcutRegistry).dispatch(slashKey(box))).toBe(false);

    box.remove();
    fixture.nativeElement.remove();
  });

  it('lists "/" on the cheat-sheet while the page is open, and not after', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    const registry = TestBed.inject(ShortcutRegistry);

    const scope = registry.scopes().find((candidate) => candidate.id === 'settings-home');
    expect(scope?.title()).toBe('Settings');
    expect(scope?.shortcuts.map((shortcut) => shortcut.label())).toEqual(['Find a setting']);

    fixture.destroy();
    expect(registry.scopes().some((candidate) => candidate.id === 'settings-home')).toBe(false);
  });

  // -------------------------------------------------- 10.0: the combobox half

  it('offers a matching configuration key in the combobox, but not one CONFIGURATION_KEY_ROUTES names no screen for', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });

    const input = searchInput(fixture);
    input.value = 'vat';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const options = [
      ...fixture.nativeElement.querySelectorAll('[data-testid="q-combobox-option"]'),
    ];
    expect(options.map((option: Element) => option.textContent)).toEqual([
      expect.stringContaining('ordering.vat_rate_percent'),
    ]);
  });

  it('navigates to the owning screen when a configuration-key result is chosen', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const input = searchInput(fixture);
    input.value = 'vat';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="q-combobox-option"]') as HTMLElement
    ).click();
    fixture.detectChanges();

    expect(navigateSpy).toHaveBeenCalledWith(['/settings', 'order-policy']);
  });

  it('offers and deep-links a reference list by its own section heading', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const input = searchInput(fixture);
    input.value = 'cancellation';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const option = fixture.nativeElement.querySelector(
      '[data-testid="q-combobox-option"]',
    ) as HTMLElement;
    expect(option.textContent).toContain('Cancellation reasons');
    option.click();
    fixture.detectChanges();

    expect(navigateSpy).toHaveBeenCalledWith(['/settings/reference-data'], {
      fragment: 'cancellation-reasons',
    });
  });
});
