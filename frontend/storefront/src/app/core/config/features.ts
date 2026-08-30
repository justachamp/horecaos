/**
 * Feature flags for affordances whose backend does not exist yet.
 *
 * Both flags below gate a whole customer-facing feature, not a visual detail:
 * favourites and the profile avatar are being ported to the platform
 * separately and today's platform build has no endpoint for either. Showing
 * the heart or the avatar picker before that lands is not a cosmetic gap --
 * every tap fails, because `FavouritesService`/`AvatarService` call an
 * endpoint that 404s on every environment this build can reach.
 *
 * A single `const` rather than a runtime remote-config flag: there is no
 * server-side kill switch to wire this to yet, and the whole point is that
 * flipping one line here re-enables the affordance the day the backend lands
 * -- every call site already reads from here rather than deciding for itself.
 */
export const FEATURES = {
  /** Hearts on home/product/food-card, and the profile favourites screen. */
  favourites: false,

  /** The profile avatar picker (upload, replace, remove) and its display. */
  avatar: false,
} as const;
