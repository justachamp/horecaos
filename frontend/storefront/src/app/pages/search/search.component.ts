import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  AfterViewInit,
  ElementRef,
  ViewChild,
  inject,
  signal,
  computed,
  DestroyRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { NavigationHistoryService } from '../../services/navigation-history.service';
import { Subject, from, switchMap, EMPTY } from 'rxjs';
import { debounceTime, distinctUntilChanged, tap } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FoodCardComponent } from '../../shared/food-card/food-card.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { CartHintBadgeComponent } from '../../shared/cart-hint-badge/cart-hint-badge.component';
import { MenuService } from '../../services/menu.service';
import { LangService } from '../../services/lang.service';
import { RecentSearchesService } from '../../services/recent-searches.service';
import { UiCartService } from '../../services/ui-cart.service';
import type { MenuItem } from '../../types/home.types';

const SEARCH_PATTERN = /^[a-zA-Z0-9 ЁёА-я]+$/;
const MIN_LENGTH = 2;
const STORAGE_KEY = 'mar_search_query';

@Component({
  selector: 'app-search',
  standalone: true,
  templateUrl: './search.component.html',
  styleUrl: './search.component.scss',
  imports: [CommonModule, FoodCardComponent, TranslatePipe, CartHintBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SearchComponent implements OnInit, AfterViewInit {
  @ViewChild('searchInput') searchInput!: ElementRef<HTMLInputElement>;

  private readonly menuService = inject(MenuService);
  private readonly langService = inject(LangService);
  private readonly recent = inject(RecentSearchesService);
  private readonly history = inject(NavigationHistoryService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly cartService = inject(UiCartService);

  readonly hasCartItems = computed(() => this.cartService.totalItemsCount() > 0);

  private readonly searchQuery$ = new Subject<string>();

  readonly query = signal('');
  readonly loading = signal(false);
  readonly results = signal<MenuItem[]>([]);
  readonly recentLabels = signal<string[]>([]);
  readonly recentLoading = signal(false);

  readonly showRecent = computed(
    () => this.query().trim().length < MIN_LENGTH && this.recentLabels().length > 0,
  );

  readonly noResults = computed(
    () => this.query().trim().length >= MIN_LENGTH && !this.loading() && this.results().length === 0,
  );

  constructor() {
    this.searchQuery$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        tap((q) => this.query.set(q)),
        switchMap((q) => {
          const trimmed = q.trim();
          if (trimmed.length < MIN_LENGTH || !SEARCH_PATTERN.test(trimmed)) {
            this.results.set([]);
            this.loading.set(false);
            return EMPTY;
          }
          this.loading.set(true);
          sessionStorage.setItem(STORAGE_KEY, trimmed);
          this.recent.remember(trimmed);
          // Client-side, over the menu already loaded. The platform has no
          // search endpoint and one location's menu does not need one.
          return from(this.menuService.search(trimmed, this.langService.langId()));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (items) => {
          this.results.set(items);
          this.loading.set(false);
        },
        error: () => {
          this.results.set([]);
          this.loading.set(false);
        },
      });
  }

  ngOnInit(): void {
    if (!sessionStorage.getItem(STORAGE_KEY)) {
      this.loadRecentSearches();
    }
  }

  /**
   * Recent searches, from this device.
   *
   * The legacy backend kept them per account at
   * `/customers/items/searches/recently-searched`; the platform has no such
   * endpoint and storing a customer's search history server-side is not
   * something to add on the way past -- it is personal data with a retention
   * question attached. Kept locally instead, which is what the feature is
   * actually for: not retyping something from a minute ago.
   */
  private loadRecentSearches(): void {
    this.recentLabels.set(this.recent.list());
  }

  ngAfterViewInit(): void {
    const saved = sessionStorage.getItem(STORAGE_KEY);
    if (saved) {
      this.searchInput.nativeElement.value = saved;
      this.searchQuery$.next(saved);
    }
    setTimeout(() => {
      const el = this.searchInput?.nativeElement;
      if (!el) return;
      el.click();
      el.focus({ preventScroll: true });
    }, 50);
  }

  onInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.searchQuery$.next(value);
  }

  selectRecent(label: string): void {
    this.searchInput.nativeElement.value = label;
    this.searchQuery$.next(label);
  }

  clearSearch(): void {
    this.searchInput.nativeElement.value = '';
    this.query.set('');
    this.searchQuery$.next('');
    this.results.set([]);
    sessionStorage.removeItem(STORAGE_KEY);
    this.loadRecentSearches();
    this.searchInput.nativeElement.focus();
  }

  goBack(): void {
    sessionStorage.removeItem(STORAGE_KEY);
    this.history.back('/home');
  }
}
