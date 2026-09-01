/**
 * The shape of the build-time configuration.
 *
 * In its own file because `environment.development.ts` is *substituted for*
 * `environment.ts` by the build's `fileReplacements`. A development file that
 * imported its type from the file it replaces is a cycle, and esbuild reports it
 * as a confusing "environment is declared here" error rather than as a cycle.
 */
export interface Environment {
  readonly production: boolean;

  /**
   * Origin of the platform API. Empty means same-origin, which is what a
   * reverse-proxied production deployment wants.
   */
  readonly apiBaseUrl: string;
}
