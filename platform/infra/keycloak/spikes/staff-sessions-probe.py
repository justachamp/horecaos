#!/usr/bin/env python3
"""
Evidence for ADR 0176 (staff sessions: sign-in history and active sessions): the
three Keycloak behaviours that record leans on and had not probed, and whether the
provisioning credential as it stands can do the two calls.

Run it against a THROWAWAY Keycloak 26.7.0 in dev mode, never a shared realm. It
deletes and recreates a realm called `staff-sessions-probe`:

    docker run -d --name kc-sessions-probe -p 18193:8080 \\
        -e KC_BOOTSTRAP_ADMIN_USERNAME=probe-admin \\
        -e KC_BOOTSTRAP_ADMIN_PASSWORD="$(openssl rand -hex 12)" \\
        quay.io/keycloak/keycloak:26.7.0 start-dev
    # read the password back with: docker inspect kc-sessions-probe (Config.Env)

    KC_URL=http://localhost:18193 KC_ADMIN_USER=probe-admin \\
        KC_ADMIN_PASSWORD=... python3 platform/infra/keycloak/spikes/staff-sessions-probe.py

The realm models what the platform's sign-in does (ADR 0062): a confidential
direct-grant client called `horecaos-staff-login` asked for `openid offline_access`.
A second client, `probe-provisioning`, stands in for `horecaos-provisioning` and is
given exactly the realm-management roles `infra/keycloak/README.md` lists for it
(`manage-users`, `view-users`, `query-users`) and no client role, which is the point:
the two session calls must work on that credential or they are not built. Every
credential the script uses is generated for the run and discarded with the realm.

It prints each observation and exits non-zero when an expectation of the record does
not hold, so re-run it when the pinned image changes:

  1. the access token the direct grant returns carries `sid`, and it is the id of the
     account's offline session;
  2. `GET /users/{id}/offline-sessions/{clientUuid}` lists that session on the
     provisioning credential, with no client-read role, when the client's UUID is
     supplied (and `GET /clients?clientId=` is the 403 ADR 0098 recorded);
  3. `DELETE /sessions/{id}?isOffline=true` on the same credential ends that one
     session and its refresh token then answers 400, while a second device's session
     is untouched;
  4. the session representation carries an address, which is why the adapter never
     copies it out;
  5. the two calls `logoutEverywhere` makes end both devices (ADR 0098's control).

Recorded result, 2026-10-08, quay.io/keycloak/keycloak:26.7.0 start-dev: every
expectation above holds. The `sid` claim is the offline session's id; both session
lists and the single delete work on `manage-users` + `view-users` + `query-users`
alone once the client's UUID is supplied; `GET /clients?clientId=` is the 403 ADR 0098
recorded, so the UUID is a deployment property and no role is granted. A direct grant
with `offline_access` creates an offline session and no online one (the online list is
empty). Ending a session already gone answers 404. The single end is therefore built
(ADR 0176 Decision 6, Supersedes (2)).
"""
from __future__ import annotations

import base64
import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("KC_URL", "http://localhost:18193")
ADMIN_USER = os.environ.get("KC_ADMIN_USER", "")
ADMIN_PASSWORD = os.environ.get("KC_ADMIN_PASSWORD", "")
REALM = "staff-sessions-probe"

# Generated per run; nothing here is a real credential.
USER_PASSWORD = "Pw-" + secrets.token_urlsafe(18)
LOGIN_SECRET = secrets.token_urlsafe(24)
PROVISIONING_SECRET = secrets.token_urlsafe(24)

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


def provisioning_token() -> str:
    status, body = http(
        "POST",
        f"{BASE}/realms/{REALM}/protocol/openid-connect/token",
        {"grant_type": "client_credentials", "client_id": "probe-provisioning", "client_secret": PROVISIONING_SECRET},
        form=True,
    )
    assert status == 200, (status, body)
    return body["access_token"]


def provisioning(method, path, data=None):
    return http(
        method,
        f"{BASE}/admin/realms/{REALM}/{path}",
        data,
        {"Authorization": "Bearer " + provisioning_token()},
    )


def sign_in():
    return http(
        "POST",
        f"{BASE}/realms/{REALM}/protocol/openid-connect/token",
        {
            "grant_type": "password",
            "client_id": "horecaos-staff-login",
            "client_secret": LOGIN_SECRET,
            "username": "cook",
            "password": USER_PASSWORD,
            "scope": "openid offline_access",
        },
        form=True,
    )


def refresh(token):
    return http(
        "POST",
        f"{BASE}/realms/{REALM}/protocol/openid-connect/token",
        {
            "grant_type": "refresh_token",
            "client_id": "horecaos-staff-login",
            "client_secret": LOGIN_SECRET,
            "refresh_token": token,
        },
        form=True,
    )


def claims(jwt: str) -> dict:
    payload = jwt.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def expect(label: str, ok: bool, detail: str = "") -> None:
    print(f"  [{'ok' if ok else 'FAIL'}] {label}{(' -- ' + detail) if detail else ''}")
    if not ok:
        FAILED.append(label)


def setup() -> tuple[str, str]:
    admin("DELETE", REALM)
    status, body = admin("POST", "", {"realm": REALM, "enabled": True})
    assert status == 201, (status, body)

    status, body = admin(
        "POST",
        f"{REALM}/clients",
        {
            "clientId": "horecaos-staff-login",
            "secret": LOGIN_SECRET,
            "publicClient": False,
            "directAccessGrantsEnabled": True,
            "standardFlowEnabled": False,
            "serviceAccountsEnabled": False,
            "clientAuthenticatorType": "client-secret",
            "optionalClientScopes": ["offline_access"],
        },
    )
    assert status == 201, (status, body)
    login_uuid = admin("GET", f"{REALM}/clients?clientId=horecaos-staff-login")[1][0]["id"]

    status, body = admin(
        "POST",
        f"{REALM}/clients",
        {
            "clientId": "probe-provisioning",
            "secret": PROVISIONING_SECRET,
            "publicClient": False,
            "directAccessGrantsEnabled": False,
            "standardFlowEnabled": False,
            "serviceAccountsEnabled": True,
            "clientAuthenticatorType": "client-secret",
        },
    )
    assert status == 201, (status, body)
    provisioning_uuid = admin("GET", f"{REALM}/clients?clientId=probe-provisioning")[1][0]["id"]
    service_user = admin("GET", f"{REALM}/clients/{provisioning_uuid}/service-account-user")[1]["id"]
    management = admin("GET", f"{REALM}/clients?clientId=realm-management")[1][0]["id"]
    roles = [
        role
        for role in admin("GET", f"{REALM}/clients/{management}/roles")[1]
        if role["name"] in ("manage-users", "view-users", "query-users")
    ]
    assert len(roles) == 3, roles
    status, body = admin("POST", f"{REALM}/users/{service_user}/role-mappings/clients/{management}", roles)
    assert status in (200, 204), (status, body)

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
            "credentials": [{"type": "password", "value": USER_PASSWORD, "temporary": False}],
        },
    )
    assert status == 201, (status, body)
    user_id = admin("GET", f"{REALM}/users?username=cook&exact=true")[1][0]["id"]
    return user_id, login_uuid


def main() -> int:
    if not ADMIN_USER or not ADMIN_PASSWORD:
        sys.exit("set KC_ADMIN_USER and KC_ADMIN_PASSWORD for the throwaway Keycloak (see the header)")

    user_id, login_uuid = setup()
    print(f"Keycloak at {BASE}, realm {REALM} recreated\n")

    print("The sign-in")
    status, first = sign_in()
    assert status == 200, (status, first)
    status, second = sign_in()
    assert status == 200, (status, second)
    sid_first = claims(first["access_token"]).get("sid")
    sid_second = claims(second["access_token"]).get("sid")
    expect("the access token carries a sid claim", bool(sid_first) and bool(sid_second), f"{sid_first}")
    expect(
        "the refresh token is an offline token (refresh_expires_in 0)",
        first.get("refresh_expires_in") == 0,
        f"refresh_expires_in={first.get('refresh_expires_in')}",
    )

    print("\nThe provisioning credential as it is")
    status, body = provisioning("GET", "clients?clientId=horecaos-staff-login")
    expect(
        "reading the client's UUID needs view-clients and is refused (ADR 0098's 403)",
        status == 403,
        f"HTTP {status}",
    )
    status, listed = provisioning("GET", f"users/{user_id}/offline-sessions/{login_uuid}")
    ids = [s.get("id") for s in listed] if status == 200 and isinstance(listed, list) else []
    expect(
        "the offline-session list works on manage-users + view-users with the client UUID supplied",
        status == 200,
        f"HTTP {status}",
    )
    expect("the list holds both devices", len(ids) == 2, f"{len(ids)} sessions")
    expect(
        "the sid of each access token is the id of one listed session",
        sid_first in ids and sid_second in ids,
    )
    if status == 200 and listed:
        expect(
            "the representation carries an address and the times the screen needs",
            all(key in listed[0] for key in ("ipAddress", "start", "lastAccess")),
            ", ".join(sorted(listed[0].keys())),
        )
    status, online = provisioning("GET", f"users/{user_id}/sessions")
    expect("the online-session list works on the same credential", status == 200, f"HTTP {status}, {len(online) if isinstance(online, list) else '?'} online")

    print("\nEnding one session")
    status, body = provisioning("DELETE", f"sessions/{sid_first}?isOffline=true")
    expect("DELETE /sessions/{id}?isOffline=true is accepted on the provisioning credential", status == 204, f"HTTP {status} {body if status != 204 else ''}")
    status, body = refresh(first["refresh_token"])
    expect("the ended device's refresh token answers 400", status == 400, f"HTTP {status}")
    status, body = refresh(second["refresh_token"])
    expect("the other device is untouched", status == 200, f"HTTP {status}")
    status, listed = provisioning("GET", f"users/{user_id}/offline-sessions/{login_uuid}")
    expect(
        "the list afterwards holds exactly the other device",
        status == 200 and len(listed) == 1,
        f"HTTP {status}, {len(listed) if isinstance(listed, list) else '?'}",
    )
    status, body = provisioning("DELETE", f"sessions/{sid_first}?isOffline=true")
    expect("ending a session already gone is a 404, not a server error", status == 404, f"HTTP {status}")

    print("\nEnding everything (ADR 0098's control)")
    status, third = sign_in()
    assert status == 200
    status, _ = provisioning("POST", f"users/{user_id}/logout")
    expect("POST /users/{id}/logout is accepted", status == 204, f"HTTP {status}")
    status, _ = provisioning("DELETE", f"users/{user_id}/consents/horecaos-staff-login")
    expect("DELETE /users/{id}/consents/{client} is accepted", status in (204, 404), f"HTTP {status}")
    status_a, _ = refresh(second["refresh_token"])
    status_b, _ = refresh(third["refresh_token"])
    expect("both devices answer 400 afterwards", status_a == 400 and status_b == 400, f"{status_a}, {status_b}")

    print()
    if FAILED:
        print("FAILED: " + "; ".join(FAILED))
        return 1
    print("every expectation of ADR 0176 holds on this Keycloak")
    return 0


if __name__ == "__main__":
    sys.exit(main())
