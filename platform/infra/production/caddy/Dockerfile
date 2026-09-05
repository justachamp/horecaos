# The edge image, with one module stock Caddy does not carry: rate limiting.
#
# `caddy:2.10-alpine` — what both Caddyfiles in this repository ran on before
# this file existed — has no `rate_limit` directive at all. It is not a
# configuration flag; the module is compiled in or it is not, and it is not.
# `docker run --rm caddy:2.10-alpine caddy list-modules --packages` proves it:
# no `http.handlers.rate_limit` anywhere in the list. A Caddyfile that used the
# directive against that image would fail to start, not degrade — ADR 0023's
# "per-binding rate limits at the edge" checklist item could not have been
# closed by writing config alone.
#
# xcaddy is the standard way to add a module without forking Caddy itself:
# it recompiles the same caddy core against an extra Go import. Built here
# rather than fetched, because there is no upstream image that ships
# caddy-ratelimit and pinning a third party's build would trade one supply
# chain for a less inspectable one.
FROM caddy:2.10-builder-alpine AS build

# Pinned to the version this Dockerfile was proven against (`caddy build-info`
# after building, `docker run --rm <image> caddy build-info`). Bump
# deliberately, not by dropping the version and letting xcaddy pick up
# whatever is newest on the next unrelated rebuild.
RUN xcaddy build \
    --with github.com/mholt/caddy-ratelimit@v0.1.0

FROM caddy:2.10-alpine

# Same base as the stock image everywhere else in this repository refers to
# by tag — only the binary differs. `caddy list-modules --packages` on the
# result carries exactly one extra line beyond stock: caddy-ratelimit.
COPY --from=build /usr/bin/caddy /usr/bin/caddy
