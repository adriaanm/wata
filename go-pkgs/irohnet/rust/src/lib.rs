//! irohnet-ffi — a blocking C ABI over iroh, shaped for Go's net.Conn/net.Listener.
//!
//! The Go side (go-pkgs/irohnet) calls these from goroutines; every blocking
//! call parks on an internal tokio runtime via `block_on`. One QUIC
//! bidirectional stream = one net.Conn; one iroh connection multiplexes the
//! streams (the client caches its connection per dial target, the server
//! accepts streams off every live connection into one queue).
//!
//! Deadline semantics (the net.Conn contract): each stream stores a read and a
//! write deadline (unix millis, 0 = none). Setting a deadline wakes any
//! blocked read/write on that stream (a tokio watch channel bumps them), so a
//! deadline moved into the past interrupts a pending call — exactly what
//! net/http relies on for Close/timeouts. `read`/`write` on iroh streams are
//! documented cancel-safe (noq recv_stream.rs / send_stream.rs), which is
//! what makes select!-based interruption honest: an interrupted write has
//! written nothing, an interrupted read has consumed nothing.
//!
//! Return-code protocol for stream I/O:
//!   >= 0  bytes transferred
//!   -1    EOF (read: the peer finished the stream)
//!   -2    deadline exceeded
//!   -3    stream closed locally
//!   -4    stream error (reset / connection lost)

use std::collections::{HashMap, HashSet};
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use iroh::endpoint::{
    presets, ApplicationClose, ConnectError, Connection, ConnectionError, ReadError, RecvStream,
    SendStream, VarInt, WriteError,
};
use iroh::{Endpoint, EndpointAddr, EndpointId, SecretKey, TransportAddr};
use tokio::sync::{mpsc, watch};

/// ALPN identifying "HTTP over a wata iroh stream". Both sides must match.
const ALPN: &[u8] = b"wata/iroh-http/0";

const RET_EOF: i64 = -1;
const RET_TIMEOUT: i64 = -2;
const RET_CLOSED: i64 = -3;
const RET_ERR: i64 = -4;

// ---- runtime + handle registry ---------------------------------------------

fn rt() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("irohnet: tokio runtime")
    })
}

struct AcceptedStream {
    send: SendStream,
    recv: RecvStream,
    remote: String,
}

struct ServerState {
    endpoint: Endpoint,
    rx: tokio::sync::Mutex<mpsc::Receiver<AcceptedStream>>,
    gate: Arc<Gate>,
}

/// The allowlist the accept loop enforces, plus the connections it has
/// admitted — SHARED (Arc) between that loop and the mutating FFI calls, so an
/// id added after the listener was built takes effect immediately instead of
/// at the next restart. That is what device enrolment needs: a parent approves
/// a handset in the admin interface (plan 0021 milestone B) and the handset's
/// next dial is accepted, with the durable copy of the decision living in the
/// server's iroh config file.
///
/// `allow_all` is fixed at creation (the single entry `*`); the id set and the
/// live-connection table are behind plain `std::sync::Mutex`es — every access
/// is a set/vec operation with no await inside, so a blocking mutex is the
/// right one even on the tokio side.
struct Gate {
    allow_all: bool,
    ids: Mutex<HashSet<EndpointId>>,
    /// (token, remote id, connection) for every admitted, still-live
    /// connection. The token is a local monotonic tag so removal never needs
    /// connection equality.
    conns: Mutex<Vec<(u64, EndpointId, Connection)>>,
    next_conn: AtomicU64,
}

impl Gate {
    fn admits(&self, id: &EndpointId) -> bool {
        self.allow_all || self.ids.lock().unwrap().contains(id)
    }

    fn allow(&self, id: EndpointId) {
        self.ids.lock().unwrap().insert(id);
    }

    /// Drop an id AND close the connections it already holds — a revoked
    /// handset stops talking now, not at its next dial.
    fn disallow(&self, id: &EndpointId) {
        self.ids.lock().unwrap().remove(id);
        let mut guard = self.conns.lock().unwrap();
        for (_, cid, c) in guard.iter() {
            if cid == id {
                c.close(VarInt::from_u32(401), b"not allowlisted");
            }
        }
        guard.retain(|(_, cid, _)| cid != id);
    }

    fn track(&self, id: EndpointId, c: Connection) -> u64 {
        let token = self.next_conn.fetch_add(1, Ordering::SeqCst);
        self.conns.lock().unwrap().push((token, id, c));
        token
    }

    fn untrack(&self, token: u64) {
        self.conns.lock().unwrap().retain(|(t, _, _)| *t != token);
    }
}

struct ClientState {
    /// Swappable: `maybe_rebuild_endpoint` replaces a long-refused client's
    /// endpoint wholesale. Everything that dials clones the current one out of
    /// the lock first.
    endpoint: tokio::sync::Mutex<Endpoint>,
    /// What `build_endpoint` needs to build a replacement.
    secret_hex: String,
    relay: String,
    peer: EndpointAddr,
    conn: tokio::sync::Mutex<Option<Connection>>,
    /// The last peer-initiated close seen on this client, so callers (and the
    /// Go log-once-per-reason layer) can see a stable reason even once the
    /// dead connection is behind a fast local failure rather than a fresh
    /// dial.
    last_refusal: Mutex<Option<String>>,
    /// When a HANDSHAKE was last refused. The dead connection answers dials
    /// fast-and-locally only for REFUSAL_COOLDOWN after this; past that, one
    /// fresh handshake is allowed — which is what lets a client recover the
    /// moment its enrolment is approved (plan 0014's
    /// approve-while-the-device-retries loop), instead of staying locked out
    /// until process restart.
    ///
    /// ONLY a real handshake attempt writes it. Stamping it again on each
    /// fast-local refusal slid the window forward every time anything dialled,
    /// so a client whose loops retried faster than the cooldown never earned a
    /// fresh handshake at all and stayed refused until the process restarted —
    /// which is precisely what the first hardware enrolment hit: the admin
    /// approved the device and the running app never noticed.
    refused_at: Mutex<Option<Instant>>,
    /// When the CURRENT unbroken run of dial failures started (refusals or
    /// transport failures — anything but a dial that got through); cleared by
    /// the first successful dial. This is the age `maybe_rebuild_endpoint`
    /// reads: a client that has failed for longer than `rebuild_horizon`
    /// rebuilds its endpoint on the next past-cooldown dial.
    fail_run_started: Mutex<Option<Instant>>,
    /// When the endpoint was last rebuilt — rate-limits rebuilds to one per
    /// horizon (the trigger would otherwise fire on every dial once the run
    /// is old).
    last_rebuild: Mutex<Option<Instant>>,
    /// How many times this client rebuilt its endpoint (irohnet_client_rebuilds
    /// — what the aged-refusal gate asserts on).
    rebuilds: AtomicU64,
    /// Resolved once at client creation: IROHNET_REBUILD_HORIZON_MS, default
    /// 5 minutes, 0 = never rebuild (the pre-rebuild behavior — the gate's red
    /// switch).
    rebuild_horizon: Duration,
}

/// How long a client keeps failing before a dial is allowed to rebuild the
/// endpoint. Field evidence (2026-08-06): a handset refused for 15+ minutes was
/// never admitted after its approval until an app RESTART, which was an instant
/// heal — while 16-minute aged-refusal runs against every layer this repo can
/// exercise on one host (rust latch, iroh connection caching, the Go transport,
/// the app loops; tools/tunnel-smoke.py + aged_refusal_test.go) recover within
/// milliseconds of the approval. What a restart replaces and those runs cannot
/// age is the ENDPOINT's network-facing state — sockets, discovery/resolver
/// state, relay and path state across the device's network move. So a client
/// whose dials have failed for this long swaps in a fresh endpoint (same key,
/// same peer) and dials on: the restart, in process, scoped to the state that
/// was stale. Five minutes is far above any transient blip and far below a
/// parent's patience; IROHNET_REBUILD_HORIZON_MS compresses it for the gate
/// (and 0 disables rebuilds outright — the old behavior, the gate's red run).
const REBUILD_HORIZON_DEFAULT: Duration = Duration::from_secs(300);

fn rebuild_horizon_from_env() -> Duration {
    match std::env::var("IROHNET_REBUILD_HORIZON_MS") {
        Ok(v) => match v.trim().parse::<u64>() {
            Ok(ms) => Duration::from_millis(ms),
            Err(_) => REBUILD_HORIZON_DEFAULT,
        },
        Err(_) => REBUILD_HORIZON_DEFAULT,
    }
}

/// How long a refusal answers dials from the cached dead connection before a
/// fresh handshake is attempted. The sync loop's backoff paces dials anyway;
/// this only collapses a burst of them, so it is short: every second it adds is
/// a second a parent waits after approving a handset.
const REFUSAL_COOLDOWN: Duration = Duration::from_secs(5);

struct StreamState {
    send: tokio::sync::Mutex<SendStream>,
    recv: tokio::sync::Mutex<RecvStream>,
    read_deadline_ms: AtomicI64,
    write_deadline_ms: AtomicI64,
    closed: AtomicBool,
    /// bumped on close and on any deadline change; blocked ops re-evaluate.
    epoch: watch::Sender<()>,
    /// The client that opened this stream, client-side streams only.
    /// Opening a bidirectional stream is a local QUIC operation (bounded by
    /// the peer's already-negotiated stream-limit transport parameter), so
    /// it can succeed even against a connection the peer is already closing
    /// — the allowlist gate's `conn.close(401, ...)` (irohnet_server_new)
    /// often isn't visible until the first read/write on the stream. Reading
    /// the refusal there (irohnet_stream_read/write) needs a way back to the
    /// owning client's `last_refusal`, which is what this is for.
    client: Option<Arc<ClientState>>,
}

enum Obj {
    Server(Arc<ServerState>),
    Client(Arc<ClientState>),
    Stream(Arc<StreamState>),
}

fn registry() -> &'static Mutex<HashMap<u64, Obj>> {
    static REG: OnceLock<Mutex<HashMap<u64, Obj>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn register(o: Obj) -> u64 {
    let id = NEXT_ID.fetch_add(1, Ordering::SeqCst);
    registry().lock().unwrap().insert(id, o);
    id
}

fn get_server(h: u64) -> Option<Arc<ServerState>> {
    match registry().lock().unwrap().get(&h) {
        Some(Obj::Server(s)) => Some(s.clone()),
        _ => None,
    }
}

fn get_client(h: u64) -> Option<Arc<ClientState>> {
    match registry().lock().unwrap().get(&h) {
        Some(Obj::Client(c)) => Some(c.clone()),
        _ => None,
    }
}

fn get_stream(h: u64) -> Option<Arc<StreamState>> {
    match registry().lock().unwrap().get(&h) {
        Some(Obj::Stream(s)) => Some(s.clone()),
        _ => None,
    }
}

fn remove(h: u64) -> Option<Obj> {
    registry().lock().unwrap().remove(&h)
}

// ---- small C helpers -------------------------------------------------------

/// Copy `msg` (truncated) into the caller's buffer as a NUL-terminated C string.
fn put_str(buf: *mut c_char, cap: usize, msg: &str) {
    if buf.is_null() || cap == 0 {
        return;
    }
    let bytes: Vec<u8> = msg.bytes().filter(|&b| b != 0).take(cap - 1).collect();
    let c = CString::new(bytes).unwrap();
    let src = c.as_bytes_with_nul();
    unsafe { std::ptr::copy_nonoverlapping(src.as_ptr() as *const c_char, buf, src.len()) };
}

fn arg_str<'a>(p: *const c_char) -> &'a str {
    if p.is_null() {
        return "";
    }
    unsafe { CStr::from_ptr(p) }.to_str().unwrap_or("")
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// IROHNET_DEBUG=1 turns on stderr tracing of the dial/accept decision points
/// — most importantly whether a given dial performed a REAL fresh QUIC
/// handshake or was answered locally from the cached dead connection. That
/// distinction is invisible in the app's own logs (the Go layer dedupes the
/// refusal string), and it is the first question when a device sits refused:
/// is it still actually asking the server, or replaying its own cache?
fn dbg_on() -> bool {
    static ON: OnceLock<bool> = OnceLock::new();
    *ON.get_or_init(|| std::env::var("IROHNET_DEBUG").map(|v| v == "1").unwrap_or(false))
}

macro_rules! dbg_log {
    ($($arg:tt)*) => {
        if dbg_on() {
            eprintln!("irohnet-dbg[{}]: {}", now_ms() % 1_000_000, format!($($arg)*));
        }
    };
}

fn register_stream(send: SendStream, recv: RecvStream, client: Option<Arc<ClientState>>) -> u64 {
    let (tx, _rx) = watch::channel(());
    register(Obj::Stream(Arc::new(StreamState {
        send: tokio::sync::Mutex::new(send),
        recv: tokio::sync::Mutex::new(recv),
        read_deadline_ms: AtomicI64::new(0),
        write_deadline_ms: AtomicI64::new(0),
        closed: AtomicBool::new(false),
        epoch: tx,
        client,
    })))
}

/// Format a peer-initiated close as `"server refused: <code> <reason>"`, the
/// reason decoded lossy-UTF8 (the wire allows arbitrary bytes). This is the
/// one place the allowlist gate's `conn.close(401, b"not allowlisted")`
/// (irohnet_server_new, the accept loop) becomes readable client-side.
fn format_application_close(ac: &ApplicationClose) -> String {
    format!(
        "server refused: {} {}",
        ac.error_code,
        String::from_utf8_lossy(&ac.reason)
    )
}

/// If a stream/connection error is a peer-initiated application close,
/// format it loud; every other error (transport reset, idle timeout, a
/// locally-closed connection) stays generic — only an application close
/// carries a reason worth surfacing.
fn refusal_message(e: &ConnectionError) -> Option<String> {
    match e {
        ConnectionError::ApplicationClosed(ac) => Some(format_application_close(ac)),
        _ => None,
    }
}

/// Same, for the error `Endpoint::connect` itself can return: a refusal fast
/// enough to land before the connection is considered established still
/// surfaces as `ConnectError::Connection { source: ConnectionError }`.
fn connect_refusal_message(e: &ConnectError) -> Option<String> {
    match e {
        ConnectError::Connection { source, .. } => refusal_message(source),
        _ => None,
    }
}

/// Opening a stream is local-only, so it can outrun the peer's close — the
/// first read/write on a fresh stream is often where an application close
/// (e.g. the allowlist gate) is actually observed, not `open_bi`. When that
/// happens on a client-side stream, record the reason on the owning client
/// so the next dial (or the Go layer via irohnet_client_last_refusal) can
/// report it — the stream I/O return-code protocol stays a plain RET_ERR,
/// this is a side channel, not a protocol change.
/// Note a REAL dial failure (a fresh handshake or its first stream I/O — never
/// the fast-local cached answers): starts the failure run clock that
/// `maybe_rebuild_endpoint` ages, if one is not already running.
fn mark_dial_failed(cl: &ClientState) {
    let mut run = cl.fail_run_started.lock().unwrap();
    if run.is_none() {
        *run = Some(Instant::now());
    }
}

/// The failure run's honest end: bytes actually ARRIVED on a client stream —
/// the peer answered. Neither a dial nor a write can end it: opening a stream
/// is local-only and a write only buffers locally, so both "succeed" on every
/// refused cycle.
fn mark_io_worked(st: &StreamState) {
    if let Some(client) = &st.client {
        let mut run = client.fail_run_started.lock().unwrap();
        if run.is_some() {
            *run = None;
        }
    }
}

fn record_stream_refusal_read(st: &StreamState, e: &ReadError) {
    if let (Some(client), ReadError::ConnectionLost(ce)) = (&st.client, e) {
        if let Some(msg) = refusal_message(ce) {
            dbg_log!("stream read: refusal recorded: {msg}");
            *client.last_refusal.lock().unwrap() = Some(msg);
            *client.refused_at.lock().unwrap() = Some(Instant::now());
            mark_dial_failed(client);
        }
    }
}

fn record_stream_refusal_write(st: &StreamState, e: &WriteError) {
    if let (Some(client), WriteError::ConnectionLost(ce)) = (&st.client, e) {
        if let Some(msg) = refusal_message(ce) {
            dbg_log!("stream write: refusal recorded: {msg}");
            *client.last_refusal.lock().unwrap() = Some(msg);
            *client.refused_at.lock().unwrap() = Some(Instant::now());
            mark_dial_failed(client);
        }
    }
}

async fn build_endpoint(secret_hex: &str, relay: &str, with_alpns: bool) -> Result<Endpoint, String> {
    let mut b = match relay {
        "none" => Endpoint::builder(presets::Minimal),
        _ => Endpoint::builder(presets::N0),
    };
    if !secret_hex.is_empty() {
        let bytes = data_encoding::HEXLOWER
            .decode(secret_hex.trim().as_bytes())
            .map_err(|e| format!("bad secret key hex: {e}"))?;
        let arr: [u8; 32] = bytes
            .as_slice()
            .try_into()
            .map_err(|_| "secret key must be 32 bytes".to_string())?;
        b = b.secret_key(SecretKey::from_bytes(&arr));
    }
    if with_alpns {
        b = b.alpns(vec![ALPN.to_vec()]);
    }
    b.bind().await.map_err(|e| format!("bind: {e:?}"))
}

// ---- keys ------------------------------------------------------------------

/// Generate a fresh secret key; writes lowercase hex of the secret and of the
/// derived endpoint id (public key).
#[no_mangle]
pub extern "C" fn irohnet_gen_key(
    secret_out: *mut c_char,
    secret_cap: usize,
    id_out: *mut c_char,
    id_cap: usize,
) {
    let key = SecretKey::generate();
    put_str(
        secret_out,
        secret_cap,
        &data_encoding::HEXLOWER.encode(&key.to_bytes()),
    );
    put_str(id_out, id_cap, &key.public().to_string());
}

/// Derive the endpoint id (hex) of a secret key (hex). Returns 0, or -1 on a
/// malformed key.
#[no_mangle]
pub extern "C" fn irohnet_id_of(secret_hex: *const c_char, id_out: *mut c_char, id_cap: usize) -> i32 {
    let hex = arg_str(secret_hex);
    let bytes = match data_encoding::HEXLOWER.decode(hex.trim().as_bytes()) {
        Ok(b) => b,
        Err(_) => return -1,
    };
    let arr: [u8; 32] = match bytes.as_slice().try_into() {
        Ok(a) => a,
        Err(_) => return -1,
    };
    put_str(id_out, id_cap, &SecretKey::from_bytes(&arr).public().to_string());
    0
}

// ---- server ----------------------------------------------------------------

/// Create the server endpoint and start accepting. `allowlist_csv` is a
/// comma-separated list of endpoint ids (hex or base32); the single entry `*`
/// admits any peer; an empty list refuses every peer. `relay` is "n0" or
/// "none". Returns 0 and the handle, or -1 with `err_out` filled.
#[no_mangle]
pub extern "C" fn irohnet_server_new(
    secret_hex: *const c_char,
    allowlist_csv: *const c_char,
    relay: *const c_char,
    out_handle: *mut u64,
    err_out: *mut c_char,
    err_cap: usize,
) -> i32 {
    let secret = arg_str(secret_hex).to_string();
    let relay = arg_str(relay).to_string();
    let allow_raw = arg_str(allowlist_csv).to_string();
    let allow_all = allow_raw.trim() == "*";
    let mut allow: Vec<EndpointId> = Vec::new();
    if !allow_all {
        for part in allow_raw.split(',') {
            let part = part.trim();
            if part.is_empty() {
                continue;
            }
            match EndpointId::from_str(part) {
                Ok(id) => allow.push(id),
                Err(e) => {
                    put_str(err_out, err_cap, &format!("bad allowlist id {part}: {e:?}"));
                    return -1;
                }
            }
        }
    }

    let res = rt().block_on(build_endpoint(&secret, &relay, true));
    let endpoint = match res {
        Ok(ep) => ep,
        Err(msg) => {
            put_str(err_out, err_cap, &msg);
            return -1;
        }
    };

    let gate = Arc::new(Gate {
        allow_all,
        ids: Mutex::new(allow.into_iter().collect()),
        conns: Mutex::new(Vec::new()),
        next_conn: AtomicU64::new(1),
    });

    let (tx, rx) = mpsc::channel::<AcceptedStream>(16);
    let ep = endpoint.clone();
    let accept_gate = gate.clone();
    rt().spawn(async move {
        while let Some(incoming) = ep.accept().await {
            let tx = tx.clone();
            let gate = accept_gate.clone();
            tokio::spawn(async move {
                let conn = match incoming.accept() {
                    Ok(accepting) => match accepting.await {
                        Ok(c) => c,
                        Err(_) => return,
                    },
                    Err(_) => return,
                };
                let remote = conn.remote_id();
                if !gate.admits(&remote) {
                    dbg_log!("accept: refused {} (not allowlisted)", remote);
                    // the allowlist gate: refused at accept, before any stream.
                    // Read off the SHARED gate, so an id approved since this
                    // listener was built is admitted without a restart.
                    conn.close(VarInt::from_u32(401), b"not allowlisted");
                    return;
                }
                dbg_log!("accept: admitted {}", remote);
                // tracked while live, so a later disallow can close it.
                let token = gate.track(remote, conn.clone());
                let remote_hex = remote.to_string();
                loop {
                    match conn.accept_bi().await {
                        Ok((send, recv)) => {
                            let item = AcceptedStream {
                                send,
                                recv,
                                remote: remote_hex.clone(),
                            };
                            if tx.send(item).await.is_err() {
                                conn.close(VarInt::from_u32(0), b"listener closed");
                                gate.untrack(token);
                                return;
                            }
                        }
                        Err(_) => {
                            gate.untrack(token);
                            return; // connection gone
                        }
                    }
                }
            });
        }
    });

    let h = register(Obj::Server(Arc::new(ServerState {
        endpoint,
        rx: tokio::sync::Mutex::new(rx),
        gate,
    })));
    unsafe { *out_handle = h };
    0
}

/// The server's endpoint id (hex).
#[no_mangle]
pub extern "C" fn irohnet_server_id(h: u64, out: *mut c_char, cap: usize) -> i32 {
    match get_server(h) {
        Some(s) => {
            put_str(out, cap, &s.endpoint.id().to_string());
            0
        }
        None => -1,
    }
}

/// The server's bound socket addresses, comma-separated (e.g.
/// "0.0.0.0:52011,[::]:52012"). These are the *local* sockets — for a
/// same-machine dial, rewrite an unspecified host to a loopback address.
#[no_mangle]
pub extern "C" fn irohnet_server_addrs(h: u64, out: *mut c_char, cap: usize) -> i32 {
    match get_server(h) {
        Some(s) => {
            let addrs: Vec<String> = s
                .endpoint
                .bound_sockets()
                .into_iter()
                .map(|a| a.to_string())
                .collect();
            put_str(out, cap, &addrs.join(","));
            0
        }
        None => -1,
    }
}

/// Block until a peer opens a bidirectional stream. Returns 0 with the stream
/// handle and the remote endpoint id (hex), -1 if the server was closed.
#[no_mangle]
pub extern "C" fn irohnet_server_accept(
    h: u64,
    out_stream: *mut u64,
    remote_out: *mut c_char,
    remote_cap: usize,
    err_out: *mut c_char,
    err_cap: usize,
) -> i32 {
    let srv = match get_server(h) {
        Some(s) => s,
        None => {
            put_str(err_out, err_cap, "server closed");
            return -1;
        }
    };
    let item = rt().block_on(async { srv.rx.lock().await.recv().await });
    match item {
        Some(acc) => {
            let sh = register_stream(acc.send, acc.recv, None);
            unsafe { *out_stream = sh };
            put_str(remote_out, remote_cap, &acc.remote);
            0
        }
        None => {
            put_str(err_out, err_cap, "server closed");
            -1
        }
    }
}

/// Add a node id to the RUNNING listener's allowlist (device enrolment
/// approval, plan 0021): the next connection from that peer is admitted, with
/// no restart. Idempotent. Returns 0, or -1 with `err_out` filled (unknown
/// handle, or an id that is not a valid endpoint id — the same parse the
/// config's allowlist goes through, so a junk id is refused here rather than
/// silently poisoning the set).
#[no_mangle]
pub extern "C" fn irohnet_server_allow(
    h: u64,
    node_id: *const c_char,
    err_out: *mut c_char,
    err_cap: usize,
) -> i32 {
    match gate_and_id(h, node_id, err_out, err_cap) {
        Some((gate, id)) => {
            gate.allow(id);
            0
        }
        None => -1,
    }
}

/// Remove a node id from the running listener's allowlist AND close the
/// connections it already holds (revocation, the counterpart of
/// `irohnet_server_allow`): the peer's in-flight requests fail and its next
/// dial is refused at accept like any unknown node. Idempotent.
#[no_mangle]
pub extern "C" fn irohnet_server_disallow(
    h: u64,
    node_id: *const c_char,
    err_out: *mut c_char,
    err_cap: usize,
) -> i32 {
    match gate_and_id(h, node_id, err_out, err_cap) {
        Some((gate, id)) => {
            gate.disallow(&id);
            0
        }
        None => -1,
    }
}

/// The shared half of the two mutators: resolve the handle and parse the id,
/// reporting either failure through `err_out`.
fn gate_and_id(
    h: u64,
    node_id: *const c_char,
    err_out: *mut c_char,
    err_cap: usize,
) -> Option<(Arc<Gate>, EndpointId)> {
    let srv = match get_server(h) {
        Some(s) => s,
        None => {
            put_str(err_out, err_cap, "server closed");
            return None;
        }
    };
    match EndpointId::from_str(arg_str(node_id).trim()) {
        Ok(id) => Some((srv.gate.clone(), id)),
        Err(e) => {
            put_str(err_out, err_cap, &format!("bad node id: {e:?}"));
            None
        }
    }
}

/// Close the server endpoint; pending and future accepts return "closed".
#[no_mangle]
pub extern "C" fn irohnet_server_close(h: u64) {
    if let Some(Obj::Server(s)) = remove(h) {
        rt().block_on(async {
            s.endpoint.close().await;
        });
    }
}

// ---- client ----------------------------------------------------------------

/// Create the client endpoint. `peer_id` is the server's endpoint id (hex or
/// base32); `peer_addrs_csv` optionally lists direct socket addresses
/// ("127.0.0.1:52011,..."). With relay "n0", the id alone is dialable via
/// address lookup; with relay "none", addresses are required.
#[no_mangle]
pub extern "C" fn irohnet_client_new(
    secret_hex: *const c_char,
    peer_id: *const c_char,
    peer_addrs_csv: *const c_char,
    relay: *const c_char,
    out_handle: *mut u64,
    err_out: *mut c_char,
    err_cap: usize,
) -> i32 {
    let secret = arg_str(secret_hex).to_string();
    let relay = arg_str(relay).to_string();
    let id = match EndpointId::from_str(arg_str(peer_id).trim()) {
        Ok(id) => id,
        Err(e) => {
            put_str(err_out, err_cap, &format!("bad peer id: {e:?}"));
            return -1;
        }
    };
    let mut peer = EndpointAddr::new(id);
    for part in arg_str(peer_addrs_csv).split(',') {
        let part = part.trim();
        if part.is_empty() {
            continue;
        }
        match part.parse::<std::net::SocketAddr>() {
            Ok(sa) => {
                peer.addrs.insert(TransportAddr::Ip(sa));
            }
            Err(e) => {
                put_str(err_out, err_cap, &format!("bad peer addr {part}: {e}"));
                return -1;
            }
        }
    }

    let res = rt().block_on(build_endpoint(&secret, &relay, false));
    match res {
        Ok(endpoint) => {
            let h = register(Obj::Client(Arc::new(ClientState {
                endpoint: tokio::sync::Mutex::new(endpoint),
                secret_hex: secret,
                relay,
                peer,
                conn: tokio::sync::Mutex::new(None),
                last_refusal: Mutex::new(None),
                refused_at: Mutex::new(None),
                fail_run_started: Mutex::new(None),
                last_rebuild: Mutex::new(None),
                rebuilds: AtomicU64::new(0),
                rebuild_horizon: rebuild_horizon_from_env(),
            })));
            unsafe { *out_handle = h };
            0
        }
        Err(msg) => {
            put_str(err_out, err_cap, &msg);
            -1
        }
    }
}

/// The aged-failure heal: when this client's dials have been failing for
/// longer than `rebuild_horizon` (and no rebuild happened within the last
/// horizon), replace the endpoint with a freshly built one — same key, same
/// peer, new sockets, new discovery/resolver/relay state. Called with the
/// dial's `conn` lock held, right before a fresh handshake, so no stream can
/// be racing onto the old endpoint's cached connection. The old endpoint is
/// closed in the background. A rebuild that fails to bind leaves the old
/// endpoint in place — worse off than before is not an option on a path that
/// is already failing.
async fn maybe_rebuild_endpoint(cl: &Arc<ClientState>) {
    if cl.rebuild_horizon.is_zero() {
        return; // disabled
    }
    let due = {
        let run = cl.fail_run_started.lock().unwrap();
        let aged = run.map(|t| t.elapsed() >= cl.rebuild_horizon).unwrap_or(false);
        let recent = cl
            .last_rebuild
            .lock()
            .unwrap()
            .map(|t| t.elapsed() < cl.rebuild_horizon)
            .unwrap_or(false);
        aged && !recent
    };
    if !due {
        return;
    }
    match build_endpoint(&cl.secret_hex, &cl.relay, false).await {
        Ok(fresh) => {
            let old = {
                let mut guard = cl.endpoint.lock().await;
                std::mem::replace(&mut *guard, fresh)
            };
            *cl.last_rebuild.lock().unwrap() = Some(Instant::now());
            let n = cl.rebuilds.fetch_add(1, Ordering::SeqCst) + 1;
            dbg_log!("dial: endpoint REBUILT (aged failure run; rebuild #{n})");
            rt().spawn(async move { old.close().await });
        }
        Err(msg) => {
            dbg_log!("dial: endpoint rebuild failed, keeping the old one: {msg}");
        }
    }
}

/// Open a bidirectional stream to the configured peer, connecting (or
/// reconnecting) as needed. Blocks up to `timeout_ms` (<=0: 30s). Returns 0
/// with the stream handle, or -1 (err_out filled).
#[no_mangle]
pub extern "C" fn irohnet_client_dial(
    h: u64,
    timeout_ms: i64,
    out_stream: *mut u64,
    err_out: *mut c_char,
    err_cap: usize,
) -> i32 {
    let cl = match get_client(h) {
        Some(c) => c,
        None => {
            put_str(err_out, err_cap, "client closed");
            return -1;
        }
    };
    let fut = async {
        let mut guard = cl.conn.lock().await;
        if let Some(c) = guard.as_ref() {
            match c.open_bi().await {
                Ok(pair) => return Ok(pair),
                Err(e) => {
                    if let Some(msg) = refusal_message(&e) {
                        // The peer closed this connection on purpose (e.g. the
                        // allowlist gate). Within REFUSAL_COOLDOWN of the last
                        // refusal, answer from the dead connection — fast,
                        // local, loud exactly once per distinct reason (the Go
                        // layer dedupes on that string). Past the cooldown,
                        // fall through to ONE fresh handshake: the server's
                        // answer may have changed (enrolment approved), and a
                        // client must be able to recover without a restart.
                        let recent = cl
                            .refused_at
                            .lock()
                            .unwrap()
                            .map(|t| t.elapsed() < REFUSAL_COOLDOWN)
                            .unwrap_or(false);
                        *cl.last_refusal.lock().unwrap() = Some(msg.clone());
                        // NOT refused_at: this failure was local, against a
                        // connection already known dead. Re-stamping it here
                        // would slide the cooldown forward on every dial and
                        // the fresh handshake below would never come due.
                        if recent {
                            dbg_log!("dial: cached refusal served locally (no handshake): {msg}");
                            return Err(msg);
                        }
                    }
                    dbg_log!("dial: cached conn dead ({e:?}); redialing fresh");
                    *guard = None; // stale, or a refusal past cooldown; redial below
                }
            }
        }
        // A failure run past the horizon earns a fresh ENDPOINT before the
        // fresh handshake — see maybe_rebuild_endpoint.
        maybe_rebuild_endpoint(&cl).await;
        dbg_log!("dial: fresh handshake starting");
        let ep = cl.endpoint.lock().await.clone();
        let c = match ep.connect(cl.peer.clone(), ALPN).await {
            Ok(c) => c,
            Err(e) => {
                let refusal = connect_refusal_message(&e);
                let msg = refusal.clone().unwrap_or_else(|| format!("connect: {e:?}"));
                dbg_log!("dial: fresh handshake FAILED: {msg}");
                mark_dial_failed(&cl);
                if refusal.is_none() {
                    // The link is failing for a DIFFERENT reason now; a stale
                    // "server refused" must not outlive it (errForRet would
                    // replay it over any later stream error, and the device
                    // would keep its QR screen up over an outage).
                    *cl.last_refusal.lock().unwrap() = None;
                }
                return Err(msg);
            }
        };
        let pair = match c.open_bi().await {
            Ok(pair) => pair,
            Err(e) => {
                let refusal = refusal_message(&e);
                dbg_log!("dial: fresh handshake done, open_bi failed: {e:?}");
                mark_dial_failed(&cl);
                if let Some(m) = &refusal {
                    *cl.last_refusal.lock().unwrap() = Some(m.clone());
                    *cl.refused_at.lock().unwrap() = Some(Instant::now());
                }
                // Cache the connection even though it is dead: the next dial
                // call hits the cached branch above and fails fast on this
                // same connection instead of redialing into the same refusal.
                *guard = Some(c);
                return Err(refusal.unwrap_or_else(|| format!("open_bi: {e:?}")));
            }
        };
        dbg_log!("dial: fresh handshake + open_bi OK");
        // A handshake that got through retires the refusal: the peer's answer
        // has changed (an enrolment was approved), and a stale "not
        // allowlisted" would keep the device's QR screen up over a working
        // link. A refusal that merely outran the dial re-records itself on the
        // first read/write (record_stream_refusal_*).
        *cl.last_refusal.lock().unwrap() = None;
        *cl.refused_at.lock().unwrap() = None;
        // NOT fail_run_started: opening a stream is local-only, so a dial
        // "succeeds" against a connection the peer is already closing on
        // every refused cycle — clearing the failure-run clock here would
        // reset it each cycle and the rebuild horizon would never come due.
        // The run ends when stream I/O actually completes (mark_io_worked).
        *guard = Some(c);
        Ok::<(SendStream, RecvStream), String>(pair)
    };
    let dur = Duration::from_millis(if timeout_ms > 0 { timeout_ms as u64 } else { 30_000 });
    match rt().block_on(async { tokio::time::timeout(dur, fut).await }) {
        Ok(Ok((send, recv))) => {
            // Opening a stream is local-only (bounded by the peer's already
            // negotiated stream limit), so it can succeed even against a
            // connection the peer is already closing — pass the client
            // handle through so a first-read/write ApplicationClosed still
            // has somewhere to record itself (irohnet_stream_read/write).
            let sh = register_stream(send, recv, Some(cl.clone()));
            unsafe { *out_stream = sh };
            0
        }
        Ok(Err(msg)) => {
            put_str(err_out, err_cap, &msg);
            -1
        }
        Err(_) => {
            put_str(err_out, err_cap, "dial timeout");
            -1
        }
    }
}

/// The last peer-initiated close (application close, e.g. an allowlist
/// refusal) seen on this client — from a dial attempt or from the first
/// read/write on a stream that outran the peer's close. Returns 0 with the
/// reason written to `out` (formatted "server refused: <code> <reason>"), or
/// -1 if the client is unknown or has not seen one yet.
#[no_mangle]
pub extern "C" fn irohnet_client_last_refusal(h: u64, out: *mut c_char, cap: usize) -> i32 {
    let cl = match get_client(h) {
        Some(c) => c,
        None => return -1,
    };
    let guard = cl.last_refusal.lock().unwrap();
    match guard.as_ref() {
        Some(msg) => {
            put_str(out, cap, msg);
            0
        }
        None => -1,
    }
}

/// How many times this client has rebuilt its endpoint (the aged-failure
/// heal). Returns the count, or -1 for an unknown handle. What the
/// aged-refusal gate asserts on.
#[no_mangle]
pub extern "C" fn irohnet_client_rebuilds(h: u64) -> i64 {
    match get_client(h) {
        Some(c) => c.rebuilds.load(Ordering::SeqCst) as i64,
        None => -1,
    }
}

/// Close the client endpoint (and its cached connection).
#[no_mangle]
pub extern "C" fn irohnet_client_close(h: u64) {
    if let Some(Obj::Client(c)) = remove(h) {
        rt().block_on(async {
            c.endpoint.lock().await.close().await;
        });
    }
}

// ---- streams ---------------------------------------------------------------

/// Set the stream's deadlines (unix millis; 0 = none; pass -1 to leave one
/// side unchanged). Wakes any blocked read/write so it re-evaluates — a
/// deadline in the past interrupts a pending call with -2.
#[no_mangle]
pub extern "C" fn irohnet_stream_set_deadlines(h: u64, read_ms: i64, write_ms: i64) {
    if let Some(st) = get_stream(h) {
        if read_ms >= 0 {
            st.read_deadline_ms.store(read_ms, Ordering::SeqCst);
        }
        if write_ms >= 0 {
            st.write_deadline_ms.store(write_ms, Ordering::SeqCst);
        }
        let _ = st.epoch.send(());
    }
}

/// Read up to `cap` bytes. See the return-code protocol in the module header.
#[no_mangle]
pub extern "C" fn irohnet_stream_read(h: u64, buf: *mut u8, cap: usize) -> i64 {
    let st = match get_stream(h) {
        Some(s) => s,
        None => return RET_CLOSED,
    };
    let slice = unsafe { std::slice::from_raw_parts_mut(buf, cap) };
    rt().block_on(async move {
        let mut epoch_rx = st.epoch.subscribe();
        loop {
            if st.closed.load(Ordering::SeqCst) {
                return RET_CLOSED;
            }
            let dl = st.read_deadline_ms.load(Ordering::SeqCst);
            let now = now_ms();
            if dl > 0 && dl <= now {
                return RET_TIMEOUT;
            }
            let mut recv = tokio::select! {
                guard = st.recv.lock() => guard,
                _ = epoch_rx.changed() => continue,
            };
            tokio::select! {
                r = recv.read(slice) => {
                    return match r {
                        Ok(Some(n)) => {
                            mark_io_worked(&st);
                            n as i64
                        }
                        Ok(None) => RET_EOF,
                        Err(e) => {
                            record_stream_refusal_read(&st, &e);
                            RET_ERR
                        }
                    };
                }
                _ = epoch_rx.changed() => { drop(recv); continue; }
                _ = tokio::time::sleep(Duration::from_millis((dl - now) as u64)), if dl > 0 => {
                    return RET_TIMEOUT;
                }
            }
        }
    })
}

/// Write up to `len` bytes; returns how many were accepted (may be short —
/// the caller loops). Same return-code protocol.
#[no_mangle]
pub extern "C" fn irohnet_stream_write(h: u64, buf: *const u8, len: usize) -> i64 {
    let st = match get_stream(h) {
        Some(s) => s,
        None => return RET_CLOSED,
    };
    let slice = unsafe { std::slice::from_raw_parts(buf, len) };
    rt().block_on(async move {
        let mut epoch_rx = st.epoch.subscribe();
        loop {
            if st.closed.load(Ordering::SeqCst) {
                return RET_CLOSED;
            }
            let dl = st.write_deadline_ms.load(Ordering::SeqCst);
            let now = now_ms();
            if dl > 0 && dl <= now {
                return RET_TIMEOUT;
            }
            let mut send = tokio::select! {
                guard = st.send.lock() => guard,
                _ = epoch_rx.changed() => continue,
            };
            tokio::select! {
                r = send.write(slice) => {
                    return match r {
                        // NB: write success is NOT mark_io_worked — a QUIC
                        // write only buffers locally, so it "succeeds" on a
                        // dying connection just like open_bi does. Only a
                        // read proves the peer answered.
                        Ok(n) => n as i64,
                        Err(e) => {
                            record_stream_refusal_write(&st, &e);
                            RET_ERR
                        }
                    };
                }
                _ = epoch_rx.changed() => { drop(send); continue; }
                _ = tokio::time::sleep(Duration::from_millis((dl - now) as u64)), if dl > 0 => {
                    return RET_TIMEOUT;
                }
            }
        }
    })
}

/// Close the stream: wakes blocked ops (they return -3), finishes the send
/// side (graceful FIN — the peer reads EOF) and stops the receive side.
#[no_mangle]
pub extern "C" fn irohnet_stream_close(h: u64) {
    if let Some(Obj::Stream(st)) = remove(h) {
        st.closed.store(true, Ordering::SeqCst);
        let _ = st.epoch.send(());
        rt().block_on(async {
            let mut send = st.send.lock().await;
            let _ = send.finish();
            let mut recv = st.recv.lock().await;
            let _ = recv.stop(VarInt::from_u32(0));
        });
    }
}
