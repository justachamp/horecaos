import { Pipe, PipeTransform } from '@angular/core';

/**
 * Splits terms content by numbered points (e.g. 1., 1.1., 1.1.1.) and returns sections.
 * Each point gets its own section. (?<![.\d]) ensures we don't split in the middle of
 * compound numbers: "1.1. Text" stays one section, not "1." + "1. Text".
 */
@Pipe({ name: 'termsSections', standalone: true })
export class TermsSectionsPipe implements PipeTransform {
  /** Matches start of numbered points. (?<![.\d]) = not after dot/digit (avoids splitting "1.1." into "1." + "1.") */
  private static readonly POINT_REGEX = /(?<![.\d])(?=\d+(?:\.\d+)*\.\s)/;

  transform(value: string | null | undefined): string[] {
    if (!value || typeof value !== 'string') return [];
    const sections = value.split(TermsSectionsPipe.POINT_REGEX);
    return sections.map((s) => s.trim()).filter((s) => s.length > 0);
  }
}
