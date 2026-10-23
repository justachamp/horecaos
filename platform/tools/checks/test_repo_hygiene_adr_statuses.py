#!/usr/bin/env python3
"""Proves the ADR status guard in repo_hygiene.py can say no (make hygiene-map-key-test).

A record that carries two `- Implementation status:` blocks says two things at once: ADR 0145 listed the
storefront migration, the key removal, the bake-off harness and the processor-register entry as built in
one block and as «Not built» in another, and a reader of the header got both. Each case below feeds the
guard the shape of that defect and checks it is refused, and the shapes of a clean record and checks they
are not. The last case runs the guard over the tracked records, so a record that regains a second block
fails here as well as in `make lint`.
"""
from __future__ import annotations

import unittest

import repo_hygiene as hygiene

CLEAN = """# ADR 9999: Something

- Decision status: Accepted
- Implementation status: Partial — the server is built; the screens are not.
  A continuation line, indented, is still the same block.
- Date proposed: 2026-10-01

## Decision

We will do it.
"""


def problems(text: str, path: str = "docs/adr/partial/9999-something.md") -> list[str]:
    return hygiene.adr_status_problems({path: text})


class TheDefectIsRefused(unittest.TestCase):
    def test_a_second_implementation_status_block_in_the_header(self) -> None:
        text = CLEAN.replace(
            "- Date proposed:",
            "- Implementation status: Partial — Not built: the storefront migration.\n- Date proposed:",
        )

        found = problems(text)

        self.assertEqual(len(found), 1)
        self.assertIn("9999-something.md", found[0])
        self.assertIn("2 '- Implementation status:' blocks", found[0])

    def test_three_blocks_are_counted(self) -> None:
        text = CLEAN + "\n- Implementation status: Built\n- Implementation status: Not started\n"

        self.assertIn("3 '- Implementation status:' blocks", problems(text)[0])

    def test_a_record_with_no_implementation_status_at_all(self) -> None:
        text = CLEAN.replace("- Implementation status:", "- Progress:")

        self.assertEqual(problems(text), ["docs/adr/partial/9999-something.md: no implementation status"])

    def test_a_record_with_no_decision_status(self) -> None:
        text = CLEAN.replace("- Decision status:", "- Verdict:")

        self.assertEqual(problems(text), ["docs/adr/partial/9999-something.md: no decision status"])


class TheShapesThatAreFine(unittest.TestCase):
    def test_one_block_with_continuation_lines(self) -> None:
        self.assertEqual(problems(CLEAN), [])

    def test_a_sentence_that_mentions_the_field_is_not_a_second_block(self) -> None:
        text = CLEAN + "\nThe Implementation status: line above is machine-read.\n"
        text += "A note: the `- Implementation status:` field moves with the code.\n"

        self.assertEqual(problems(text), [])


class TheGuardAsShipped(unittest.TestCase):
    def test_the_tracked_records_each_carry_one_block(self) -> None:
        hygiene.failures.clear()
        hygiene.check_adr_statuses()

        self.assertEqual(
            [name for name in hygiene.failures if name.startswith("adr:")],
            [],
            "an ADR carries a second '- Implementation status:' block; run tools/checks/repo_hygiene.py to see which",
        )


if __name__ == "__main__":
    unittest.main()
