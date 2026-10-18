# Relay Server Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recover an opted-in Gadget device's completed text reply after disconnect/restart through an authenticated, bounded, explicitly acknowledged outbox, preserving stock conversations and speech when the extension is disabled.

**Architecture:** Add one optional SDK patch after the existing three-patch series. A private grant file binds enrolled keys to an opaque profile; SQLite persists final text before delivery through the real core `send_final_ledgered` hook. Capability discovery advertises only the outbox methods actually implemented; session/history/task methods remain gated for a subsequent real-core adapter increment.

**Tech Stack:** Python standard library (`sqlite3`, `hashlib`, `json`, `uuid`), existing websockets and pytest, Gadget v1 JSON envelopes, pinned Hermes core adapter base hook. No Android or new runtime dependency.

## Global Constraints

- Preserve stock v0.2.0 behavior with every optional capability disabled.
- Keep the Kotlin protocol module Android-free.
- No multiplexed Gadget gateway or public release.
- Phone and watch retain separate device identities.
- No personal endpoints, tailnet details, credentials, signing keys or personal pet art in public files.
- Configurable default: twenty-four-hour bounded server outbox; canonical Hermes history retention is unchanged.
- Never replay recordings, actions, approval answers or paid synthesis.
- Recovered events never automatically speak or answer approval prompts.
- Actual live integration and physical acceptance require their own receipts; a fixture is software evidence only.
- No edits to installed Hermes, installed Gadget, original stock SDK or the existing three-patch checkout while executing this plan.
- Controller coordinates commits. Do not commit another worker's files or independently deploy/restart a service.

---

## Scope, sources and workspace

Read `AGENTS.md`, `SESSION_STATUS.md`, `docs/progress.md`, the approved expansion spec
and `docs/relay-server-contract.md`. This is **one first feature increment**, not the
complete fifteen-feature program. It implements final-text O5 retention and discovery.
It does not claim full task notifications, history or shared-session continuation.

Verified SDK base: `323e3303ab68981f810fc3208119cd8a22e64af0`; patches 1–3 end at
`8a1d8541661a218acb70b8e4dec46fbb30a9eff2`. Verified core reference:
`e6f57427c198cfd96854522b7a9159eb007e13c2`. Reverify before implementation.
`gateway/platforms/base.py:4363–4386` is the real final-delivery bracket and passes the
original event, ledger message identity and final text before socket send. Stock
`plugin/adapter.py.send` and `DeviceSession.send_reply` lack this durable identity and
cannot be the outbox capture point. No inference from `welcome.session` is allowed.

Use a fresh ignored SDK checkout `.local/upstream/relay-foundations-sdk` and environment
`.local/relay-foundations-venv`, prepared by `tools/setup-extensions.py --sdk-dir ...
--environment ...` using absolute paths. Read that checkout's `AGENTS.md` before edits.
For core tests use an isolated source worktree and temporary `HERMES_HOME`; never import
against a real profile's writable DB. No provider key or actual provider request is needed.

Files in SDK checkout (exported into this repository as patch 4):

| File | Responsibility |
| --- | --- |
| `plugin/relay_grants.py` | Strict private grant read; enrollment fingerprint, expiry and grant generation |
| `plugin/relay_outbox.py` | Durable final records, ACK tombstones, bounded retention and loss indication |
| `plugin/relay_service.py` | Authenticated bounded request dispatch and final-delivery capture |
| `plugin/hub.py` | Opt-in welcome and dispatch only |
| `plugin/adapter.py` | Service lifecycle and verified final-delivery hook |
| `tests/test_relay_grants.py` | Revocation, expiry, key rotation, malformed state |
| `tests/test_relay_outbox.py` | Restart, duplicate, loss/expiry, quota and ACK isolation |
| `tests/test_relay_service.py` | Wire bounds, stock fallback, silent replay |
| `tests/test_relay_adapter.py` | Real core inheritance/hook compatibility and capability-off behavior |
| `docs/protocol.md` | Optional envelope and no-replay semantics |

Files in this repository at handoff: new `server/patches/0004-feat-add-scoped-final-text-outbox.patch`,
updates to `server/README.md`, `docs/upstream.md`, `docs/progress.md`, `SESSION_STATUS.md`
and this plan's checkboxes. No updates to original patches 1–3. Coordinate those shared
document edits with the controller. Private receipts: `.local/expansion/server-foundations/`.

## Task 1: Durable bounded outbox with restart-safe ACKs

**Interfaces:** `Outbox(path, now_ms, max_rows=256, max_device_bytes=1048576,
max_profile_bytes=8388608, ttl_ms=86400000)`; `put(owner,event_id,conversation_id,text)`
returns Delivery; `read(owner,limit)` returns `{items,next_cursor:null,gap_generation}`;
`ack(owner,ids)` returns acknowledged IDs. Owner is private SHA-256 of profile UUID,
device ID and enrollment fingerprint; it is never exposed. This first bounded queue
returns oldest unacknowledged records repeatedly until ACK, so no cursor is needed.
`relay.outbox.read` accepts only `limit` in this increment; discovery includes a
`limits.outbox_cursor=false` flag. Later pagination must be capability-negotiated.

- [ ] Write `tests/test_relay_outbox.py`:

```python
import pytest
from hermes_gadget_plugin.relay_outbox import Outbox, OutboxError


def test_restart_lost_ack_and_no_cross_device_ack(tmp_path):
    path = tmp_path / 'outbox.sqlite3'
    box = Outbox(path, lambda: 1000)
    first = box.put('phone', 'event-a', 'conversation-a', 'finished')
    restarted = Outbox(path, lambda: 1001)
    assert restarted.read('phone', 25)['items'] == [first]
    assert restarted.put('phone', 'event-a', 'conversation-a', 'finished') == first
    with pytest.raises(OutboxError, match='not_found'):
        restarted.ack('watch', [first['delivery_id']])
    assert restarted.ack('phone', [first['delivery_id']]) == [first['delivery_id']]
    assert Outbox(path, lambda: 1002).ack('phone', [first['delivery_id']]) == [first['delivery_id']]
    assert restarted.read('phone', 25)['items'] == []
    assert restarted.put('phone', 'event-a', 'conversation-a', 'finished') == first
    assert restarted.read('phone', 25)['items'] == []


def test_expiry_eviction_and_duplicate_conflict(tmp_path):
    clock = [1000]
    box = Outbox(tmp_path / 'o.db', lambda: clock[0], max_rows=1, ttl_ms=100)
    first = box.put('p', 'a', 'c', 'one')
    with pytest.raises(OutboxError, match='conflict'):
        box.put('p', 'a', 'c', 'changed')
    box.put('p', 'b', 'c', 'two')
    page = box.read('p', 25)
    assert [x['payload']['text'] for x in page['items']] == ['two']
    assert page['gap_generation'] == 1
    clock[0] = 1100
    assert box.read('p', 25)['items'] == []
    assert box.read('p', 25)['gap_generation'] == 2
    # Late retries of expired delivery events cannot resurrect retained content.
    with pytest.raises(OutboxError, match='expired'):
        box.put('p', 'a', 'c', 'one')
    assert first['expires_at_ms'] == 1100


def test_profile_quota_and_ack_atomicity(tmp_path):
    box = Outbox(tmp_path / 'o.db', lambda: 1000, max_profile_bytes=650)
    a = box.put('a', 'a', 'c', 'a' * 80)
    box.put('b', 'b', 'c', 'b' * 80)
    assert box.read('a', 25)['gap_generation'] >= 1
    with pytest.raises(OutboxError, match='not_found'):
        box.ack('b', ['absent'])
    assert box.read('b', 25)['items']
    assert a['delivery_id']
```

- [ ] Run `python -m pytest -q tests/test_relay_outbox.py`; expect import failure.
- [ ] Create `plugin/relay_outbox.py` with this implementation. SQLite transactions serialize
insert/dedup/ACK; a durable row is committed before `put` returns. Store at most 4096
live/tombstone identities per profile; refuse new work with `busy` before destroying
unexpired retry evidence. ACK tombstones retain the bounded DTO until expiry (24 hours),
then retain only event identity/digest for seven days. Retries after seven days are
outside this final-delivery recovery window and must come from canonical history,
not new outbox capture. No caller may re-run an old model turn to refill this queue.

```python
import hashlib
import json
import os
import sqlite3
import uuid
from contextlib import contextmanager


class OutboxError(ValueError):
    pass


class Outbox:
    def __init__(self, path, now_ms, max_rows=256, max_device_bytes=1048576,
                 max_profile_bytes=8388608, ttl_ms=86400000):
        self.path, self.now_ms = path, now_ms
        self.max_rows, self.max_device_bytes = max_rows, max_device_bytes
        self.max_profile_bytes, self.ttl_ms = max_profile_bytes, ttl_ms
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with self.tx() as db:
            db.execute('CREATE TABLE IF NOT EXISTS deliveries ('
                       'seq INTEGER PRIMARY KEY, owner TEXT NOT NULL, event TEXT NOT NULL, '
                       'id TEXT UNIQUE NOT NULL, digest TEXT NOT NULL, body TEXT, size INTEGER NOT NULL, '
                       'expires INTEGER NOT NULL, retire INTEGER NOT NULL, ack INTEGER NOT NULL DEFAULT 0, '
                       'UNIQUE(owner,event))')
            db.execute('CREATE TABLE IF NOT EXISTS gaps (owner TEXT PRIMARY KEY, generation INTEGER NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS optins (owner TEXT PRIMARY KEY, generation INTEGER NOT NULL)')
        os.chmod(path, 0o600)

    @contextmanager
    def tx(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        try:
            db.execute('PRAGMA page_size=4096')
            db.execute('PRAGMA max_page_count=4096')
            db.execute('PRAGMA journal_mode=DELETE')
            db.execute('PRAGMA synchronous=FULL')
            db.execute('BEGIN IMMEDIATE')
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def _gap(self, db, owner):
        db.execute('INSERT INTO gaps VALUES (?,1) ON CONFLICT(owner) '
                   'DO UPDATE SET generation=generation+1', (owner,))

    def _purge(self, db):
        now = self.now_ms()
        for row in db.execute('SELECT owner FROM deliveries WHERE body IS NOT NULL '
                              'AND ack=0 AND expires<=?', (now,)).fetchall():
            self._gap(db, row['owner'])
        db.execute('UPDATE deliveries SET body=NULL,size=0 WHERE expires<=?', (now,))
        db.execute('DELETE FROM deliveries WHERE retire<=?', (now,))

    def _trim(self, db, owner):
        while True:
            own = db.execute('SELECT count(*),coalesce(sum(size),0) FROM deliveries '
                             'WHERE owner=? AND body IS NOT NULL AND ack=0', (owner,)).fetchone()
            total = db.execute('SELECT coalesce(sum(size),0) FROM deliveries').fetchone()[0]
            if own[0] > self.max_rows or own[1] > self.max_device_bytes:
                row = db.execute('SELECT * FROM deliveries WHERE owner=? AND body IS NOT NULL '
                                 'AND ack=0 ORDER BY seq LIMIT 1', (owner,)).fetchone()
            elif total > self.max_profile_bytes:
                row = db.execute('SELECT * FROM deliveries WHERE body IS NOT NULL ORDER BY seq LIMIT 1').fetchone()
            else:
                return
            if not row['ack']:
                self._gap(db, row['owner'])
            db.execute('UPDATE deliveries SET body=NULL,size=0 WHERE seq=?', (row['seq'],))

    def put(self, owner, event_id, conversation_id, text):
        if not isinstance(text, str) or not text or len(text.encode()) > 16384:
            raise OutboxError('invalid_request')
        digest = hashlib.sha256(json.dumps([conversation_id, text], ensure_ascii=False).encode()).hexdigest()
        with self.tx() as db:
            self._purge(db)
            old = db.execute('SELECT * FROM deliveries WHERE owner=? AND event=?', (owner, event_id)).fetchone()
            if old:
                if old['digest'] != digest:
                    raise OutboxError('conflict')
                if old['body'] is None:
                    raise OutboxError('expired')
                return json.loads(old['body'])
            if db.execute('SELECT count(*) FROM deliveries').fetchone()[0] >= 4096:
                raise OutboxError('busy')
            now = self.now_ms()
            item = dict(delivery_id=str(uuid.uuid4()), conversation_id=conversation_id,
                        event_id=event_id, created_at_ms=now, expires_at_ms=now+self.ttl_ms,
                        payload={'kind': 'final_text', 'text': text})
            body = json.dumps(item, separators=(',', ':'), ensure_ascii=False)
            db.execute('INSERT INTO deliveries(owner,event,id,digest,body,size,expires,retire) '
                       'VALUES (?,?,?,?,?,?,?,?)', (owner,event_id,item['delivery_id'],digest,body,
                       len(body.encode()),item['expires_at_ms'],item['expires_at_ms']+604800000))
            self._trim(db, owner)
            return item

    def read(self, owner, limit):
        if type(limit) is not int or not 1 <= limit <= 50:
            raise OutboxError('invalid_request')
        with self.tx() as db:
            self._purge(db)
            rows = db.execute('SELECT body FROM deliveries WHERE owner=? AND ack=0 '
                              'AND body IS NOT NULL ORDER BY seq LIMIT ?', (owner,limit)).fetchall()
            gap = db.execute('SELECT generation FROM gaps WHERE owner=?', (owner,)).fetchone()
            return dict(items=[json.loads(r[0]) for r in rows], next_cursor=None,
                        gap_generation=gap[0] if gap else 0)

    def ack(self, owner, ids):
        if not isinstance(ids, list) or not 1 <= len(ids) <= 50 or any(not isinstance(x, str) for x in ids):
            raise OutboxError('invalid_request')
        with self.tx() as db:
            self._purge(db)
            for identifier in ids:
                row = db.execute('SELECT owner FROM deliveries WHERE id=?', (identifier,)).fetchone()
                if row is None or row[0] != owner:
                    raise OutboxError('not_found')
            db.executemany('UPDATE deliveries SET ack=1 WHERE owner=? AND id=?', [(owner,x) for x in ids])
        return ids

    def opt_in(self, owner, generation):
        with self.tx() as db:
            if db.execute('SELECT count(*) FROM optins').fetchone()[0] >= 128:
                if db.execute('SELECT 1 FROM optins WHERE owner=?', (owner,)).fetchone() is None:
                    raise OutboxError('busy')
            db.execute('INSERT INTO optins VALUES (?,?) ON CONFLICT(owner) '
                       'DO UPDATE SET generation=excluded.generation', (owner,generation))

    def opt_out(self, owner):
        with self.tx() as db:
            db.execute('DELETE FROM optins WHERE owner=?', (owner,))
            db.execute('UPDATE deliveries SET body=NULL,size=0 WHERE owner=?', (owner,))
            db.execute('DELETE FROM gaps WHERE owner=?', (owner,))

    def is_opted_in(self, owner, generation):
        with self.tx() as db:
            row = db.execute('SELECT generation FROM optins WHERE owner=?', (owner,)).fetchone()
            return row is not None and row[0] == generation
```

- [ ] Run the tests; adjust the profile-quota fixture to the actual encoded DTO size if
400 bytes cannot retain even one row. Choose a quota that retains exactly one of two
identical-size rows; assert final stored aggregate bytes do not exceed it. Add concurrent
put/ACK with two SQLite connections and a killed writer before/after commit. Before
commit no row is visible; after commit restart sees exactly one delivery.
- [ ] Review quota correctness: acknowledged DTO bytes count toward the profile cap;
if evicted, exact ACK still succeeds and duplicate capture returns `expired`, never a new
record. `gaps` needs a maximum of 128 grant owners and cleanup on key rotation; implement
that in the grant/service integration. SQLite DB page allocation is not payload quota:
set `PRAGMA journal_size_limit=1048576`, use incremental vacuum or scheduled bounded
compaction, and verify total DB/journal disk size after churn stays below a documented
36 MiB deployment budget. If that bound fails, reduce tombstones/metadata or refuse writes;
do not silently claim bounded disk from bounded text alone.
- [ ] Controller checkpoint/commit for this independently testable persistence unit.

## Task 2: Enrolled-key grants and wire service

**Interfaces:** `Grants(path, devices, authorized, now_ms).get(device_id)` returns
`{profile_id,owner,generation,expires_at_ms}` or raises `OutboxError('permission_denied')`.
`devices` is the existing DeviceStore; `authorized(device_id)` must be exactly True
from the actual runner allowlist, not the permissive no-runner unit default.
Grant JSON is schema 1 with profile UUID, positive generation and device mapping:
`{device_id:{key_sha256,expires_at_ms,outbox:true}}`. This increment has no writable
grant RPC. Store grants in the profile's existing plugin data directory.

- [ ] Write `tests/test_relay_grants.py` testing key mismatch/re-enrollment, expired grants,
missing/invalid file, false/None runner authorization, and a file changed between two
requests. Use this concrete fixture and parametrize the refusals:

```python
import hashlib
import json
import os
from types import SimpleNamespace
import pytest
from hermes_gadget_plugin.relay_grants import Grants
from hermes_gadget_plugin.relay_outbox import OutboxError


def test_recheck_key_and_revocation(tmp_path):
    p = tmp_path / 'relay-grants.json'
    key = [b'first-key']
    data = {'schema':1, 'profile_id':'9e8af651-47eb-411f-b28c-ed292bbfd305',
            'generation':1, 'devices':{'phone':{'key_sha256':hashlib.sha256(key[0]).hexdigest(),
                                              'expires_at_ms':5000, 'outbox':True}}}
    p.write_text(json.dumps(data)); os.chmod(p, 0o600)
    grants = Grants(p, SimpleNamespace(key_for=lambda _: key[0]), lambda _: True, lambda: 1000)
    assert grants.get('phone')['generation'] == 1
    key[0] = b'replacement-key'
    with pytest.raises(OutboxError, match='permission_denied'):
        grants.get('phone')
    key[0] = b'first-key'
    data['devices'] = {}; p.write_text(json.dumps(data))
    with pytest.raises(OutboxError, match='permission_denied'):
        grants.get('phone')
```

- [ ] Run that test; expect missing module. Create `plugin/relay_grants.py`:

```python
import hashlib
import json
import os
import stat
import uuid
from .relay_outbox import OutboxError


class Grants:
    def __init__(self, path, devices, authorized, now_ms):
        self.path, self.devices, self.authorized, self.now_ms = path, devices, authorized, now_ms

    def get(self, device_id):
        try:
            if self.authorized(device_id) is not True:
                raise ValueError()
            # Inspect the opened descriptor, avoiding a check/open file replacement race.
            flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
            fd = os.open(self.path, flags)
            with os.fdopen(fd, 'rb') as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_size > 65536:
                    raise ValueError()
                if os.name != 'nt' and (info.st_mode & 0o077):
                    raise ValueError()
                raw = stream.read(65537)
            if len(raw) > 65536:
                raise ValueError()
            data = json.loads(raw)
            if set(data) != {'schema','profile_id','generation','devices'} or (type(data['schema']) is not int or data['schema'] != 1):
                raise ValueError()
            profile = str(uuid.UUID(data['profile_id']))
            if type(data['generation']) is not int or not 1 <= data['generation'] <= 2147483647:
                raise ValueError()
            if not isinstance(data['devices'], dict) or len(data['devices']) > 128:
                raise ValueError()
            grant = data['devices'][device_id]
            if set(grant) != {'key_sha256','expires_at_ms','outbox'} or grant['outbox'] is not True:
                raise ValueError()
            if type(grant['expires_at_ms']) is not int or grant['expires_at_ms'] <= self.now_ms():
                raise ValueError()
            key = self.devices.key_for(device_id)
            if key is None or hashlib.sha256(key).hexdigest() != grant['key_sha256']:
                raise ValueError()
            owner = hashlib.sha256(json.dumps([profile,device_id,grant['key_sha256']]).encode()).hexdigest()
            return dict(profile_id=profile,owner=owner,generation=data['generation'],
                        expires_at_ms=grant['expires_at_ms'])
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            raise OutboxError('permission_denied') from None
```

On Windows, POSIX mode bits do not enforce privacy. Add an actual Windows ACL check or
private-directory ACL provisioning before live activation there; local fixture success
on macOS is not evidence for Windows. Reject symlink/reparse private state paths during
installer preflight. Do not put a private path or JSON parser error into a wire response.

- [ ] Write `tests/test_relay_service.py` using the service below: unpaired, no-capability,
unknown method, invalid ID/type/params, 64-KiB input, wrong-owner ACK, expiry/revocation
and duplicate read. Assert all replies use `relay.response` and no call sends `reply`,
`audio.start`, `tts.speak`, `prompt.reply` or invokes a provider.
- [ ] Run it to observe missing module. Create `plugin/relay_service.py`:

```python
import asyncio
import hashlib
import json
import re
import sqlite3
import time
import uuid
from .relay_outbox import OutboxError

_ID = re.compile(r'[A-Za-z0-9_-]{1,64}\Z')


def negotiated(session):
    cap = session.caps.get('relay')
    return (isinstance(cap, dict) and set(cap) == {'version'}
            and type(cap['version']) is int and cap['version'] == 1)


def wire_bytes(value):
    return len(json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode())


class RelayService:
    def __init__(self, grants, outbox):
        self.grants, self.outbox = grants, outbox
        self.lock = asyncio.Lock()

    def context(self, session):
        if not session.paired or not negotiated(session):
            raise OutboxError('permission_denied')
        return self.grants.get(session.device_id)

    async def welcome(self, session):
        if self.lock.locked():
            return None
        async with self.lock:
            try:
                ctx = await asyncio.to_thread(self.grants.get, session.device_id)
                if not session.paired or not negotiated(session):
                    await asyncio.to_thread(self.outbox.opt_out, ctx['owner'])
                    return None
                await asyncio.to_thread(self.outbox.opt_in, ctx['owner'], ctx['generation'])
                fresh = await asyncio.to_thread(self.context, session)
                if fresh != ctx:
                    await asyncio.to_thread(self.outbox.opt_out, ctx['owner'])
                    return None
                return {'version':1}
            except (OutboxError, OSError, sqlite3.Error):
                return None

    def _rate(self, session):
        now = time.monotonic()
        tokens, when = getattr(session, '_relay_rate', (20.0, now))
        tokens = min(20.0, tokens + (now-when)*10)
        if tokens < 1:
            session._relay_rate = (tokens, now)
            raise OutboxError('rate_limited')
        session._relay_rate = (tokens-1, now)

    async def request(self, session, msg):
        identifier = msg.get('id')
        if not isinstance(identifier,str) or not _ID.fullmatch(identifier):
            identifier = None
        reply = {'type':'relay.response','v':1}
        if identifier is not None:
            reply['id'] = identifier
        try:
            self._rate(session)
            if identifier is None or set(msg) != {'type','v','id','method','params'}:
                raise OutboxError('invalid_request')
            if msg['type'] != 'relay.request' or type(msg['v']) is not int or msg['v'] != 1:
                raise OutboxError('invalid_request')
            params, method = msg['params'], msg['method']
            if not isinstance(params,dict) or not isinstance(method,str):
                raise OutboxError('invalid_request')
            if method == 'relay.info':
                valid = not params
            elif method == 'relay.outbox.read':
                valid = (set(params) <= {'limit'} and type(params.get('limit',25)) is int
                         and 1 <= params.get('limit',25) <= 50)
            elif method == 'relay.outbox.ack':
                ids = params.get('delivery_ids')
                valid = (set(params) == {'delivery_ids'} and isinstance(ids,list)
                         and 1 <= len(ids) <= 50 and all(isinstance(x,str) and len(x)==36 for x in ids))
            else:
                raise OutboxError('unsupported')
            if not valid:
                raise OutboxError('invalid_request')
            if self.lock.locked():
                raise OutboxError('busy')
            async with self.lock:
                ctx = await asyncio.to_thread(self.context,session)
                if method == 'relay.info':
                    result = dict(profile_id=ctx['profile_id'],grant_generation=ctx['generation'],
                                  expires_at_ms=ctx['expires_at_ms'],
                                  methods=['relay.info','relay.outbox.read','relay.outbox.ack'],
                                  limits={'outbox_cursor':False,'page_items':50,'text_bytes':16384})
                elif method == 'relay.outbox.read':
                    result = await asyncio.to_thread(self.outbox.read,ctx['owner'],params.get('limit',25))
                    while result['items'] and wire_bytes({**reply,'result':result}) > 65536:
                        result['items'].pop()
                else:
                    result = {'acknowledged':await asyncio.to_thread(self.outbox.ack,ctx['owner'],params['delivery_ids'])}
                if await asyncio.to_thread(self.context,session) != ctx:
                    await asyncio.to_thread(self.outbox.opt_out,ctx['owner'])
                    raise OutboxError('permission_denied')
                reply['result'] = result
        except OutboxError as exc:
            reply['error'] = {'code':str(exc)}
        except (OSError,sqlite3.Error):
            reply['error'] = {'code':'temporarily_unavailable'}
        await session.send_json(reply)

    async def capture(self,event,session_key,text,ephemeral):
        if ephemeral or not text or str(event.text or '').lstrip().startswith(('/', '!')):
            return None
        if event.source.chat_id != event.source.user_id:
            return None
        async with self.lock:
            try:
                ctx = await asyncio.to_thread(self.grants.get,event.source.user_id)
            except OutboxError:
                return None
            if not await asyncio.to_thread(self.outbox.is_opted_in,ctx['owner'],ctx['generation']):
                return None
            identity = getattr(event,'ledger_message_id',None) or event.message_id
            if not identity:
                raise OutboxError('invalid_request')
            digest = hashlib.sha256(json.dumps([session_key,str(identity)],separators=(',',':')).encode()).hexdigest()
            event_id = str(uuid.uuid5(uuid.UUID(ctx['profile_id']),digest))
            conversation_id = str(uuid.uuid5(uuid.UUID(ctx['profile_id']),session_key))
            item = await asyncio.to_thread(self.outbox.put,ctx['owner'],event_id,conversation_id,text)
            if await asyncio.to_thread(self.grants.get,event.source.user_id) != ctx:
                await asyncio.to_thread(self.outbox.opt_out,ctx['owner'])
                raise OutboxError('permission_denied')
            return item
```

- [ ] Verify the code's asynchronous I/O, grant recheck, one active service operation,
transport-local 10/sec burst-20 rate limit and actual 64-KiB response-size budget. The
wire schema rejects nested params; catch `RecursionError` alongside `ValueError` in the
hub JSON parser so deeply nested input cannot disconnect unrelated clients. Responses
return fewer than limit when needed and leave the rest unacknowledged. An item of
16 KiB text plus escaping must fit; JSON `ensure_ascii=False` avoids worst-case Unicode
expansion, but quotes/backslashes still require the actual serialized byte check. Add
exact boundary tests (50×16-KiB finals must never form one frame). These are acceptance
requirements, not optional hardening.
- [ ] Fix ACK tombstone quota, 128-owner cleanup and seven-day identity-expiry behavior from
Task 1 with concrete tests. Ensure an unknown key owner cannot consume another owner's
retained data. Do not erase live owners merely because their device is disconnected.
- [ ] Controller checkpoint/commit after persistence/grant/wire tests pass.

## Task 3: Connect the real final-delivery hook and preserve stock behavior

**Interfaces:** `await HubDelegate.relay_welcome(session) -> dict | None` and
`await HubDelegate.on_relay_request(session,msg)` are default no-ops. GadgetAdapter
owns `_relay_service: RelayService | None`. Flag `extra.enable_relay_outbox` defaults
False. Missing/invalid grants disable discovery/delivery for that device, never stock
chat. The service database and grant path use `plugin_data_dir('gadget')`; no caller
path or host URL is accepted.

- [ ] Write failing adapter tests against the isolated **actual core** import, not a
stub BasePlatformAdapter. Spy the actual inherited `send_final_ledgered` for disabled
and no-grant cases; use a synthetic event/source with no runner/provider call. Test
that a valid grant and opted-in session produces one durable record even if the
WebSocket disappears before final delivery, then reconnect/read/ACK returns that exact
record. Restart the adapter and SQLite connection and repeat. No provider calls.
- [ ] Add these methods to `HubDelegate`:

```python
    async def relay_welcome(self, session):
        return None

    async def on_relay_request(self, session, msg):
        return None
```

Immediately before sending the existing welcome dictionary in `DeviceHub._handle`, add:

```python
            relay = await self.delegate.relay_welcome(session)
            if relay is not None:
                welcome['relay'] = relay
```

Before existing route lookup in `_on_text`, add:

```python
        if msg.get('type') == 'relay.request':
            if len(raw.encode('utf-8')) > 65536:
                await session.send_json({'type':'relay.response','v':1,
                                         'error':{'code':'invalid_request'}})
                return
            await self.delegate.on_relay_request(session,msg)
            return
```

- [ ] In `GadgetAdapter.__init__`, add `_relay_service = None` and
`_enable_relay_outbox = extra.get('enable_relay_outbox') is True`. In `connect`, after
DeviceStore creation and before hub start, build the optional service:

```python
        if self._enable_relay_outbox:
            from .relay_grants import Grants
            from .relay_outbox import Outbox
            from .relay_service import RelayService
            now_ms = lambda: int(time.time() * 1000)
            directory = plugin_data_dir(PLATFORM_NAME)
            grants = Grants(directory / 'relay-grants.json', self._store,
                            self._authorized, now_ms)
            self._relay_service = RelayService(
                grants, Outbox(directory / 'relay-outbox.sqlite3', now_ms))
```

Add these methods to GadgetAdapter:

```python
    async def relay_welcome(self,session):
        return await self._relay_service.welcome(session) if self._relay_service else None

    async def on_relay_request(self,session,msg):
        if self._relay_service:
            await self._relay_service.request(session,msg)

    async def send_final_ledgered(self,event,session_key,text_content,metadata,*,reply_to,
                                 is_ephemeral_response=False):
        from .relay_outbox import OutboxError
        import sqlite3
        item = None
        if self._relay_service is not None:
            try:
                item = await self._relay_service.capture(event,session_key,text_content,
                                                         is_ephemeral_response)
            except (OutboxError,OSError,sqlite3.Error):
                return SendResult(success=False,error='relay final persistence unavailable'),self
        if item is None:
            return await super().send_final_ledgered(
                event,session_key,text_content,metadata,reply_to=reply_to,
                is_ephemeral_response=is_ephemeral_response)
        await self._release_turn_marker(event)
        return SendResult(success=True,message_id=item['delivery_id']),self
```

The last block is deliberately pull-only: an opted-in client polls the authenticated
outbox while a turn runs and after reconnect; ordinary live deltas may still appear,
but final text comes from its durable delivery ID. This avoids duplicate final bubbles
and any recovered TTS call. Core automatic speech outside this final-text bracket must
be measured separately; this hook itself never synthesizes or replays audio.

- [ ] Verify durable enrollment-bound opt-in: authenticated welcome commits the owner and
grant generation before acknowledging the extension. Final capture uses that record
even when disconnected. Stock reconnect calls opt_out. Run this concrete acceptance test:

```python
import asyncio
import hashlib
import json
import os
from types import SimpleNamespace
from hermes_gadget_plugin.relay_grants import Grants
from hermes_gadget_plugin.relay_outbox import Outbox
from hermes_gadget_plugin.relay_service import RelayService


def test_disconnected_final_restart_and_silent_replay(tmp_path):
    async def run():
        key = b'synthetic-test-key'
        profile = '9e8af651-47eb-411f-b28c-ed292bbfd305'
        path = tmp_path/'relay-grants.json'
        path.write_text(json.dumps({'schema':1,'profile_id':profile,'generation':1,
            'devices':{'phone':{'key_sha256':hashlib.sha256(key).hexdigest(),
                                'expires_at_ms':900000,'outbox':True}}}))
        os.chmod(path,0o600)
        grants = Grants(path,SimpleNamespace(key_for=lambda _:key),lambda _:True,lambda:1000)
        received = []
        async def send(msg):
            received.append(msg)
        session = SimpleNamespace(device_id='phone',paired=True,caps={'relay':{'version':1}},send_json=send)
        service = RelayService(grants,Outbox(tmp_path/'o.db',lambda:1000))
        assert await service.welcome(session) == {'version':1}
        event = SimpleNamespace(source=SimpleNamespace(chat_id='phone',user_id='phone'),
                                message_id='stable-input',ledger_message_id=None,text='question')
        # Capture has no socket/session parameter: disconnected completion is retained.
        item = await service.capture(event,'synthetic-session','final answer',False)
        assert received == []
        restarted = RelayService(grants,Outbox(tmp_path/'o.db',lambda:1001))
        assert await restarted.welcome(session) == {'version':1}
        await restarted.request(session,{'type':'relay.request','v':1,'id':'read1',
                                        'method':'relay.outbox.read','params':{}})
        assert received[-1]['result']['items'] == [item]
        await restarted.request(session,{'type':'relay.request','v':1,'id':'ack1',
            'method':'relay.outbox.ack','params':{'delivery_ids':[item['delivery_id']]}})
        assert received[-1]['result']['acknowledged'] == [item['delivery_id']]
        assert all(msg['type']=='relay.response' for msg in received)
        assert await restarted.capture(event,'synthetic-session','final answer',False) == item
        assert restarted.outbox.read(grants.get('phone')['owner'],25)['items'] == []
    asyncio.run(run())
```

- [ ] Fail persistence explicitly: if outbox commit fails, do not clear the core turn marker
or return success. Preserve normal core delivery-ledger recovery semantics and return a
failed SendResult; never silently fall back to an unacknowledged final or claim it was
saved. A retry of the same event identity must meet its original row or a conflict.
Test disk-full/locked DB and grant revocation after capture but before response. Never
release a revoked device's queued text; revoke its opt-in and expire retained deliveries.
- [ ] Verify **actual** pinned core inheritance calls this hook for normal streamed final
text, non-streamed finals and recovered native finals. Inspect/test any alternate path
that only calls `edit_message(finalize=True)`; either route it through the same stable
final-delivery identity or leave capability disabled for that core. Test queued-chain
`ledger_message_id`, two different same-text turns, lost socket, and a repeated final
callback. Do not deduplicate by text alone or by connection epoch.
- [ ] Run all SDK protocol/plugin unit tests and the three existing extension suites with
warnings as errors; record exact counts/skips. Run actual isolated-core adapter tests
with `HERMES_AGENT_DIR` pointing to the read-only isolated core source and temporary
`HERMES_HOME`. Missing core import is a failure, not an accepted skip. Stock SDK remains
unmodified; compare its unchanged hello/text/audio path in a separate fixture.
- [ ] Controller checkpoint/commit; export only the SDK changes as patch 4. Reapply patches
1–4 to a **fresh** checkout through the setup tool and rerun the targeted tests. Existing
patches' SHA-256 values must match their pre-task values. Include `git diff --check`.

## Exact validation commands and evidence

Run from the fresh patched SDK checkout, using its isolated environment Python:

```sh
python -m pytest -W error -q tests/test_relay_grants.py tests/test_relay_outbox.py tests/test_relay_service.py tests/test_relay_adapter.py
python -m pytest -W error -q tests/test_protocol.py tests/test_plugin_units.py
python -m pytest -W error -q tests/test_opus.py tests/test_pets.py tests/test_client_tts.py
```

The extension filenames above were verified in the three-patch checkout; recheck if
the patch base changes. Core-enabled fixture command must set an isolated
`HERMES_HOME` with no credentials and use `HERMES_AGENT_DIR` for the pinned source.
Capture local loopback Gadget socket exchange with synthetic device keys: HMAC → paired
welcome → relay.info → interrupted delivery → process restart → read → repeated ACK.
The trace must contain no `tts.speak`, binary audio or recorded input replay on recovery.

Receipts must include source SHAs, patch checksums, commands, passed/failed/skipped test
counts, DTO/frame bounds, DB/journal disk bound, tested OS and explicit untested live
service/provider/device claims. Nothing here authorizes a public release. Prepare one
profile's reviewed backup/rollback package only after controller review, then follow the
approved staged rollout. Rollback disables the flag and restores plugin code while
preserving devices.json and private outbox state; clients fall back to stock features.

## Dependency boundary for genuine continuity and remaining features

The next focused plan must implement a real two-device Relay-owned routed session, not
stop at discovery. Its concrete seam is `SessionSource(platform=gadget,
chat_id='relay-'+server_uuid, chat_type='dm', user_id=authenticated_device_id)`.
`gateway/session.py:687–728` proves DM routing preserves one chat across those distinct
users. `SessionAuthority.register/_reserve_route/_publish_route` reserves/publishes the
canonical session; `admit_native` validates callback provenance and derives the actual
native principal. Use normal Gadget `handle_message` with a durable namespaced message
ID; add a narrow upstream async registration/committed-admission receipt hook if required.
Maintain a private membership/binding registry and explicitly fan out only to granted
subscribers. Test the physical core row/lineage and two principals; no copied transcript.

Task state projects actual core admissions, execution generations, prompt controls and
terminal persistence. Existing history reads reuse scoped SessionDB browse/timeline
functions after opt-in filtering. Existing foreign LOCAL continuation requires an upstream
per-session delegation grant; native_owner/operator must never be forged. Attachments
start with verified core image staging/admission, then add actual document ingestion.
Each gets a small exported SDK/core patch and its own offline/live tests. Keep those gaps
in the program ledger; this first increment's completion cannot close N1/N2/N3 or the
full O5 task-event scope.

## Plan self-review

Coverage is the final-text outbox foundation; the dependency section maps remaining
approved scope to subsequent concrete adapters. Source inspection and syntax checking
of Python plan blocks are design evidence only. No implementation/test pass, rollout or
physical acceptance is claimed. Controller coordinates execution and commits.
