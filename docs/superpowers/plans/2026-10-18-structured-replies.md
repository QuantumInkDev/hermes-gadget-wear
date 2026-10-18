# Structured replies for phone and watch

Status: execution plan for approved N6. This consumes ordinary validated reply text;
no new server capability, provider call or administrative API is required.

## Outcome and boundaries

Readable phone cards offer deliberate Copy/Open/Check actions for code, safe links,
explicit addresses and checklists. Watch cards remain concise below the unobstructed
portrait/pet. The complete response remains available as plain text. Existing native
approval/action/card semantics are separate; checking a response item never executes a
tool, approves a request, changes a server task or sends a message.

## Global Constraints

- Preserve stock v0.2.0, the Android-free protocol module and all existing reply validation.
- Never fetch a remote preview/image, execute code, navigate, copy or send automatically.
- No arbitrary HTML, WebView, intent URI, script, command or server-supplied component.
- Keep full validated text, profile isolation, drafts, gestures, themes and clear artwork.
- Public fixtures/captures use synthetic content; raw content and URLs never enter logs,
  diagnostics or model toString. Controller owns physical tests and preserving installs.
- Do not overlap phone audio or watch presentation implementation files. Native integration
  follows those reviewed source heads; parser work is independently scoped.

## Verified source seams

Conversation bounds ordinary reply text to 32,768 characters. Phone ConversationContent
currently renders it with Text; watch renders it with Body. Existing Card and Picture
are distinct validated protocol objects and remain unchanged. New parsing is a bounded
presentation layer over that accepted text, not a new source of authority or history.

## Task 1: Pure bounded reply parser and action descriptors

Own new protocol ReplyPresentation.kt and meaningful JVM tests only. No Android/native
or Conversation reducer changes in this task.

- [ ] Define typed immutable blocks for plain text, fenced code, safe link, explicit
  address and checklist; expose `ReplyPresentation.parse(text)` and safe action descriptors.
  Keep all model toString output content-redacted. Unknown/malformed syntax remains text.
- [ ] Parse in linear bounded passes over at most 32,768 characters, with at most 16
  rendered blocks, 16 links, 32 checklist entries and 8,192 characters per code block.
  Additional/oversized elements remain readable plain text, without silent omission.
  For input above the accepted reply bound, return a typed unsupported result rather
  than parsing an unbounded input. No logging, network, clock or platform dependency.
- [ ] Support triple-backtick fenced code with an optional bounded language label;
  preserve exact code/line breaks and treat an unclosed fence as plain text. Do not parse
  links or checkboxes inside code. Copy includes exact displayed code; no Run action.
- [ ] Recognize Markdown links and isolated HTTPS URLs only when java.net.URI validation
  proves a nonempty host, no userinfo, valid optional port and no whitespace/control
  characters; cap URL at 2,048 and display label at 256 characters. Reject non-HTTPS,
  opaque, malformed and executable schemes as actions while retaining their plain text.
  Expose the actual host with the action so a friendly label cannot hide its destination.
- [ ] Recognize an explicit standalone `Address: ` line with at most 512 characters of
  nonempty address text. Preserve that text; the platform adapter later constructs a
  fixed geo query with proper encoding. No guessed geolocation or background lookups.
- [ ] Recognize consecutive Markdown `- [ ]` / `- [x]` / `- [X]` items, each at most 512
  characters. Store their initial checked state and stable block/item positions within
  this parsed response. Excess/malformed entries stay plain. A checkbox is presentation
  state only and never maps to a protocol Action or approval response.
- [ ] Add failing behavioral tests for mixed blocks, exact code copying, Unicode/newlines,
  unterminated/malicious syntax, dangerous schemes/userinfo/control/port tricks, escaped
  labels, address query delimiters, checklists inside fences, every boundary and oversized
  fallback. Assert reconstructable retained text/no dropped tails, deterministic results
  and redacted toString. A large adversarial input must not recurse or grow without bound.
- [ ] Run affected protocol JVM tests and strict formatting/build checks. Record genuine
  RED/GREEN, commands/counts/skips, source commit and concerns in the task report. Commit
  exact owned paths, no push. Controller dispatches independent combined review.

## Task 2: Native cards and deliberate platform actions

Depends on Task 1 review plus reviewed phone audio/watch presentation source integration.
Own focused new phone/watch reply components, platform action adapters and current reply
insertion points. Coordinate any small reducer identity field with protocol ownership.

- [ ] Render parsed blocks with existing Light/Dark/Moonlight tokens, selectable text,
  accessible labels and bounded scrolling. Retain an explicit full-text view/copy path.
  Phone shows full detail; watch shows short code/link/address/checklist summaries with
  deliberate expansion. Do not insert controls over the portrait or move Jump from last.
- [ ] Copy is explicit through Android ClipboardManager, with private-content treatment
  where supported. Open uses only a new fixed ACTION_VIEW/chooser constructed by the
  platform adapter from the validated descriptor. Address query delimiters are encoded;
  missing handlers show readable unsupported state. No arbitrary intent extras/component,
  auto-opening, URL fetch or code execution. Tests intercept real intents/clipboard.
- [ ] Check states belong to an exact mounted response and clear for a new turn/profile,
  reconnect replacement or changed response. Add a bounded local presentation revision
  at an actual reducer turn boundary if needed; never treat content equality/time or
  diagnostic uncertainty as durable server message identity. Disable checks while a
  response is still changing. N1 later supplies canonical message identity for retention;
  do not claim persistence or server task mutation here.
- [ ] Any optional Open on phone path must authenticate the associated Wear node and
  validate exact endpoint/profile/response generation, with explicit destination review.
  Implement/test it or show a truthful unavailable state; never launch a different bot.
- [ ] Verify real native interaction for copy/open/check, plain fallback, no-handler,
  process/profile/late-callback replacement and busy/approval locks. Exercise all themes,
  large font/narrow phone and round watch layouts via owned device_list/device_open
  emulators. No real external navigation, service/provider or physical operations by worker.
- [ ] Strict affected JVM/native checks and APK builds; source/APK-bound synthetic captures
  and no unproven physical claims. Independent review precedes preserving physical delivery.

## Preflight rulings

| Producer / consumer | Resolution |
| --- | --- |
| Validated reply / parser | Parser owns presentation only; excess syntax remains plain text. |
| Link/address descriptor / Android | Fixed allowlisted adapter constructs intent; text supplies no component or extras. |
| Checkbox / actual task state | Local response presentation only; never a remote action or inferred completion. |
| Equal text across separate turns / UI state | A true turn-bound local revision resets state; canonical identity awaits N1. |
| Native ownership / parallel work | Parser is disjoint; phone/watch insertion waits for reviewed implementation heads. |

Final N6 acceptance needs actual delivered phone/watch interactions and full-text fallback;
parser tests alone cannot establish native action safety or legibility.
