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

use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use iroh::endpoint::{presets, Connection, RecvStream, SendStream, VarInt};
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
}

struct ClientState {
    endpoint: Endpoint,
    peer: EndpointAddr,
    conn: tokio::sync::Mutex<Option<Connection>>,
}

struct StreamState {
    send: tokio::sync::Mutex<SendStream>,
    recv: tokio::sync::Mutex<RecvStream>,
    read_deadline_ms: AtomicI64,
    write_deadline_ms: AtomicI64,
    closed: AtomicBool,
    /// bumped on close and on any deadline change; blocked ops re-evaluate.
    epoch: watch::Sender<()>,
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

fn register_stream(send: SendStream, recv: RecvStream) -> u64 {
    let (tx, _rx) = watch::channel(());
    register(Obj::Stream(Arc::new(StreamState {
        send: tokio::sync::Mutex::new(send),
        recv: tokio::sync::Mutex::new(recv),
        read_deadline_ms: AtomicI64::new(0),
        write_deadline_ms: AtomicI64::new(0),
        closed: AtomicBool::new(false),
        epoch: tx,
    })))
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

    let (tx, rx) = mpsc::channel::<AcceptedStream>(16);
    let ep = endpoint.clone();
    rt().spawn(async move {
        while let Some(incoming) = ep.accept().await {
            let tx = tx.clone();
            let allow = allow.clone();
            tokio::spawn(async move {
                let conn = match incoming.accept() {
                    Ok(accepting) => match accepting.await {
                        Ok(c) => c,
                        Err(_) => return,
                    },
                    Err(_) => return,
                };
                let remote = conn.remote_id();
                if !allow_all && !allow.contains(&remote) {
                    // the allowlist gate: refused at accept, before any stream.
                    conn.close(VarInt::from_u32(401), b"not allowlisted");
                    return;
                }
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
                                return;
                            }
                        }
                        Err(_) => return, // connection gone
                    }
                }
            });
        }
    });

    let h = register(Obj::Server(Arc::new(ServerState {
        endpoint,
        rx: tokio::sync::Mutex::new(rx),
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
            let sh = register_stream(acc.send, acc.recv);
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
                endpoint,
                peer,
                conn: tokio::sync::Mutex::new(None),
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
                Err(_) => *guard = None, // stale connection; redial below
            }
        }
        let c = cl
            .endpoint
            .connect(cl.peer.clone(), ALPN)
            .await
            .map_err(|e| format!("connect: {e:?}"))?;
        let pair = c.open_bi().await.map_err(|e| format!("open_bi: {e:?}"))?;
        *guard = Some(c);
        Ok::<(SendStream, RecvStream), String>(pair)
    };
    let dur = Duration::from_millis(if timeout_ms > 0 { timeout_ms as u64 } else { 30_000 });
    match rt().block_on(async { tokio::time::timeout(dur, fut).await }) {
        Ok(Ok((send, recv))) => {
            let sh = register_stream(send, recv);
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

/// Close the client endpoint (and its cached connection).
#[no_mangle]
pub extern "C" fn irohnet_client_close(h: u64) {
    if let Some(Obj::Client(c)) = remove(h) {
        rt().block_on(async {
            c.endpoint.close().await;
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
                        Ok(Some(n)) => n as i64,
                        Ok(None) => RET_EOF,
                        Err(_) => RET_ERR,
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
                        Ok(n) => n as i64,
                        Err(_) => RET_ERR,
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
