# Windows relay storage privacy

Focused O5 follow-up under the approved expansion design. Depends on independently
reviewed server-foundations Task 3. This is required implementation for the two Windows
boxes, not permission to remove the existing fail-closed Windows activation gate.

## Goal

Enable the optional final-text outbox on supported Windows hosts only after the actual
process verifies private NTFS storage and enrollment/grant reads. The default and every
failed/unsupported check retain stock Gadget behavior with no relay advertisement.

## Global Constraints

- Preserve stock v0.2.0 behavior with every optional capability disabled.
- Keep phone/watch enrollments independent and all existing profiles/artwork unchanged.
- No new runtime package dependency, subprocess-based localized ACL parsing, privilege
  elevation, user creation, account/password changes or installed-service changes.
- No personal endpoints, tailnet details, credentials or private source changes in public
  files. Synthetic public fixtures; real-host receipts stay ignored under `.local/`.
- Never replay recordings, actions, approval answers or paid synthesis.
- Do not alter installed directory/file permissions automatically. Refuse unsafe existing
  storage and report a concise setup failure; controller prepares any necessary scoped
  remediation for concrete review under the approved rollout.
- A mocked Windows API or POSIX chmod result is not Windows privacy evidence.
- No capability advertisement or live activation until code review and actual-host gates.

## Verified seams and primary references

The SDK's current `Grants.snapshot`, `private_directory`, and adapter connect path
explicitly deny Windows. Outbox creates its database with mode 0600 and uses SQLite
DELETE journaling; those POSIX modes do not establish a Windows DACL. An actual native
validator must cover the directory, grant/enrollment authority, existing database and
journal, plus inherited permissions on newly created database/journal files.

Read-only inventories found owner/SYSTEM/Administrators grants on inspected existing
Windows directories and no inspected reparse points. That does not prove future-file
privacy, current process authority, or a negative kernel access result.

Use primary Microsoft references, checked 2026-10-18:

- [CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew):
  open existing objects, directory/reparse flags and explicit sharing semantics.
- [GetSecurityInfo](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-getsecurityinfo):
  retrieve owner/DACL from the opened handle and free the returned allocation.
- [Access control lists](https://learn.microsoft.com/en-us/windows/win32/secauthz/access-control-lists):
  distinguish missing/null DACL, effective and inheritable entries.
- [CreateRestrictedToken](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-createrestrictedtoken):
  a reduced-authority native negative probe without creating an account. A restricted-token
  test must describe its exact token; it must not claim to be a second real user account.

## Task 1: Native validator and guarded integration

Owned isolated SDK paths: one focused Windows privacy module, narrow `relay_grants.py`,
`relay_outbox.py`, `store.py` and adapter integration if required, focused Windows/native
and platform-neutral tests, and extension setup documentation. Start from the accepted
Task 3 head in a fresh isolated checkout; do not edit its active review/fix checkout.
The controller owns final patch-series export and live rollout.

- [x] Specify a small private-file/directory interface shared by grant and activation
  checks. Keep existing POSIX semantics and public APIs unless the concrete consumer
  requires a reviewed change. No generic security framework or persistent bypass flag.
- [x] Implement native Windows inspection through standard-library ctypes with exact
  pointer-width-safe signatures and bounded allocations/counts. Determine the actual
  process user SID from its token; do not authorize by account name, locale or an
  environment variable. Allowed leaf identities are that SID, LocalSystem and built-in
  Administrators. Elevated/admin users remain within the existing local trust boundary.
- [x] Open existing objects without following reparse points; verify the handle's actual
  file attributes/type, link count and security descriptor. Reject symlinks, junctions,
  other reparse forms, remote/unsupported filesystems, alternate streams, malformed or
  unsupported security descriptors, missing/null DACLs, unknown effective ACE types and
  unresolved authority. Close every handle/allocation on all error paths.
- [x] Require private leaf directory and authority/data owners to be the actual process
  user, LocalSystem or built-in Administrators, with effective and inherited permissions
  limited to those same allowed identities. TrustedInstaller is an ancestor-owner exception
  only, never an allowed private-leaf owner or a blanket service-SID exemption.
  Check existing components through the filesystem root for reparse substitution and
  outsider permissions that can replace/delete/change the protected path. Ordinary
  ancestor traversal is permitted; treat ancestor and private-leaf requirements separately.
  Derive concrete access-mask handling from native constants and test it. Do not simply
  reject every ancestor readable by Users or Everyone.
- [x] Prevent check/read path substitution at the actual grant/enrollment read boundary:
  inspect and read the same opened object, with bounded bytes and preserved shape/load-
  health validation. A later file/path replacement cannot grant an old socket new authority.
  Unsafe/malformed reads deny access without deleting retained outbox bodies or secrets.
- [x] Validate database and journal privacy before accepting the extension, including a
  newly created SQLite journal during a real transaction. Existing unsafe files must not
  be repaired by chmod or ACL mutation. A transient validation failure must not turn an
  unreadable registry into an authoritative empty registry. Preserve transaction bounds,
  durability, deduplication and accepted current-grant semantics.
- [x] Keep Win32 imports lazy so stock-disabled and POSIX deployments do not require the
  native module. Remove Windows deny branches only where the real validator replaces
  them. Any failed check keeps the optional service unavailable and stock speech usable.
- [x] Add genuine failing regressions for null/permissive/inheritable DACLs, wrong owner,
  unrecognized ACEs, unsafe ancestor replacement rights, reparse/hardlink/stream paths,
  grant replacement between check/read, newly created sidecars and native API failure.
  Unit API doubles exercise parser/error paths only, never establish native acceptance.
- [x] Add actual Windows-only tests using temporary synthetic directories/files and the
  existing outbox: positive private ACL and normal read/write/restart/ACK; negative broad
  effective or inherited grants; reparse/junction and hardlink refusal where supported;
  database and active journal descriptor inspection; reduced-authority native read denial
  and a permissive control proving that the negative token can read ordinary test data.
  Impersonation must be confined to a disposable test process and reverted in finally;
  privileges stay disabled. No real identities/history/grants enter these fixtures.
- [x] Controller runs the native test artifact on GPU 5090 and Win 11 ARM using the actual
  supported gateway interpreter architecture and disposable per-task locations. Record
  OS, interpreter bitness, exact source hash, owner/SID class, assertions and skip reasons
  privately. Missing required native evidence blocks that box's activation; it does not
  complete Windows support as an unsupported-hardware exception.
- [x] Run affected platform-neutral storage/grant/adapter regressions warning-strict,
  retaining stock-disabled compatibility. Do not rerun unrelated core suites without a
  changed seam. Commit exact source paths, no push. Full report includes commands,
  genuine RED/GREEN counts, native-vs-mocked distinction and remaining gates. Independent
  spec/quality review precedes public export, installation or listener restart.

## Preflight

| Interface/tasks | Check | Ruling |
| --- | --- | --- |
| Server Task 3 / this Task 1 | Same adapter, enrollment and grant authority seams | Execute after Task 3 review/fixes; never race its checkout or export stale code. |
| Leaf privacy / ancestor traversal | Publicly traversable ancestors are normal | Verify replacement rights separately from leaf read/creation inheritance. |
| SQLite creation / native descriptors | Database mode bits are insufficient | Prove actual newly created DB and active journal DACLs before activation. |
| Negative native access / account scope | Creating another account is outside scope | Use a described reduced-authority test token and readable control; no account changes. |
| Existing unsafe state / automatic repair | Validation cannot authorize a broad ACL rewrite | Deny and retain bytes; controller handles any concrete scoped remediation. |

Ruling: retain Windows as a required software/host-validation task. The cost is native
ACL and race-boundary work plus two actual-host runs; removing the guard based on existing
Get-Acl output would fail the approved per-box privacy/rollout contract.

## Native evidence clarification after fix round 1

The actual SSH test contexts use a built-in Administrators default token owner; newly
created synthetic files therefore have Administrators ownership. One inspected existing
private parent is also Administrators-owned. Requiring TokenUser-only ownership conflicts
with the explicitly allowed local SYSTEM/Administrators trust boundary and fails all
positive methods before their intended checks. No installed ownership or ACL is changed.

Ruling: allow only TokenUser, LocalSystem or built-in Administrators as private-leaf
owners, while retaining the existing effective/future DACL allowlist, all native path and
same-object checks, and unknown-owner refusal. TrustedInstaller remains ancestor-owner-only.
Cost if wrong: the small ownership policy needs rework before activation; native runs and
fresh independent security review still gate both boxes. This permits no new local identity
beyond the plan's existing trust boundary and does not waive any mandatory native method.

## Verified Task1 completion

Source/fix reviews through2b64698 are clean. Public patch6 freshly reapplies after
patches1–5; all274 combined SDK files match reviewed source.402 affected software
checks pass, with10 explicit Windows-only skips on Mac. Actual supported AMD64 and
ARM64 gateway interpreters each pass10 native methods, zero failures/errors/skips.
Control bytes, exact private0xC0000022, disabled privileges, reversion and fixture/source
cleanup are source-bound in private receipts. This completes this validator task, not
O5 native clients or live rollout. Existing installed source/owners/ACLs/listeners and
flags are unchanged; each installed profile still needs its own activation preflight.
