#!/usr/bin/env python3
"""Proves the map-key guard in repo_hygiene.py can say no (make repo-hygiene-test).

The guard exists because a vendor map key was a literal default in two container images, in a
committed config.json and in the Angular environment files, shared by every deployment (ADR 0145
decision 4). A guard that cannot fail is a ritual, so each case below feeds it a file that has the
old shape of the defect and checks it is refused, and a file that has the new shape and checks it
is not. The "key" used here is invented: the real one is named in the guard only by its SHA-256, and
this file must not put it back in git either.
"""
from __future__ import annotations

import hashlib
import re
import unittest
from pathlib import Path

import repo_hygiene as hygiene

HERE = Path(__file__).parent
STAND_IN = "11111111-2222-3333-4444-555555555555"
STAND_IN_DIGEST = hashlib.sha256(STAND_IN.encode()).hexdigest()
OTHER_UUID = "22222222-3333-4444-5555-666666666666"
KNOWN = frozenset({STAND_IN_DIGEST})


def problems(files: dict[str, str]) -> list[str]:
    return hygiene.map_key_problems(files, KNOWN)


class TheOldShapesAreRefused(unittest.TestCase):
    def test_a_default_in_the_container_entrypoint(self) -> None:
        text = f': "${{APP_YANDEX_MAPS_API_KEY:={STAND_IN}}}"\n'

        found = problems({"frontend/storefront/docker-entrypoint.sh": text})

        self.assertTrue(found)
        self.assertIn("docker-entrypoint.sh:1", found[0])

    def test_a_key_in_a_committed_config_json(self) -> None:
        text = f'{{\n  "apiBaseUrl": "/api/v1",\n  "yandexMapsApiKey": "{STAND_IN}",\n  "channel": "STOREFRONT"\n}}\n'

        found = problems({"frontend/storefront/public/config.json": text})

        self.assertEqual(len(found), 2, "the known key, and a literal on a map-key line")
        self.assertTrue(all(":3:" in line for line in found))

    def test_a_key_in_an_angular_environment_file(self) -> None:
        text = f"export const environment = {{\n  yandexMapsApiKey: '{STAND_IN}',\n}};\n"

        self.assertTrue(problems({"frontend/storefront/src/environments/environment.ts": text}))

    def test_the_known_key_is_refused_wherever_it_hides_even_on_a_line_that_does_not_say_what_it_is(self) -> None:
        for text in (f"const k = '{STAND_IN}';", f"KEY={STAND_IN.upper()}", f"# was {STAND_IN}"):
            with self.subTest(text=text):
                found = problems({"somewhere/else.ts": text})
                self.assertTrue(any("compromised" in line for line in found))

    def test_a_different_key_is_refused_too_because_the_defect_was_a_literal_and_not_one_value(self) -> None:
        text = f'  "yandexMapsApiKey": "{OTHER_UUID}",\n'

        found = problems({"frontend/storefront-milliy/public/config.json": text})

        self.assertEqual(len(found), 1)
        self.assertIn("literal", found[0])

    def test_other_spellings_of_a_map_key_variable(self) -> None:
        for spelling in ("MAPS_API_KEY", "mapsApiKey", "map_key", "apikey", "YANDEX_KEY"):
            with self.subTest(spelling=spelling):
                self.assertTrue(problems({"a.sh": f"{spelling}={OTHER_UUID}\n"}))


class TheNewShapesPass(unittest.TestCase):
    def test_a_variable_with_no_default_and_a_config_without_a_key(self) -> None:
        files = {
            "frontend/storefront/docker-entrypoint.sh": ': "${APP_CHANNEL:=STOREFRONT}"\n',
            "frontend/storefront/public/config.json": '{\n  "apiBaseUrl": "/api/v1",\n  "channel": "STOREFRONT"\n}\n',
            "deploy/env.template": "HORECAOS_GEO_YANDEX_BROWSER_KEY=\n",
        }

        self.assertEqual(problems(files), [])

    def test_a_uuid_on_a_line_that_is_not_about_a_map_key(self) -> None:
        files = {
            "frontend/storefront/public/config.json": f'  "tenantId": "{OTHER_UUID}",\n',
            "platform/docs/x.md": f"The tenant {OTHER_UUID} owns the region.\n",
        }

        self.assertEqual(problems(files), [])

    def test_prose_about_the_key_with_no_literal_in_it(self) -> None:
        text = "The Yandex browser key is delivered by GET .../map-config and is never committed.\n"

        self.assertEqual(problems({"README.md": text}), [])


class TheGuardAsShipped(unittest.TestCase):
    def test_it_names_the_compromised_key_by_digest_only(self) -> None:
        self.assertEqual(len(hygiene.COMPROMISED_MAP_KEY_DIGESTS), 1)
        self.assertTrue(all(re.fullmatch(r"[0-9a-f]{64}", d) for d in hygiene.COMPROMISED_MAP_KEY_DIGESTS))
        source = (HERE / "repo_hygiene.py").read_text(encoding="utf-8")
        shaped = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        self.assertEqual(shaped.findall(source), [], "the guard must not spell the key it keeps out of git")
        self.assertEqual(shaped.findall(Path(__file__).read_text(encoding="utf-8")).count(STAND_IN), 1)

    def test_the_tracked_tree_is_clean(self) -> None:
        hygiene.failures.clear()
        hygiene.check_no_committed_map_key()

        self.assertEqual(
            [name for name in hygiene.failures if "map key" in name],
            [],
            "a tracked file carries a map key literal; run tools/checks/repo_hygiene.py to see which",
        )


if __name__ == "__main__":
    unittest.main()
