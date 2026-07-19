# UnifiedShim

A standalone **cross-profile UnifiedPush bridge**. One APK, installed in two (or
more) Android profiles, behaves differently per profile:

- **Any non-owner profile** (private space *or* work profile) → acts as a standard
  **UnifiedPush distributor**. Any unmodified UP app picks it from the normal
  distributor picker. Multiple UP apps can register concurrently.
- **Owner profile** → a UnifiedPush **connector** registered with the **ntfy
  app**. It never opens its own connection to ntfy — the ntfy app holds the only
  radio. When the ntfy app receives a push it wakes the owner shim, which forwards
  inward over a localhost socket.

The instances talk over **TCP loopback** (default `127.0.0.1:28472`) across the
device's shared network namespace, authenticated by a mutual HMAC handshake keyed
by the app's signing certificate. Two distributor profiles share the loopback
netns, so each binds a **distinct, user-configurable port** (see
[Bridge ports](#bridge-ports)) and the owner is told the full set.

> Abstract-namespace unix sockets were the first choice but are **SELinux-blocked
> cross-profile** (the MLS category pair encodes the userId, so `untrusted_app` in
> one profile can't `connectto` another's unix socket — verified by an `avc:
> denied { connectto } … tclass=unix_stream_socket` on-device). Loopback TCP isn't
> subject to that per-peer MAC, so it crosses the shared netns. The cost is losing
> `SO_PEERCRED`, replaced by the cert-keyed handshake (see `transport/Auth.kt`).

The point: keep push working in the distributor profile **without any persistent
radio connection there**. The owner profile already runs the ntfy app (its radio +
foreground service); we add nothing persistent on either the owner radio path or
the distributor profile beyond the listener. The UP app is **not modified**.

---

## Roles

```
OWNER PROFILE (user 0)                              DISTRIBUTOR PROFILE (private space
──────────────────────────────────                  OR work profile; userN, unlocked)
[ntfy app]  ── wss ──► ntfy.sh  (the only radio)    ─────────────────────────────────
   │ UnifiedPush MESSAGE/NEW_ENDPOINT broadcast     DistributorService (FGS, always up)
   ▼                                                  LoopbackServer  (127.0.0.1:<port>)
OwnerPushReceiver (our connector, woken on demand)    DistributorController
   │ OwnerBridge: one-shot loopback connect           EndpointStore
   │             (one per distributor port,           AppMessenger ─► UP broadcasts
   │              routed strictly by token)
   └──── loopback TCP over shared netns (HMAC auth) ──► the UP app (unmodified)
```

- **The distributor listens and stays foregrounded** — it can't be woken
  cross-profile, so its FGS keeps the listener bound.
- **Owner is event-driven** — no foreground service, no websocket (unless the
  optional [auto-provision](#auto-provision-optional) listener is enabled). It is
  woken by the ntfy app's UnifiedPush broadcasts (and by the user opening the app),
  opens a short-lived socket connection, does its business, and closes. With more than one
  distributor profile it opens one session per configured port, sequentially under
  a single lock, and routes every token to the port whose snapshot it appeared in.
- **Role detection** (`role/RoleDetector.kt`): owner/system user has profile
  serial 0 → `OWNER`; **any** other profile (private space *or* work profile) →
  `PRIVATE` (distributor). Override via `Config.forcedRole` (there's a role-cycle
  button in the UI for unusual profile setups).
- **Components are role-gated at startup** (`App.onCreate` →
  `setComponentEnabledSetting`): the distributor stack (`RegisterReceiver`,
  `LinkActivity`, `DistributorService`) is disabled in the owner profile and the
  connector (`OwnerPushReceiver`) in every distributor profile. So the
  wrong-profile stack can't be triggered — e.g. a stray `REGISTER` can't start a
  second listener that contends for the bridge port in the owner profile.

---

## Flows

**Registration / provisioning** — a UP app registers with the distributor:

1. `RegisterReceiver` (REGISTER, advertises us as a distributor) → `DistributorService`.
2. `DistributorController` stores a *pending* registration keyed by the connector
   `token`. It can't reach a sleeping owner, so the endpoint is provisioned the
   next time the owner runs — **or, with [auto-provision](#auto-provision-optional)
   enabled, the distributor nudges the owner immediately** and provisioning is
   automatic end to end.
3. When the owner runs (you open the owner app — the manual sync trigger, or it is
   nudged by a distributor), it
   connects to the private listener. The private side pushes its full
   `REGISTRATIONS` snapshot.
4. For each token without an endpoint, the owner calls `UnifiedPush.registerApp`
   against the **ntfy app**, using the *private token as the instance id*.
5. The ntfy app mints a real ntfy endpoint and delivers it to the owner via
   `onNewEndpoint` (a fresh wakeup). The owner connects and sends `ENDPOINT`.
6. The private side stores it and broadcasts `NEW_ENDPOINT` to the app, which
   registers that endpoint + its RFC 8291 Web Push keys with its own server.

**Push** — server POSTs to the endpoint:

1. The app's server POSTs the aes128gcm-encrypted body to the ntfy endpoint.
2. ntfy.sh → the **ntfy app** over its websocket → ntfy app broadcasts `MESSAGE`
   to our `OwnerPushReceiver`, which wakes the owner shim. The instance id is the
   private token.
3. `OwnerBridge` opens the socket and sends `PUSH{token, payload}` (raw bytes).
4. Private acks (`PUSH_ACK`), looks up `token → app`, broadcasts `MESSAGE` to
   the app, which decrypts with its own key.

**Unregister** — private drops the reg and remembers the token in a `deprovision`
set; on the next owner connection the owner calls `UnifiedPush.unregisterApp` on
the ntfy app and confirms with `DEPROVISIONED`.

---

## Bridge protocol

TCP loopback `127.0.0.1:<port>` (default `Config.BRIDGE_PORT` = 28472; each
distributor profile binds its own port — see [Bridge ports](#bridge-ports)).
Before the framed protocol runs, a mutual challenge-response (`transport/Auth.kt`)
proves both ends possess a key derived from the app's signing certificate — both
profiles are the same signed APK. This replaces `SO_PEERCRED` (TCP has no peer
credential). It's a weaker anchor than peercred (the cert isn't truly secret), a
deliberate trade for cross-profile reachability; payloads are E2E-encrypted anyway.

Frame: `[int32 length][byte type][body]`. Control/handshake bodies are UTF-8 JSON;
`PUSH` is binary (byte-exact for aes128gcm payloads). See `transport/protocol/`.

| Type | Dir | Body |
|------|-----|------|
| `HELLO` 0x01 / `HELLO_ACK` 0x02 | owner→priv / priv→owner | JSON version+role handshake |
| `REGISTRATIONS` 0x10 | priv→owner (on connect) | JSON `{active:[token…], deprovision:[token…]}` |
| `ENDPOINT` 0x11 | owner→priv | JSON `{token, endpoint}` |
| `DEPROVISIONED` 0x12 | owner→priv | JSON `{token}` |
| `PUSH` 0x20 | owner→priv | `[int64 msgId][int16 tokenLen][token][payload]` |
| `PUSH_ACK` 0x21 | priv→owner | JSON `{msgId}` |
| `NUDGE` 0x30 | priv→owner | JSON `{port}` — flows on the **owner's** [auto-provision](#auto-provision-optional) listener (not the distributor listener); the sender's own bridge port |

The owner is the client and connects per wakeup; the private side is the server
and pushes `REGISTRATIONS` immediately after the handshake so the exchange is
reconciliation-based rather than request/response (the owner can't be polled).

---

## Bridge ports

Every distributor profile binds a loopback port, but all profiles share the
device's single loopback netns — so two distributor profiles (private space **and**
a work profile) **cannot both bind the default `28472`**. Give each a distinct
port and tell the owner about all of them:

1. In **each distributor profile** (UnifiedShim → PRIVATE role screen): set a
   **distinct** *Bridge port* (e.g. `28472` in the private space, `28473` in the
   work profile) and tap **Apply port**. If the service is already running it
   restarts to rebind. Valid range `1024..65535`.
2. In the **owner profile** (UnifiedShim → OWNER role screen): enter the
   **comma-separated list** of every distributor port (e.g. `28472,28473`) in
   *Distributor ports* and tap **Apply ports**.

The owner opens one short-lived session per port on each wakeup and routes strictly
by token: it learns `token → port` from whichever port's `REGISTRATIONS` snapshot a
token appears in, and only ever flushes a queued push to the port that owns its
token. (A distributor acks-and-drops pushes for unknown tokens, so sending a push to
the wrong port would falsely ack and permanently lose it — hence the strict
routing.) A single distributor profile needs no configuration; the defaults
(`28472` / `[28472]`) just work.

Persisted in `Config` (`bridgePort`, `ownerBridgePorts`) and, on the owner, the
`token → port` map in `OwnerStore`.

---

## Auto-provision (optional)

By default the owner profile runs **nothing persistent** and a brand-new
registration waits until you open the owner app once (the manual sync). That
manual step is the price of the "no persistent owner component" property.

**Auto-provision** trades that property for full automation: registrations
provision **start to finish with no manual sync anywhere**. It is **off by
default** — turning it on adds an always-on foreground service in the *owner*
profile (`OwnerListenerService`, a `specialUse` FGS like the distributor's),
relaxing the "nothing persistent in the owner profile" constraint. That's the
whole trade-off; the manual-open flow still works and remains the default.

How it works: the owner listener binds its own loopback port. When a registration
changes (or the distributor service restarts with pending work), the distributor
opens a one-shot authenticated connection to that port and sends a `NUDGE` frame
carrying its own bridge port. The owner reconciles that single port immediately —
exactly the work `Sync now` would do. The nudge fires **only** on registration
state changes and distributor startup, never in response to owner-initiated
traffic, so there's no feedback loop. A dead or disabled owner listener is
indistinguishable from manual mode (every nudge is best-effort and swallowed).

The owner **auto-learns distributor ports from nudges**: the auth handshake already
proves the peer is our own signed APK in another profile, so a nudged port that
isn't in *Distributor ports* yet is added automatically — no owner-side port config
needed for a nudging distributor.

Setup:
- **Owner** (OWNER screen): tap **Auto-provision listener: OFF** to turn it **ON**.
  Note the listener port (default `28471`); change it with **Apply listener port**
  if needed.
- **Each distributor** (PRIVATE screen): set **Owner port** to the owner's listener
  port (default `28471`). `0` disables nudging for that profile (manual
  provisioning).

The owner listener shares the device's single loopback netns with every
distributor listener, so its port must be **distinct from every bridge port**
(the defaults `28471` vs `28472` already are) — a collision leaves whichever
side binds second stuck retrying.

Persisted in `Config` (`ownerListenerEnabled`, `ownerListenerPort`,
`ownerNudgePort`). Started from the owner toggle and, on boot, by `BootReceiver`
when `ownerListenerEnabled`.

---

## Module / package layout

Single `:app` module; the **`transport/` package is the isolation seam** — both
controllers only touch a (stream-based) `BridgeLink`. If the loopback approach is
ever blocked too, swap `loopback/` and nothing else changes.

```
com.tigerlake.unifiedshim
├── role/          RoleDetector, Role, ServiceLauncher
├── common/        Config, Logx, Notifications
├── transport/     ← rip-out seam (cross-profile loopback)
│   ├── protocol/  Frame, Protocol
│   ├── Auth       (cert-keyed mutual HMAC handshake)
│   ├── BridgeLink (stream-based, transport-agnostic)
│   └── loopback/  LoopbackServer (private), LoopbackClient (owner one-shot)
├── distributor/   (distributor role) RegisterReceiver, LinkActivity, DistributorService,
│                   DistributorController, EndpointStore, AppMessenger, UnifiedPush, OwnerNudger
├── owner/         (owner role) OwnerPushReceiver (UP connector), OwnerBridge, OwnerStore,
│                   OwnerListenerService (optional auto-provision FGS)
├── boot/          BootReceiver (starts the distributor FGS; owner FGS only if auto-provision on)
└── ui/            MainActivity (status + role/port config + distributor picker + manual sync)
```

---

## Build & install

Built with the Android SDK at `/opt/android-sdk` (Gradle 8.14.2 / AGP 8.7.3 /
Kotlin 2.1.0):

```bash
./gradlew :app:assembleRelease
# release is signed with unified-shim-release.jks (see app/build.gradle.kts)
adb install -r app/build/outputs/apk/release/app-release.apk                 # owner profile
adb install -r --user <PRIVATE_USER_ID> app/build/.../app-release.apk        # private space
```

Find the private space user id with `adb shell pm list users`.

Both profiles must run the **same** signed APK: the cross-profile auth handshake
is keyed by the signing certificate, so a debug-signed and a release-signed copy
won't pair. Switching signing (e.g. from an earlier debug install) requires an
**uninstall first** in each profile (`adb uninstall [--user N] com.tigerlake.unifiedshim`),
since Android won't update across different signers.

### Android 17+ (cross-profile loopback permission)

Android 17 (June 2026) gates cross-profile loopback behind
`android.permission.INTERACT_ACROSS_USERS`. The app declares it; it's
`development`-protection, so grant it over ADB (no root) in **every** profile the
app runs in, then restart:

```bash
adb shell pm grant --user 0  com.tigerlake.unifiedshim android.permission.INTERACT_ACROSS_USERS
adb shell pm grant --user 10 com.tigerlake.unifiedshim android.permission.INTERACT_ACROSS_USERS
adb shell am force-stop --user 10 com.tigerlake.unifiedshim
```

(Use your private-space user id from `pm list users` in place of `10`.) On Android
16 and earlier this isn't needed — loopback crosses profiles freely. The owner
status screen prints the exact command on Android 17+. Same mechanism the
Inter Profile Sharing app uses for the same OS change.

Setup, **in each profile**:
- Owner: install the **ntfy app**, point it at your ntfy server, then open
  UnifiedShim and tap **Use: io.heckel.ntfy** to select it as distributor. If you
  run more than one distributor profile, set **Distributor ports** to the
  comma-separated list of their ports (see [Bridge ports](#bridge-ports)).
- Distributor profile (private space or work profile): open UnifiedShim, optionally
  set a distinct **Bridge port**, tap **Start distributor service**, then in your UP
  app pick **UnifiedShim** as the UnifiedPush distributor. Any number of UP apps
  can register.
- Whenever a UP app creates a new registration (e.g. you add a mail account),
  **open the owner UnifiedShim once** so it provisions that registration's
  endpoint (the distributor can't wake it).

Logs: `adb logcat -s ushim`.

---

## Constraints honored / caveats

- **The owner shim never connects to ntfy.sh.** It is a UnifiedPush connector to
  the ntfy app; the ntfy app owns the single radio + FGS in the owner profile.
- **No persistent component in the owner profile beyond the ntfy app** — unless the
  optional [auto-provision](#auto-provision-optional) listener is enabled. Our owner
  shim is otherwise woken on demand by UnifiedPush broadcasts.
- **TCP loopback, not unix sockets.** Filesystem-path sockets fail under
  per-profile storage isolation, and abstract-namespace sockets are SELinux-blocked
  cross-profile (MLS `connectto` denial). Loopback TCP crosses the shared netns;
  quarantined behind `transport/loopback/`.
- **Private FGS is mandatory and uses `specialUse`** (not `dataSync`, which is
  capped at 6h/day on API 35).
- **Binary-safe end to end.** Payloads are RFC 8291 (aes128gcm) encrypted; the
  `PUSH` frame and `EXTRA_BYTES_MESSAGE` keep them byte-exact, and connector 2.5.0
  reads them via `getByteArrayExtra`. The receiving app decrypts with its own key.
- **First provisioning of a new registration needs the owner app opened once** — the
  private side can't wake a sleeping owner. Subsequent pushes flow automatically.
  The optional [auto-provision](#auto-provision-optional) listener removes even this
  one manual step, at the cost of a persistent owner-profile FGS.
- **Delivery is at-least-once with de-dupe.** Every push is written to the owner's
  durable outbox (`OwnerStore`) *before* any send and removed only on its
  `PUSH_ACK`, so an unreachable private side (locked / rebooting / Android-17 grant
  missing) queues the push instead of dropping it; it flushes on the next
  connection. The private side de-dupes by `msgId`, so a retried push isn't
  delivered to the app twice. The premise is the distributor profile stays
  unlocked, so queuing is the rare case.
- **A REGISTER is persisted even if the distributor FGS can't start** from the
  background — `RegisterReceiver` writes the pending registration to the store
  directly, so it isn't lost to the background-FGS-start restriction; the owner's
  next reconcile provisions it.
