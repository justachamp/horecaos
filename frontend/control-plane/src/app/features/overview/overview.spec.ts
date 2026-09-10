import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { Overview } from './overview';
import { PlatformHealth, PlatformHealthApi } from './platform-health-api';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const HEALTH: PlatformHealth = {
  measuredAt: '2026-09-11T04:00:00Z',
  tenantsByStatus: { ACTIVE: 12, PROVISIONING: 2, SUSPENDED: 1 },
  orders: { lastHour: 31, lastDay: 540, liveByStatus: { PREPARING: 4, READY: 2 }, oldestLiveAgeSeconds: 1500 },
  receipts: { lastDayByStatus: { ISSUED: 500, BLOCKED: 3 }, blocked: 5 },
  queues: {
    outbox: [{ name: 'orders.events', pending: 7, oldestAgeSeconds: 1200 }],
    inbox: [{ name: 'fiscal-consumer', pending: 2, oldestAgeSeconds: 30 }],
    outboxDeadLetters: 1,
    inboxDeadLetters: 2,
  },
};

describe('Overview', () => {
  let fixture: ComponentFixture<Overview>;

  it('shows exact platform figures, and marks what has waited too long', async () => {
    await TestBed.configureTestingModule({
      imports: [Overview],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: PlatformHealthApi, useValue: { health: vi.fn().mockResolvedValue(HEALTH) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Overview);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();

    const tiles = Array.from(fixture.nativeElement.querySelectorAll('.tile')) as HTMLElement[];
    expect(tiles[0].textContent).toContain('12');
    expect(tiles[0].textContent).toContain('1 ' + ru['overview.tenants.other']);
    expect(tiles[1].textContent).toContain('31');
    expect(tiles[1].querySelector('.alert')?.textContent).toContain('25');
    expect(tiles[2].classList).toContain('tileAlert');
    expect(tiles[2].textContent).toContain('500');
    expect(tiles[3].classList).toContain('tileAlert');
    expect(tiles[3].textContent).toContain('20');
  });
});
