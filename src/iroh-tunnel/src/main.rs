//! iroh-tunnel — tunnel a local TCP service (the Wata homeserver, or a client's
//! Matrix HTTP client) over an iroh QUIC connection.
//!
//! Two modes, one per side of the tunnel:
//!
//!   listen  --to 127.0.0.1:8008
//!       Runs next to the always-on homeserver. Prints a stable NodeID, then
//!       accepts iroh connections and pipes each to the local homeserver.
//!
//!   connect --node <NodeID> --local 127.0.0.1:8009
//!       Runs next to a client. Listens on a local TCP port; every accepted
//!       connection is piped over a fresh iroh connection to <NodeID>. Point the
//!       client's homeserver URL at 127.0.0.1:8009.
//!
//! The NodeID is resolved to a live network address via the N0 preset's pkarr +
//! DNS discovery, so only the (stable) NodeID needs to be provisioned into a
//! client — no IPs, no dynamic DNS.
//!
//! Spike-quality: one iroh connection per TCP connection (simple and correct;
//! could be optimized to multiplex streams over a shared connection later).

use std::path::PathBuf;

use clap::{Parser, Subcommand};
use iroh::{Endpoint, EndpointId, SecretKey, endpoint::presets};
use n0_error::{Result, StdResultExt};
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, TcpStream};
use tracing::{info, warn};

/// ALPN identifying the Wata tunnel protocol. Both sides must match.
const ALPN: &[u8] = b"wata/tunnel/0";

#[derive(Parser)]
#[command(about = "Tunnel the Wata Matrix API over iroh (NAT traversal, no port forwarding).")]
struct Cli {
    #[command(subcommand)]
    mode: Mode,
}

#[derive(Subcommand)]
enum Mode {
    /// Server side: accept iroh connections, pipe each to a local TCP service.
    Listen {
        /// Local homeserver address to forward to.
        #[clap(long, default_value = "127.0.0.1:8008")]
        to: String,
        /// File holding the persisted secret key (created if missing). Keeping
        /// this stable keeps the NodeID — and thus what clients provision —
        /// stable across restarts.
        #[clap(long, default_value = "iroh-tunnel.key")]
        secret_key_file: PathBuf,
    },
    /// Client side: listen on a local TCP port, pipe each connection to a NodeID.
    Connect {
        /// The homeserver box's NodeID (from `listen`'s startup output).
        #[clap(long)]
        node: EndpointId,
        /// Local address to listen on; point the client's homeserver URL here.
        #[clap(long, default_value = "127.0.0.1:8009")]
        local: String,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt::init();
    match Cli::parse().mode {
        Mode::Listen { to, secret_key_file } => run_listen(to, secret_key_file).await,
        Mode::Connect { node, local } => run_connect(node, local).await,
    }
}

/// Load a persisted secret key, or generate and persist a fresh one.
fn load_or_create_secret_key(path: &PathBuf) -> Result<SecretKey> {
    if path.exists() {
        let hex = std::fs::read_to_string(path).anyerr()?;
        let bytes = data_encoding::HEXLOWER
            .decode(hex.trim().as_bytes())
            .anyerr()?;
        let arr: [u8; 32] = bytes.as_slice().try_into().anyerr()?;
        Ok(SecretKey::from_bytes(&arr))
    } else {
        let key = SecretKey::generate();
        std::fs::write(path, data_encoding::HEXLOWER.encode(&key.to_bytes())).anyerr()?;
        Ok(key)
    }
}

async fn run_listen(to: String, secret_key_file: PathBuf) -> Result<()> {
    let secret_key = load_or_create_secret_key(&secret_key_file)?;
    let endpoint = Endpoint::builder(presets::N0)
        .secret_key(secret_key)
        .alpns(vec![ALPN.to_vec()])
        .bind()
        .await
        .anyerr()?;

    endpoint.online().await;
    println!("iroh-tunnel listening. Provision this NodeID into clients:");
    println!("\n    {}\n", endpoint.id());
    println!("Forwarding accepted connections to {to}");

    while let Some(incoming) = endpoint.accept().await {
        let to = to.clone();
        tokio::spawn(async move {
            if let Err(err) = handle_incoming(incoming, &to).await {
                warn!("incoming connection failed: {err:#}");
            }
        });
    }
    Ok(())
}

/// Accept one iroh connection, open the local TCP connection, and pump bytes
/// both ways until either side closes.
async fn handle_incoming(incoming: iroh::endpoint::Incoming, to: &str) -> Result<()> {
    let conn = incoming.accept().anyerr()?.await.anyerr()?;
    let remote = conn.remote_id();
    let (send, recv) = conn.accept_bi().await.anyerr()?;
    info!("tunnel open from {remote} -> {to}");

    let tcp = TcpStream::connect(to).await.anyerr()?;
    pump(tcp, send, recv).await
}

async fn run_connect(node: EndpointId, local: String) -> Result<()> {
    let endpoint = Endpoint::builder(presets::N0).bind().await.anyerr()?;
    endpoint.online().await;

    let listener = TcpListener::bind(&local).await.anyerr()?;
    println!("iroh-tunnel listening on {local} -> node {node}");
    println!("Point the client's homeserver URL at http://{local}");

    loop {
        let (tcp, peer) = listener.accept().await.anyerr()?;
        let endpoint = endpoint.clone();
        tokio::spawn(async move {
            info!("local connection from {peer} -> {node}");
            if let Err(err) = handle_outgoing(endpoint, node, tcp).await {
                warn!("tunnel to {node} failed: {err:#}");
            }
        });
    }
}

/// Dial the NodeID (address resolved via discovery), open a bidi stream, and
/// pump bytes between it and the local TCP connection.
async fn handle_outgoing(endpoint: Endpoint, node: EndpointId, tcp: TcpStream) -> Result<()> {
    let conn = endpoint.connect(node, ALPN).await.anyerr()?;
    let (send, recv) = conn.open_bi().await.anyerr()?;
    pump(tcp, send, recv).await
}

/// Bidirectionally copy between a TCP stream and an iroh send/recv stream pair.
async fn pump(
    tcp: TcpStream,
    mut iroh_send: iroh::endpoint::SendStream,
    mut iroh_recv: iroh::endpoint::RecvStream,
) -> Result<()> {
    let (mut tcp_read, mut tcp_write) = tcp.into_split();

    let up = async move {
        tokio::io::copy(&mut tcp_read, &mut iroh_send).await?;
        iroh_send.finish().anyerr()?;
        Ok::<(), n0_error::AnyError>(())
    };
    let down = async move {
        tokio::io::copy(&mut iroh_recv, &mut tcp_write).await?;
        tcp_write.shutdown().await?;
        Ok::<(), n0_error::AnyError>(())
    };

    let (up, down) = tokio::join!(up, down);
    up?;
    down?;
    Ok(())
}
