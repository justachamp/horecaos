import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ConditionGroup, ConditionRow, ConditionTypeDescriptor } from './condition-types';
import { ExternalRuleResult, RuleSimulator, SimulatedRule } from './rule-simulator';

const CATALOGUE: readonly ConditionTypeDescriptor[] = [
  {
    type: 'MIN_ORDER_MINOR',
    labelKey: 'customers.segments.predicate.type.NET_SPEND_MINOR',
    valueKind: 'MONEY_MINOR',
  },
  {
    type: 'CHANNEL',
    labelKey: 'customers.segments.predicate.type.ACQUISITION_CHANNEL',
    valueKind: 'TEXT_SET',
  },
  {
    type: 'DELIVERY_DAY',
    labelKey: 'customers.segments.predicate.type.RECENCY_DAYS',
    valueKind: 'DAY_OF_WEEK_SET',
  },
  // Deliberately unused by every rule below — proves `typesInUse` filters the
  // catalogue down rather than asking for a value nothing checks.
  {
    type: 'UNUSED_TYPE',
    labelKey: 'customers.segments.predicate.type.BIRTHDAY_WITHIN_DAYS',
    valueKind: 'NUMERIC',
  },
];

function row(overrides: Partial<ConditionRow>): ConditionRow {
  return {
    id: `row-${Math.random()}`,
    type: 'MIN_ORDER_MINOR',
    operator: 'AT_LEAST',
    numericLow: '',
    numericHigh: '',
    dateLow: '',
    dateHigh: '',
    textValues: '',
    dayOfWeekValues: [],
    ...overrides,
  };
}

function group(overrides: Partial<ConditionGroup>): ConditionGroup {
  return { id: `group-${Math.random()}`, combinator: 'AND', rows: [], ...overrides };
}

const RULES: readonly SimulatedRule[] = [
  {
    id: 'promo-a',
    label: '10% off orders over 100 000',
    enabled: true,
    groups: [
      group({
        rows: [row({ type: 'MIN_ORDER_MINOR', operator: 'AT_LEAST', numericLow: '100000' })],
      }),
    ],
    outcome: '10% off',
  },
  {
    id: 'promo-b',
    label: 'Free delivery for organic or referral',
    enabled: true,
    groups: [
      group({ rows: [row({ type: 'CHANNEL', operator: 'IN', textValues: 'organic, referral' })] }),
    ],
    outcome: 'Free delivery',
  },
  {
    id: 'promo-c',
    label: 'Retired promo',
    enabled: false,
    groups: [
      group({ rows: [row({ type: 'MIN_ORDER_MINOR', operator: 'AT_LEAST', numericLow: '0' })] }),
    ],
    outcome: 'Would always match if it were enabled',
  },
  {
    id: 'promo-d',
    label: 'Friday courier bonus',
    enabled: true,
    groups: [
      group({ rows: [row({ type: 'DELIVERY_DAY', operator: 'IN', dayOfWeekValues: [5] })] }),
    ],
    outcome: 'Courier bonus, Friday',
  },
  {
    id: 'promo-e',
    label: 'VIP big order combo',
    enabled: true,
    groups: [
      group({
        combinator: 'AND',
        rows: [
          row({ type: 'MIN_ORDER_MINOR', operator: 'AT_LEAST', numericLow: '50000' }),
          row({ type: 'CHANNEL', operator: 'IN', textValues: 'vip' }),
        ],
      }),
    ],
    outcome: 'Priority pack + free delivery',
  },
];

@Component({
  selector: 'q-rule-simulator-host',
  imports: [RuleSimulator],
  template: `<q-rule-simulator [catalogue]="catalogue" [rules]="rules" />`,
})
class RuleSimulatorHost {
  readonly catalogue = CATALOGUE;
  readonly rules = RULES;
}

@Component({
  selector: 'q-rule-simulator-empty-host',
  imports: [RuleSimulator],
  template: `<q-rule-simulator [catalogue]="catalogue" [rules]="[]" />`,
})
class RuleSimulatorEmptyHost {
  readonly catalogue = CATALOGUE;
}

@Component({
  selector: 'q-rule-simulator-engine-host',
  imports: [RuleSimulator],
  template: `<q-rule-simulator [catalogue]="catalogue" [rules]="[]" [externalResults]="results" />`,
})
class RuleSimulatorEngineHost {
  readonly catalogue = CATALOGUE;
  readonly results: readonly ExternalRuleResult[] = [
    { id: 'a', label: 'LUNCH-5', state: 'matched', detail: 'Took 9 000' },
    { id: 'b', label: 'SAVE10', state: 'unmatched', detail: 'Lost to LUNCH-10' },
    { id: 'c', label: 'OLD', state: 'disabled', detail: '' },
  ];
}

const KIND_CATALOGUE: readonly ConditionTypeDescriptor[] = [
  {
    type: 'LUNCH',
    labelKey: 'customers.segments.predicate.type.RECENCY_DAYS',
    valueKind: 'TIME_RANGE',
  },
  {
    type: 'FIRST',
    labelKey: 'customers.segments.predicate.type.ORDER_COUNT',
    valueKind: 'FLAG',
  },
];

@Component({
  selector: 'q-rule-simulator-kinds-host',
  imports: [RuleSimulator],
  template: `<q-rule-simulator [catalogue]="catalogue" [rules]="rules" />`,
})
class RuleSimulatorKindsHost {
  readonly catalogue = KIND_CATALOGUE;
  readonly rules: readonly SimulatedRule[] = [
    {
      id: 'lunch',
      label: 'Lunch offer',
      enabled: true,
      groups: [
        group({
          rows: [
            row({ type: 'LUNCH', operator: 'BETWEEN', numericLow: '720', numericHigh: '900' }),
          ],
        }),
      ],
      outcome: '10% off',
    },
    {
      id: 'first',
      label: 'First order',
      enabled: true,
      groups: [group({ rows: [row({ type: 'FIRST', operator: 'EQUALS' })] })],
      outcome: 'Free dessert',
    },
  ];
}

describe('RuleSimulator', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<RuleSimulatorHost>>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RuleSimulatorHost);
    fixture.detectChanges();
    host = fixture.nativeElement;
  });

  function rows(): HTMLLIElement[] {
    return [...host.querySelectorAll<HTMLLIElement>('.result-row')];
  }

  function candidateField(type: string): HTMLElement {
    const fields = [...host.querySelectorAll<HTMLLabelElement>('.candidate-field')];
    const field = fields.find((f) => f.textContent?.includes(labelFor(type)));
    if (!field) {
      throw new Error(`no candidate field rendered for ${type}`);
    }
    return field;
  }

  function labelFor(type: string): string {
    const descriptor = CATALOGUE.find((d) => d.type === type)!;
    return descriptor.labelKey.endsWith('NET_SPEND_MINOR')
      ? 'Net spend'
      : descriptor.labelKey.endsWith('ACQUISITION_CHANNEL')
        ? 'Registration source'
        : 'Days since last order';
  }

  it('renders a candidate field only for condition types the rule set actually mentions, in catalogue order', () => {
    const fields = [...host.querySelectorAll('.candidate-field')];
    expect(fields).toHaveLength(3); // MIN_ORDER_MINOR, CHANNEL, DELIVERY_DAY — never a fourth, unused type
  });

  it('renders every rule in the priority order it was given, none matching against an empty candidate', () => {
    expect(rows()).toHaveLength(5);
    expect(rows().map((r) => r.textContent?.includes('10% off orders'))).toEqual([
      true,
      false,
      false,
      false,
      false,
    ]);
    // Nothing matches an all-blank candidate — every enabled rule reads "Not matched".
    expect(rows()[0].textContent).toContain('Not matched');
    expect(rows()[1].textContent).toContain('Not matched');
  });

  it('marks the disabled rule as disabled and never evaluates it, even when its own condition is trivially true', () => {
    const disabledRow = rows()[2];
    expect(disabledRow.textContent).toContain('Retired promo');
    expect(disabledRow.textContent).toContain('Disabled');
    expect(disabledRow.textContent).not.toContain('Matched');
  });

  it('matches a numeric AT_LEAST rule once the candidate clears the threshold, and shows its outcome', () => {
    const input = candidateField('MIN_ORDER_MINOR').querySelector('input') as HTMLInputElement;
    input.value = '150000';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const promoARow = rows()[0];
    expect(promoARow.textContent).toContain('Matched');
    expect(promoARow.textContent).toContain('10% off');
  });

  it('matches an IN text-set rule from a comma-separated candidate value', () => {
    const input = candidateField('CHANNEL').querySelector('input') as HTMLInputElement;
    input.value = 'referral';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(rows()[1].textContent).toContain('Matched');
    expect(rows()[1].textContent).toContain('Free delivery');
  });

  it('matches a DAY_OF_WEEK_SET rule only for the selected day, toggling as the candidate day changes', () => {
    const dayField = candidateField('DELIVERY_DAY');
    const fridayChip = [...dayField.querySelectorAll<HTMLButtonElement>('.dow-chip')].find(
      (b) => b.textContent?.trim() === 'Fri',
    )!;
    const mondayChip = [...dayField.querySelectorAll<HTMLButtonElement>('.dow-chip')].find(
      (b) => b.textContent?.trim() === 'Mon',
    )!;

    fridayChip.click();
    fixture.detectChanges();
    expect(rows()[3].textContent).toContain('Matched');

    mondayChip.click();
    fixture.detectChanges();
    expect(rows()[3].textContent).toContain('Not matched');
  });

  it('requires every row in an AND group before the rule matches', () => {
    const moneyInput = candidateField('MIN_ORDER_MINOR').querySelector('input') as HTMLInputElement;
    moneyInput.value = '60000';
    moneyInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // Only the money condition of the two-row AND group is satisfied so far.
    expect(rows()[4].textContent).toContain('Not matched');

    const channelInput = candidateField('CHANNEL').querySelector('input') as HTMLInputElement;
    channelInput.value = 'vip';
    channelInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(rows()[4].textContent).toContain('Matched');
    expect(rows()[4].textContent).toContain('Priority pack');
  });

  it('shows the honest empty state when there is nothing to simulate', () => {
    const emptyFixture = TestBed.createComponent(RuleSimulatorEmptyHost);
    emptyFixture.detectChanges();
    expect(emptyFixture.nativeElement.textContent).toContain('No rules to simulate.');
  });

  it("renders an engine host's verdicts instead of evaluating locally, and hides the candidate form", () => {
    const engineFixture = TestBed.createComponent(RuleSimulatorEngineHost);
    engineFixture.detectChanges();
    const engineHost: HTMLElement = engineFixture.nativeElement;

    expect(engineHost.querySelector('.candidate')).toBeNull();
    const engineRows = [...engineHost.querySelectorAll<HTMLLIElement>('.result-row')];
    expect(engineRows).toHaveLength(3);
    expect(engineRows[0].textContent).toContain('Matched');
    expect(engineRows[0].textContent).toContain('What happens: Took 9 000');
    expect(engineRows[1].textContent).toContain('Not matched');
    expect(engineRows[1].textContent).toContain('Why: Lost to LUNCH-10');
    expect(engineRows[2].textContent).toContain('Disabled');
  });

  it('keeps the local candidate form when no engine verdicts are given', () => {
    expect(host.querySelector('.candidate')).not.toBeNull();
  });

  it('asks for a clock time and a yes/no for the two new kinds, and judges the rule against them', () => {
    const kindFixture = TestBed.createComponent(RuleSimulatorKindsHost);
    kindFixture.detectChanges();
    const kindHost: HTMLElement = kindFixture.nativeElement;
    const rowsOf = () => [...kindHost.querySelectorAll<HTMLLIElement>('.result-row')];

    const time = kindHost.querySelector<HTMLInputElement>('input[type="time"]')!;
    time.value = '13:00';
    time.dispatchEvent(new Event('input'));
    kindFixture.detectChanges();
    expect(rowsOf()[0].textContent).toContain('Matched');

    time.value = '16:00';
    time.dispatchEvent(new Event('input'));
    kindFixture.detectChanges();
    expect(rowsOf()[0].textContent).toContain('Not matched');

    const flag = kindHost.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    expect(rowsOf()[1].textContent).toContain('Not matched');
    flag.checked = true;
    flag.dispatchEvent(new Event('change'));
    kindFixture.detectChanges();
    expect(rowsOf()[1].textContent).toContain('Matched');
  });
});
