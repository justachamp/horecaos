import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { SettingsHomePage } from './settings-home-page';

describe('SettingsHomePage', () => {
  let fixture: ComponentFixture<SettingsHomePage>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SettingsHomePage],
      providers: [provideRouter([])],
    }).compileComponents();

    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(SettingsHomePage);
    fixture.detectChanges();
  });

  it('renders every P-tier screen and the moved Integrations screen as a tile', () => {
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
  });

  it('badges a screen that has no built route yet', () => {
    const tiles = [...fixture.nativeElement.querySelectorAll('.tile')];
    const channelSetup = tiles.find((tile: Element) => tile.textContent?.includes('Channel setup'));
    expect(channelSetup?.querySelector('.tile__badge')).toBeTruthy();

    const salesChannels = tiles.find((tile: Element) =>
      tile.textContent?.includes('Sales channels'),
    );
    expect(salesChannels?.querySelector('.tile__badge')).toBeFalsy();
  });

  it('groups tiles under the spec nav groups, not an alphabetical list', () => {
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
    ]);
  });
});
