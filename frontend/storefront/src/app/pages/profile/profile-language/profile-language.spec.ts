import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { CustomerProfileService } from '../../../services/customer-profile.service';
import { LangService, type LanguageOption } from '../../../services/lang.service';
import { TranslateService } from '../../../services/translate.service';
import { ProfileLanguageComponent } from './profile-language';

const OFFERED: readonly LanguageOption[] = [
  { id: 'ru', tag: 'ru', label: 'Русский' },
  { id: 'uz', tag: 'uz-Latn', label: 'Oʻzbekcha' },
];

class FakeLangService {
  readonly langId = signal('ru');
  readonly offered = signal(OFFERED);
  load(): Promise<void> {
    return Promise.resolve();
  }
}

/**
 * ADR 0149, Decision 5: the profile language screen offers what the language service offers, and the
 * platform is told the registry's tag for the choice, never this app's catalogue id.
 */
describe('ProfileLanguageComponent', () => {
  function setUp(profile: unknown = { id: 'customer-1' }) {
    const update = vi.fn().mockResolvedValue(undefined);
    const setLang = vi.fn();
    TestBed.configureTestingModule({
      imports: [ProfileLanguageComponent],
      providers: [
        { provide: LangService, useClass: FakeLangService },
        {
          provide: TranslateService,
          useValue: { setLang, loadTranslations: vi.fn(), get: (key: string) => key },
        },
        {
          provide: CustomerProfileService,
          useValue: { profile: () => profile, update },
        },
      ],
    });
    const fixture = TestBed.createComponent(ProfileLanguageComponent);
    fixture.detectChanges();
    return { fixture, update, setLang };
  }

  it('lists the languages on offer by their own names, with no flag', () => {
    const { fixture } = setUp();

    const rows = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('li button'),
    ).map((row) => row.textContent?.replace(/\s+/g, ' ').trim());
    expect(rows).toEqual(['Русский', 'Oʻzbekcha']);
  });

  it('switches the interface to the catalogue id and tells the platform the registry’s tag', () => {
    const { fixture, update, setLang } = setUp();

    (
      (fixture.nativeElement as HTMLElement).querySelectorAll('li button')[1] as HTMLElement
    ).click();

    expect(setLang).toHaveBeenCalledWith('uz');
    expect(update).toHaveBeenCalledWith({ locale: 'uz-Latn' });
  });

  it('does not write a guest’s choice anywhere', () => {
    const { fixture, update, setLang } = setUp(null);

    (
      (fixture.nativeElement as HTMLElement).querySelectorAll('li button')[0] as HTMLElement
    ).click();

    expect(setLang).toHaveBeenCalledWith('ru');
    expect(update).not.toHaveBeenCalled();
  });
});
