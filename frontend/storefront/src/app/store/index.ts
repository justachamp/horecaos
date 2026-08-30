/**
 * NgRx Signal Store - Global state management
 *
 * Stores are provided at root (providedIn: 'root') and can be injected anywhere:
 *
 * @example
 * ```ts
 * import { inject } from '@angular/core';
 * import { AppStore } from './store';
 *
 * @Component({...})
 * export class MyComponent {
 *   private readonly appStore = inject(AppStore);
 *
 *   // Read state (signals)
 *   loading = this.appStore.loading;
 *   error = this.appStore.error;
 *   hasError = this.appStore.hasError; // computed
 *
 *   // Call methods
 *   setLoading() {
 *     this.appStore.setLoading(true);
 *   }
 * }
 * ```
 *
 * @see https://ngrx.io/guide/signals/signal-store
 */

export { AppStore } from './app.store';
export type { AppState } from './app.store';
