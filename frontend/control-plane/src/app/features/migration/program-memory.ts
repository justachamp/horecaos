const PROGRAM_KEY = 'horecaos.console.migrationProgram';

/** The migration program chosen last in this browser session, on any migration screen. */
export function rememberedProgram(): string | null {
  try {
    return sessionStorage.getItem(PROGRAM_KEY);
  } catch {
    return null;
  }
}

export function rememberProgram(programId: string): void {
  try {
    sessionStorage.setItem(PROGRAM_KEY, programId);
  } catch {
    // A convenience only: a blocked store means choosing the program again.
  }
}
