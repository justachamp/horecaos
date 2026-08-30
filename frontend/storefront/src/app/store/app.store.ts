import {
  signalStore,
  withState,
  withComputed,
  withMethods,
  patchState,
} from '@ngrx/signals';

/** Global app state - available app-wide via inject(AppStore) */
export interface AppState {
  loading: boolean;
  error: string | null;
}

const initialState: AppState = {
  loading: false,
  error: null,
};

export const AppStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed((store) => ({
    hasError: () => store.error() !== null,
  })),
  withMethods((store) => ({
    setLoading(loading: boolean) {
      patchState(store, { loading });
    },
    setError(error: string | null) {
      patchState(store, { error });
    },
    clearError() {
      patchState(store, { error: null });
    },
  }))
);
