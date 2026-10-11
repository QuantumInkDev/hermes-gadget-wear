# Read-only watch catalog publisher

This optional standalone service is isolated from Hermes and stock Gadget v0.2.0.
It never changes profile configuration, pairs a device, opens a Gadget socket or exposes
an administrative API. Live installation requires review of the private mapping and TLS
publication. Existing optional SDK patches are independent.

Install Pillow in an isolated Python environment. Create owner-only mapping, credential and
state files outside the public repository (the maintainer uses ignored `.local/`). A mapping
contains `profiles`, each with an existing absolute `home`, a 1–32-character `name`, optional
stable `id`, optional explicit canonical `gadget_url` starting `wss://`, and optional relative
`avatar` confined inside that home. Missing Gadget URLs produce setup-needed cards. Never
infer a port from SSH or a dashboard address. Publish only owner-approved records.

The publisher persists generated UUIDs in its separate private state directory, retaining
them through display-name changes. Set an explicit `id` when moving/renaming a profile home.
It does not write into the Hermes profile or alter the mapping. Artwork becomes an oriented,
metadata-free PNG at most 256×256 / 256 KiB. Current content-addressed assets alone are served.

```sh
python publisher.py --mapping /private/catalog-mapping.json --state /private/catalog-state \
  --credential-file /private/catalog-credential --host 127.0.0.1 --port 8766
```

Publish that loopback listener through an owner-reviewed HTTPS reverse proxy with a valid
certificate, or supply `--tls-cert` and `--tls-key`. The phone refuses HTTP, redirects,
credential-bearing URLs and cross-source artwork. Keep this credential scoped to the
catalog, distinct from Gadget/admin credentials. No Tailscale Serve/ACL change is made by
this tool. Credential files must have owner-only permissions.

Authenticated `GET /catalog/v1` returns `{"version":1,"profiles":[...]}`. Each projected
record contains only `id`, `name`, nullable `gadget_url`, and optional `portrait` with `sha256`,
`bytes`, and `/assets/<sha256>.png` path. Both routes require the same catalog-only bearer
credential. There are no write routes, arbitrary file reads, redirects or request logs.
TLS handshakes and request reads use an eight-second accepted-socket deadline, with at
most four concurrent request workers. A stalled unauthenticated connection cannot block
all authorized requests. Limits are 32 records and 64 KiB; errors fail closed without publishing a partial catalog.

```sh
python -m unittest discover -s server/catalog -p 'test_*.py' -v
```

Tests use temporary original artwork and synthetic mappings, verify minimal projection,
identity persistence, input preservation, portrait geometry/hash, credential enforcement,
path confinement, absent endpoints and a stalled unauthenticated TLS handshake. This is isolated software evidence, not a live
three-box deployment or upstream approval.
