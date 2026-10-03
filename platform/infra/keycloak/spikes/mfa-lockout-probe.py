#!/usr/bin/env python3
"""
Evidence for ADR 0148 (staff multi-factor authentication): what Keycloak's
brute-force detector does when a password-only "probe" client sits beside the
real sign-in client.

Run it against a THROWAWAY Keycloak 26.7.0 in dev mode, never a shared realm. It
deletes and recreates a realm called `mfa-lockout-probe`:

    docker run -d --name kc-mfa-probe -p 18091:8080 \\
        -e KC_BOOTSTRAP_ADMIN_USERNAME=probe-admin \\
        -e KC_BOOTSTRAP_ADMIN_PASSWORD="$(openssl rand -hex 12)" \\
        quay.io/keycloak/keycloak:26.7.0 start-dev
    # read the password back with: docker inspect kc-mfa-probe (Config.Env)

    KC_URL=http://localhost:18091 KC_ADMIN_USER=probe-admin \\
        KC_ADMIN_PASSWORD=... python3 infra/keycloak/spikes/mfa-lockout-probe.py

The realm copies the brute-force settings of infra/keycloak/realm/horecaos-realm.json
(failureFactor 8, waitIncrement 60 s, maxFailureWait 900 s, maxDeltaTime 43200 s,
quick-login check 1000 ms / 60 s). Every credential the script uses is generated
for the run and discarded with the realm.

It prints each observation and exits non-zero if one of the facts ADR 0148 rests
on no longer holds, so re-run it when the pinned Keycloak image changes:

  Controls  a missing code is refused as invalid_grant, like a wrong password; a
            one-time code cannot be used twice inside its 30-second step; the
            admin API adds an OTP credential to a user that already exists.
  1.        a wrong one-time code is counted as a failure, and the account is
            disabled at the failureFactor.
  2.        a successful password-only grant by another client clears that count,
            so a guesser who follows every wrong code with a probe is never
            locked out.
  3.        probing only when no code was sent does not help: one code-less
            request between guesses clears the count just the same.
  4.        one wrong password followed at once by a failing probe is two failures
            milliseconds apart, which trips the quick-login check and disables
            the account for a minute.
  5.        with the probe asked first, the same wrong password is one failure and
            nothing is disabled.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("KC_URL", "http://localhost:18091")
ADMIN_USER = os.environ.get("KC_ADMIN_USER", "")
ADMIN_PASSWORD = os.environ.get("KC_ADMIN_PASSWORD", "")
REALM = "mfa-lockout-probe"

# Generated per run; nothing here is a real credential.
USER_PASSWORD = "Pw-" + secrets.token_urlsafe(18)
OTP_SECRET = secrets.token_hex(10)  # 20 characters, stored raw by Keycloak
LOGIN_SECRET = secrets.token_urlsafe(24)
PROBE_SECRET = secrets.token_urlsafe(24)

FAILED: list[str] = []


def http(method, url, data=None, headers=None, form=False):
    headers = dict(headers or {})
    body = None
    if data is not None:
        if form:
            body = urllib.parse.urlencode(data).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request) as response:
            raw = response.read()
            parsed = json.loads(raw) if raw and "json" in response.headers.get("Content-Type", "") else raw
            return response.status, parsed
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, raw


def admin(method, path, data=None):
    status, body = http(
        "POST",
        f"{BASE}/realms/master/protocol/openid-connect/token",
        {"grant_type": "password", "client_id": "admin-cli", "username": ADMIN_USER, "password": ADMIN_PASSWORD},
        form=True,
    )
    if status != 200:
        sys.exit(f"admin sign-in failed ({status}); check KC_ADMIN_USER and KC_ADMIN_PASSWORD")
    return http(method, f"{BASE}/admin/realms/{path}", data, {"Authorization": "Bearer " + body["access_token"]})


USED_STEPS: set[int] = set()


def code_for(step: int) -> str:
    mac = hmac.new(OTP_SECRET.encode(), struct.pack(">Q", step), hashlib.sha1).digest()
    cut = mac[-1] & 0xF
    return "%06d" % ((struct.unpack(">I", mac[cut : cut + 4])[0] & 0x7FFFFFFF) % 1_000_000)


def fresh_code() -> str:
    """A valid code for a 30-second step this run has not used yet (Keycloak refuses a replay).

    Steps only ever move forward, so this is safe whether Keycloak remembers the
    codes it has seen or only the last counter. The look-ahead window is one step.
    """
    while True:
        current = int(time.time() // 30)
        step = max(max(USED_STEPS, default=-1) + 1, current)
        if step <= current + 1:
            USED_STEPS.add(step)
            return code_for(step)
        time.sleep((current + 1) * 30 - time.time() + 0.2)


def wrong_code(i: int) -> str:
    current = int(time.time() // 30)
    valid = {code_for(step) for step in range(current - 2, current + 3)}
    candidate = "%06d" % ((int(code_for(current)) + 1000 + i) % 1_000_000)
    return candidate if candidate not in valid else "%06d" % ((int(candidate) + 1) % 1_000_000)


def grant(client, secret, username, password, code=None):
    form = {
        "grant_type": "password",
        "client_id": client,
        "client_secret": secret,
        "username": username,
        "password": password,
        "scope": "openid",
    }
    if code is not None:
        form["totp"] = code
    return http("POST", f"{BASE}/realms/{REALM}/protocol/openid-connect/token", form, form=True)


def login(password, code=None):
    return grant("login", LOGIN_SECRET, "cook", password, code)


def probe(password):
    return grant("pwcheck", PROBE_SECRET, "cook", password)


def state(user_id):
    return admin("GET", f"{REALM}/attack-detection/brute-force/users/{user_id}")[1]


def reset(user_id):
    admin("DELETE", f"{REALM}/attack-detection/brute-force/users/{user_id}")
    time.sleep(1.2)  # keep the next failure outside the quick-login window


def expect(label: str, ok: bool, detail: str = "") -> None:
    print(f"  [{'ok' if ok else 'FAIL'}] {label}{(' -- ' + detail) if detail else ''}")
    if not ok:
        FAILED.append(label)


def setup() -> str:
    admin("DELETE", REALM)
    status, body = admin(
        "POST",
        "",
        {
            "realm": REALM,
            "enabled": True,
            "bruteForceProtected": True,
            "permanentLockout": False,
            "failureFactor": 8,
            "waitIncrementSeconds": 60,
            "maxFailureWaitSeconds": 900,
            "maxDeltaTimeSeconds": 43200,
            "quickLoginCheckMilliSeconds": 1000,
            "minimumQuickLoginWaitSeconds": 60,
            "loginWithEmailAllowed": True,
        },
    )
    assert status == 201, (status, body)

    # The probe's flow: username and password validation, no OTP step.
    alias = "password-only-direct-grant"
    status, body = admin(
        "POST",
        f"{REALM}/authentication/flows",
        {"alias": alias, "providerId": "basic-flow", "topLevel": True, "builtIn": False},
    )
    assert status == 201, (status, body)
    for provider in ("direct-grant-validate-username", "direct-grant-validate-password"):
        status, body = admin("POST", f"{REALM}/authentication/flows/{alias}/executions/execution", {"provider": provider})
        assert status == 201, (status, body)
    _, executions = admin("GET", f"{REALM}/authentication/flows/{alias}/executions")
    for execution in executions:
        execution["requirement"] = "REQUIRED"
        status, body = admin("PUT", f"{REALM}/authentication/flows/{alias}/executions", execution)
        assert status in (200, 204), (status, body)
    _, flows = admin("GET", f"{REALM}/authentication/flows")
    flow_id = next(flow["id"] for flow in flows if flow["alias"] == alias)

    for client_id, secret, overrides in (
        ("login", LOGIN_SECRET, {}),
        ("pwcheck", PROBE_SECRET, {"direct_grant": flow_id}),
    ):
        status, body = admin(
            "POST",
            f"{REALM}/clients",
            {
                "clientId": client_id,
                "secret": secret,
                "publicClient": False,
                "directAccessGrantsEnabled": True,
                "standardFlowEnabled": False,
                "serviceAccountsEnabled": False,
                "clientAuthenticatorType": "client-secret",
                "authenticationFlowBindingOverrides": overrides,
            },
        )
        assert status == 201, (status, body)

    status, body = admin(
        "POST",
        f"{REALM}/users",
        {
            "username": "cook",
            "email": "cook@example.test",
            "firstName": "Test",
            "lastName": "Cook",
            "enabled": True,
            "emailVerified": True,
            "credentials": [
                {"type": "password", "value": USER_PASSWORD, "temporary": False},
                {
                    "type": "otp",
                    "secretData": json.dumps({"value": OTP_SECRET}),
                    "credentialData": json.dumps(
                        {"subType": "totp", "digits": 6, "counter": 0, "period": 30, "algorithm": "HmacSHA1"}
                    ),
                },
            ],
        },
    )
    assert status == 201, (status, body)
    return admin("GET", f"{REALM}/users?username=cook&exact=true")[1][0]["id"]


def main() -> int:
    if not ADMIN_USER or not ADMIN_PASSWORD:
        sys.exit("set KC_ADMIN_USER and KC_ADMIN_PASSWORD for the throwaway Keycloak (see the header)")

    user_id = setup()
    print(f"Keycloak at {BASE}, realm {REALM} recreated\n")

    print("Controls")
    status, body = login(USER_PASSWORD)
    expect(
        "password without a code is refused as invalid_grant, like a wrong password",
        status == 400 and body.get("error") == "invalid_grant",
        f"HTTP {status} {body.get('error_description')}",
    )
    code = fresh_code()
    status, _ = login(USER_PASSWORD, code)
    expect("password and a current code sign in", status == 200, f"HTTP {status}")
    status, body = login(USER_PASSWORD, code)
    expect(
        "the same code a second time inside its 30-second step is refused (single use)",
        status == 400,
        f"HTTP {status}",
    )
    reset(user_id)

    # The enrolment case (ADR 0148 Decision 3): a user that already exists gets an OTP
    # credential from the admin API.
    status, _ = admin(
        "POST",
        f"{REALM}/users",
        {
            "username": "late",
            "email": "late@example.test",
            "firstName": "Late",
            "lastName": "Cook",
            "enabled": True,
            "credentials": [{"type": "password", "value": USER_PASSWORD, "temporary": False}],
        },
    )
    late_id = admin("GET", f"{REALM}/users?username=late&exact=true")[1][0]["id"]
    status, _ = admin(
        "PUT",
        f"{REALM}/users/{late_id}",
        {
            "username": "late",
            "email": "late@example.test",
            "firstName": "Late",
            "lastName": "Cook",
            "enabled": True,
            "credentials": [
                {
                    "type": "otp",
                    "userLabel": "phone",
                    "secretData": json.dumps({"value": OTP_SECRET}),
                    "credentialData": json.dumps(
                        {"subType": "totp", "digits": 6, "counter": 0, "period": 30, "algorithm": "HmacSHA1"}
                    ),
                }
            ],
        },
    )
    listed = [c["type"] for c in admin("GET", f"{REALM}/users/{late_id}/credentials")[1]]
    expect(
        "the admin API adds an OTP credential to a user that already exists",
        status == 204 and "otp" in listed,
        f"HTTP {status}, credentials {listed}",
    )
    status, _ = grant("login", LOGIN_SECRET, "late", USER_PASSWORD, fresh_code())
    expect("and that user then signs in with password and code", status == 200, f"HTTP {status}")

    print("\n1. Wrong codes, no probe: Keycloak's lockout works")
    for i in range(1, 10):
        login(USER_PASSWORD, wrong_code(i))
        time.sleep(1.1)
    after = state(user_id)
    expect(
        "nine wrong codes disable the account (failureFactor 8)",
        after.get("disabled") is True and after.get("numFailures") == 8,
        f"numFailures={after.get('numFailures')} disabled={after.get('disabled')}",
    )
    reset(user_id)

    print("\n2. Every wrong code followed by a successful password-only probe")
    for i in range(1, 16):
        login(USER_PASSWORD, wrong_code(i))
        status, _ = probe(USER_PASSWORD)
        assert status == 200, f"the probe was refused ({status})"
        time.sleep(1.1)
    final = state(user_id)
    expect(
        "fifteen wrong codes leave no failures and no lock",
        final.get("numFailures") == 0 and final.get("disabled") is False,
        f"numFailures={final.get('numFailures')} disabled={final.get('disabled')}",
    )
    status, _ = login(USER_PASSWORD, fresh_code())
    expect("the right code still signs in afterwards", status == 200, f"HTTP {status}")
    reset(user_id)

    print("\n3. Probe only when no code was sent; the guesser interleaves one code-less request")
    for i in range(1, 11):
        login(USER_PASSWORD, wrong_code(i))
        time.sleep(1.2)
        login(USER_PASSWORD)  # no code: refused
        status, _ = probe(USER_PASSWORD)  # so the platform would probe here
        assert status == 200, f"the probe was refused ({status})"
        time.sleep(1.1)
    final = state(user_id)
    expect(
        "ten rounds leave no failures and no lock",
        final.get("numFailures") == 0 and final.get("disabled") is False,
        f"numFailures={final.get('numFailures')} disabled={final.get('disabled')}",
    )
    reset(user_id)

    print("\n4. One wrong password, then a failing probe straight after (login first, probe on refusal)")
    login("not-the-password")
    probe("not-the-password")
    after = state(user_id)
    expect(
        "two failures milliseconds apart disable the account via the quick-login check",
        after.get("numFailures") == 2 and after.get("disabled") is True,
        f"numFailures={after.get('numFailures')} disabled={after.get('disabled')}",
    )
    status, _ = login(USER_PASSWORD, fresh_code())
    expect("the right password and code are refused while it lasts", status == 400, f"HTTP {status}")
    reset(user_id)

    print("\n5. One wrong password, probe first (a failing probe ends the request)")
    probe("not-the-password")
    after = state(user_id)
    expect(
        "a single failure, no lock, and the right password and code sign in at once",
        after.get("numFailures") == 1 and after.get("disabled") is False and login(USER_PASSWORD, fresh_code())[0] == 200,
        f"numFailures={after.get('numFailures')} disabled={after.get('disabled')}",
    )

    print()
    if FAILED:
        print(f"{len(FAILED)} observation(s) no longer hold; ADR 0148 needs re-reading:")
        for label in FAILED:
            print(f"  - {label}")
        return 1
    print("every observation holds")
    return 0


if __name__ == "__main__":
    sys.exit(main())
