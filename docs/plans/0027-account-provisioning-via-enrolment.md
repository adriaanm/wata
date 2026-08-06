# 0027 — Account provisioning via enrolment

Status: done

Landed 2026-08-06. The one deliberate liberty: the plumbing gap was
Go-only — the FFI already surfaced the remote id per accepted stream, so
the trusted header is strip-and-inject in the listener bridge plus a
strip on every TCP edge, no Rust change (and no in-tree
wata-matrix-spec edit: that read-only TS reference has no dialect
section; the server design doc specifies the endpoint). Everything else
is as written below, gated by admin-smoke (the TCP negatives, the
binding journal round-trip, the inline create) and tunnel-smoke (the
zero-manual-steps arc in one credential-free client process). The field
verification — a factory-clean handset after a QR scan — rides the next
device deploy, which also rebuilds the arm staticlib.

## Problem

The QR enrolment (plan 0014) provisions only the transport identity: the
admin approves a node id into the allowlist and the handset's iroh
connection starts being admitted. The account is still manual — tonight's
first field run needed a `curl` login on the host to mint a token and an
ssh session to hand-write `/etc/wata/config.json` on the device. That is
exactly the kind of step the product model forbids: a parent setting up a
handset for a child must never see tokens, config files, or a shell.

The owner's expectation, stated during the field test: *onboarding via the
QR code should also provision the user and their credentials.*

## Decision

The device's minted iroh keypair becomes its credential. Approval binds an
account to the node id; the first admitted connection exchanges the proven
transport identity for a session. Concretely:

1. **Approval binds an account.** The admin page's approve action on a
   pending enrolment row also selects the account the handset belongs to.
   The server persists the binding `nodeId → userId` alongside the
   allowlist entry (journaled, like the allowlist add).
2. **Login by transport identity.** A new endpoint,
   `POST /_wata/v1/device-login`, takes no credentials. It is served
   **only** over the iroh listener; the handler reads the connection's
   authenticated remote node id, looks up the binding, and answers with a
   freshly minted access token + user id + device id for the bound
   account — the same response shape as a password login. On the TCP
   listener (where peer identity cannot be proven) the endpoint answers
   403 unconditionally.
3. **The device closes the loop.** After enrolment, while unauthenticated
   but transport-admitted, wata-fb calls device-login, writes the session
   into its own config (as it already does after a password login), and
   proceeds to sync. The enrolment screen's state machine gains one arc:
   admitted-but-no-session → provisioning → contacts.
4. **Revocation is symmetric.** Removing a handset = disallow the node id
   + drop the binding + invalidate its tokens; all live, via the existing
   admin surface.

### Trust argument

The iroh handshake proves possession of the device's secret key; the admin
approved that exact key while looking at the physical handset's screen.
The connection therefore *is* the authenticated channel — a token sent
over it adds no security a bound node id doesn't already have, and a
password typed on a 160×128 keypad adds only friction. Passwords remain a
human-at-the-admin-page (and later phone-client) concern.

### Account selection at approve: pick or create inline

**Owner ruling 2026-08-06**: streamline it. The approve dialog offers the
existing roster to pick from *and* a bare inline name field that creates
the account on the spot and binds it — one step, no detour through a
separate account form. The stakes are low, so the friction should be too;
a casually minted name is renameable, an interrupted onboarding is not.
(The deliberate-roster alternative — create accounts first, approve only
binds — was considered and rejected as heavier than the risk warrants.)

## What changes (file-level)

- `go-pkgs/irohnet` (Rust + FFI + Go): surface the remote node id
  per-connection to the HTTP bridge; inject it as a trusted header
  (`X-Wata-Node-Id`, stripped from any inbound request first) on requests
  arriving over iroh. This is the one real plumbing gap — the accept path
  knows the id today but the handler never sees it. The same header is
  what plan 0020's command mailbox would rather authenticate on than
  bearer tokens.
- `wata-server`: binding store (journal event + in-memory map),
  `/_wata/v1/device-login`, approve API takes an optional `userId`,
  enroll GET includes the roster so the page can render the picker.
- `wata-server/adminui`: account picker + create-and-bind on the approve
  row; bound-account shown on enrolled rows.
- `wataclient` + `wata-fb`: device-login call in the enrol applet's
  admitted-but-unauthenticated state; config write reuses the existing
  session-save path.
- Docs: wata-server, wata-fb, wataclient design docs; wata-matrix-spec
  dialect section gains the endpoint.

## Verification

- Server unit: binding journal round-trip; device-login refused on TCP,
  refused for an admitted-but-unbound node, succeeds for a bound one.
- `tunnel-smoke` extension: enrol → approve-with-account → device-login
  over the same client process → authenticated sync call, all in one run.
- Integ: the admin page picker exercised headless (approve with account),
  then the fb enrol flow against it.
- Field: re-run tonight's flow on the real handset with a factory-clean
  config — the acceptance test is *zero manual steps after the QR scan*.

## Field follow-ups (2026-08-06)

- **The approve page states outcomes, not verbs.** The owner read a terse
  successful approve as an error. The page now says "approved `<id>` —
  bound to `<user>`; the handset will connect itself", and every
  already-enrolled line (scanned fragment, post-approve refresh, typed
  code) names the bound account. Copy details:
  `docs/design/wata-server.md`, Device enrolment.

## Out of scope

- Re-keying / moving a handset between accounts (unbind + re-enrol covers it).
- Multi-handset-per-account policy (allowed by the model; nothing forbids
  two bindings to one user).
- The stale-adminUrl QR (ENROL-QR-STALE-ADMINURL) — related onboarding
  polish, tracked separately.
- eSIM/wifi first-contact (how the handset gets a network before it can
  enrol) — ESIM-PROVISION / plan 0020.
