# MASQUE in this fork

MASQUE (CONNECT-IP, RFC 9484) as Cloudflare WARP deploys it, over QUIC/HTTP3 and
over TCP+TLS/HTTP2. Written against
[usque](https://github.com/Diniboy1123/usque) (MIT), which documented
Cloudflare's departures from the RFC.

This file is the handover note. Read the traps section before changing the
packet path: most of them cost a build-and-test cycle on a device to find, and
none of them fail at compile time.

## Where things live

Three repositories, all on branch `masque`, except the app which is on
`dev-masque`.

| Repository | What it holds |
| --- | --- |
| `miron404/Exclave` | the app: profile, config builder, UI, CI |
| `miron404/exclave-core` | the outbound: `proxy/masque`, `infra/conf/v4/masque.go` |
| `miron404/connect-ip-go` | two fixes, described below |

The core is pinned as a submodule at `library/core/deps/exclave-core` and
substituted with a filesystem replace in `library/core/go.mod`:

```
replace github.com/exclavenetwork/exclave-core/v5 => ./deps/exclave-core
```

It has to be a *filesystem* replace. Go requires a module-path replacement to
declare the path it is replacing, which a fork at a different URL does not, so
`=> github.com/miron404/exclave-core/v5` is rejected. A filesystem replace has no
such requirement, which is what keeps the fork free of a rename across every
import in the tree. `connect-ip-go` is small enough that it owns its path
instead and is required normally.

`libexclavecore` is **not** forked. The replace above reaches it too.

## The app side

Wired the way any protocol is here; `git show 3be5bc44` (Snell) is the template.
`MasqueBean` holds the device material. Pasting a usque `config.json`, or a
`masque://` link (the same document, base64url), creates a profile.

A profile can also be made from scratch: "Cloudflare WARP" in the tools tab
registers a device and adds the profile. `fmt/warp/WarpEnrollment.kt` repeats
what usque's `register` command does, which is two calls to an undocumented
API:

| | |
| --- | --- |
| `POST /v0a4471/reg` | registers a device, with a throwaway WireGuard key because that is the only kind registration takes, and answers with an id and an access token |
| `PATCH /v0a4471/reg/{id}` | amends it with the P-256 key actually used, and answers with the endpoint, its public key and the addresses assigned to this device |

The awkward part is the key. Cloudflare is handed PKIX, which is what
`PublicKey.getEncoded()` already is, but the outbound and usque both read the
private key with Go's `x509.ParseECPrivateKey`, which wants RFC 5915. Android
hands out PKCS#8, and the structure nested inside it leaves the curve out, so
unwrapping it is not enough: `sec1PrivateKey` re-encodes the key with the curve
named. The result is byte for byte what `x509.MarshalECPrivateKey` produces,
which is what keeps an exported profile readable by usque.

The same file registers a WireGuard device, from
[bash-warp-generator](https://github.com/ImMALWARE/bash-warp-generator). It is
the same two calls, but that reference was written against an older API version
which wraps its answers in `result`, and there the key that matters goes with
the registration itself, since WireGuard is what the API takes by default. Each
flow is kept at the version its reference used rather than merged onto one:
the request bodies, the timestamp format and the answer shape all differ, and
none of it can be tested without registering a real device. The X25519 key comes
from BouncyCastle, which is already a dependency and, unlike the platform, has
carried X25519 for far longer than this app's minimum API level.

The API is reached through the tunnel when one is running, like every other
network access in the app, so a device can be registered from a network where
`api.cloudflareclient.com` is not reachable.

## The outbound

`proxy/masque` mirrors `proxy/wireguard`, which is the closest thing in the
tree: both carry IP packets on a gVisor stack. It reuses
`proxy/wireguard/netstack` rather than bringing its own, which keeps gvisor at
one version.

| File | |
| --- | --- |
| `masque.go` | pinned TLS, dialing over QUIC and over HTTP/2 |
| `tunnel.go` | the stack, the supervisor, the two packet pumps |
| `client.go` | `proxy.Outbound`: resolution and per-connection plumbing |
| `packet.go` | UDP reader and writer, copied from wireguard's |
| `packetconn.go` | unwrapping the socket the core dialer returns |

## Traps

**The socket has to reach quic-go intact.** quic-go decides whether it can set
the don't-fragment bit, and so whether to discover the path MTU, from the
methods the socket carries:

```go
if !c.config.DisablePathMTUDiscovery && c.conn.capabilities().DF {
    c.mtuDiscoverer.Start(now)
}
```

`singbridge`'s counting wrapper deliberately hides `syscall.Conn`, so going
through it leaves the packet size at the initial 1280 for the life of the
connection and a full size tunnel packet never fits a datagram. Wrapping it
back up to count bytes does not work either: the out of band read path goes
through `golang.org/x/net/ipv4` straight to the socket, which also asserts it to
`net.Conn` and panics on anything that only carries `net.PacketConn`. The socket
is passed on as it comes and the counters travel beside it. See
`packetconn.go` and `TestUnwrappedSocketIsUsableByQUIC`.

**The reading pump must never write to the device.** The stack hands outgoing
packets over an unbuffered channel that only that pump drains, so a write which
makes the stack answer waits on the pump itself and the whole outbound direction
stops. ICMP answers go through a queue drained by a goroutine of its own. Note
that mihomo writes them from the reading loop and gets away with it because
sing-wireguard's device buffers 256 packets; this one buffers none.

**Closing the device must not close the channel the stack delivers on.** A
packet picked up between the two panics the process with "send on closed
channel", which a url test over a group landing on this outbound hits readily.
`netstack/tun.go` closes a separate channel and both sides give up on it.

**The keepalive period is halved unless the idle timeout says otherwise.**
quic-go pings at `min(KeepAlivePeriod, MaxIdleTimeout/2)`, and an unset
`MaxIdleTimeout` is 30 seconds, so the 30 second default period used to mean a
ping every 15 seconds. On a phone that is a radio wakeup every 15 seconds for as
long as the tunnel is up, and raising the period in the profile did nothing on
its own. `quicConfig` now derives the idle timeout from the period instead, which
leaves one unanswered ping of grace. The endpoint's own advertised idle timeout
is still taken into account and the smaller of the two wins, so the period is a
ceiling rather than a promise. `TestKeepalivePeriodIsNotHalvedByTheIdleTimeout`
holds the relationship.

What the endpoint advertises was measured in October 2026 by the core's
`MASQUE probe` workflow, which registers a throwaway device and records the
transport parameters a consumer endpoint sends: a `max_idle_timeout` of 56
seconds, and it holds to it. So an idle tunnel pings every 28 seconds at most,
whatever the profile asks for; a keepalive period above 28 changes nothing.
The only way to make an idle QUIC tunnel quieter than that is to not keep the
session at all. Run the workflow again if Cloudflare's numbers are in doubt.

**Below 1280 there is no IPv6.** gVisor refuses a link smaller than that
outright, so the tunnel drops IPv6 addresses when it is resized below it, and
says so. A profile MTU under 1280 costs IPv6 the same way.

**A migrated connection keeps every socket it has used.** (Moot against
Cloudflare for now, see below, but true of any endpoint that allows it.) quic-go leaves the
connection registered on each transport it ran on, and closing a transport, or
the socket under it, which makes its reader fail and close it, destroys every
connection registered there. So the old socket is held until the session ends,
and a session moves at most `maxMigrations` times before a network change
redials it instead.

## When the network changes

Android reports a change of default network or of its addresses through
`InterfaceUpdate`, whenever "interrupt reused connections" is on, which is the
default. The tunnel used to be dropped whole, stack included, so every
connection in it was reset. Now the stack stays, and with it the addresses and
every flow on them:

- over QUIC, the connection is to be moved onto a socket opened on the new
  network (`AddPath`, `Probe`, `Switch`). No handshake and no new CONNECT-IP
  request, just a few PATH_CHALLENGE frames, and quic-go restarts congestion
  control and MTU discovery for the new path. **Cloudflare's endpoints do not
  allow it**: they send `disable_active_migration`, measured by the probe, and
  quic-go's `AddPath` refuses outright when the peer has. Against them every
  network change takes the next branch, which is what the device tests in
  September actually exercised;
- if that cannot be done (Cloudflare, HTTP/2, a chained outbound, a refused or
  unanswered probe, too many moves), the session is dropped and redialed the
  usual lazy way, on the first packet something wants to send, so an idle
  tunnel spends nothing on it;
- only a tunnel whose MTU was lowered for the old path is rebuilt, because a
  stack's link MTU is fixed when it is made.

Whether an inner flow survives a redial depends on Cloudflare keeping the
device's egress mapping across sessions, which could not be checked here.
Switching networks on the device did not break anything, which suggests it
does, since the redial is what ran there.

## How the MTU works

Three values, and only the outermost is discovered:

| | | |
| --- | --- | --- |
| tunnel MTU | largest IP packet inside | the profile, fixed when the tunnel is built |
| QUIC packet | UDP datagram going out | quic-go, starts at 1280, climbs to at most 1452 |
| datagram payload | what actually fits | packet size less about 37 bytes |

A tunnel MTU of 1280 does not grow. What grows is the room around it, until
1281 bytes fit. Until then packets are refused, which is expected, and the ICMP
answer keeps TCP flows moving meanwhile.

If the search settles without ever making room, the tunnel is rebuilt at what
the path does carry. That is decided on evidence rather than a clock: the limit
reported with each refusal climbs while discovery works, so the wait restarts
whenever it improves and expires only once it has held still. A connection that
cannot discover anything is resized at once. A learned MTU only ever goes down,
so it is reset when the network changes.

`initialPacketSize` in the profile pins the QUIC packet size and turns discovery
off, which is usque's meaning of the field. quic-go clamps it to 1452, so any
value above that is the same as 1452. Leave it at 0 unless you want to skip the
climb on a network you know: pinned, there is nothing to fall back to.

## Each transport has its own settings

`keepalive_period` and `initial_packet_size` reach quic-go and nothing else;
`http2_ping_period` reaches `http2.Transport.ReadIdleTimeout` and
`tcp_keepalive_period` the HTTP/2 connection's socket, and nothing else.
The profile editor shows one set or the other, following the selected
transport, so that none of them is a knob that quietly does nothing.

Over QUIC a dead path is found by the keepalive, because the idle timeout then
expires and fails the connection. HTTP/2 has no such clock: the tunnel request
stays open for the life of the session, so a TCP path that dies silently would
park both pumps on a socket that will never deliver again, with no error to end
them and nothing to make the supervisor redial. Two things on the socket cover
that, both set in `tcp.go`:

- `TCP_USER_TIMEOUT` of 30 seconds: data that goes that long unacknowledged
  gives the connection up. A path that died while idle is found as soon as
  something is sent on it, and it costs nothing while nothing is, since it needs
  no timer of its own;
- a TCP keepalive, four minutes by default, which keeps the carrier's NAT
  mapping alive so that incoming data, a notification say, still arrives after
  a long quiet spell. 0 in the profile turns it off.

**The core dialer turns on Go's default keepalive otherwise**: a probe after 15
seconds idle and every 15 seconds after, on every TCP socket it dials. Before
`tcp.go` that was what kept the HTTP/2 mode alive across a night of idling, at
four radio wakeups a minute, more than QUIC's pings. Every other TCP outbound
in the app still carries it.

The liveness ping stays off by default. With the user timeout in place it is
only worth turning on when the tunnel runs through another proxy, whose
connection the socket options do not reach, and which can drop the session
without telling either end.

## How the HTTP/2 mode spends CPU

The HTTP/2 client writes a DATA frame, and flushes it to the connection, for
every read it makes from the request body. connect-ip-go used to feed it
through an `io.Pipe`, one packet per read, so every packet cost its own frame,
TLS record and write system call. It now queues capsules while the transport
is busy and hands them over together (`h2_body.go`), with nothing waiting on a
timer. Measured through a real endpoint with the probe workflow, client CPU per
gigabyte: upload 19.2s to 6.9s, download 32s to 23.7s, against QUIC's 21s.
Download stays dearer than upload because the inner TCP's acknowledgements go
out one at a time as data trickles in, and each is still a write.

## Updating from upstream

**Exclave.** An ordinary merge. The app diff is confined to the files any
protocol touches, plus `version.properties` (the application id, so this
installs beside the original), `buildSrc/Helpers.kt` and the two workflows.

**exclave-core.** Merge upstream into the fork's `masque` branch. Only two lines
are in shared files, so conflicts are unlikely: the entry in the outbound loader
map in `infra/conf/v4/v2ray.go` and the import in `main/distro/all/all.go`.
Everything else is `proxy/masque`, `infra/conf/v4/masque.go`, and the netstack
close fix. Then move the submodule and `go mod tidy` in `library/core`.

What does conflict, every time, is `go.sum` on both sides, and
`library/core/go.mod` in the app. Take upstream's dependency versions, keep the
two lines MASQUE adds (`connect-ip-go` and `httpsfv`), and let `go mod tidy`
settle the rest. Resolving `library/core/go.mod` by taking one side wholesale
drops the filesystem replace at the end of the file, and `tidy` then quietly
resolves against the upstream core instead of the submodule; put it back before
running tidy.

Watch the build tags too. Since Go 1.27, `golang.org/x/net/http2` is a thin
wrapper over net/http's own HTTP/2 client unless the build sets
`http2legacy`, which keeps x/net's original one. Upstream used the tag for a
while and dropped it in October 2026. The HTTP/2 mode runs on whichever one
the build picks and works on both: the endpoint takes a plain CONNECT, not the
extended one net/http refuses, and `ReadIdleTimeout` reaches net/http as
`SendPingTimeout`. The core's `masque.yml` workflow tests with the app's tags,
so keep the two in step when upstream changes them.

Watch for a quic-go bump. 0.60 to 0.61 replaced `http3.ParseCapsule` with a
stateful `http3.CapsuleParser`, which is why connect-ip-go is forked at all, and
the datagram and path MTU behaviour described above lives there too.

**usque.** Its `api/masque.go` and `api/tunnel.go` are what this was derived
from, so a change there is worth reading, but do not depend on the module: it
brings cobra, water, netlink and a newer gvisor that conflicts with the one this
core pins. Two of the bugs fixed here exist upstream: `MaintainTunnel` writes
the ICMP answer from its reading goroutine, and the wait for the peer's HTTP/3
settings has no deadline of its own.

**connect-ip-go.** Two commits on top of upstream, both worth sending back:

- the quic-go 0.61 capsule API;
- the ICMP "packet too big" answer carried a constant 1280 instead of the
  peer's actual datagram limit, so a sender already at 1280 had nothing to
  shrink to and every full size packet was dropped and retransmitted unchanged
  forever, while small ones went through.

Keep the module path rename in its own commit so merges stay clean.

## Building

`git submodule update --init library/core/deps/exclave-core` first; CI does this
explicitly rather than recursively, since the naive submodule is large.

A push to `dev-masque` builds one ABI and one flavour, which is a couple of
minutes; a manual run builds everything, for a release. The `legacy` flavour is
gone, and with it the KSP flake that only ever hit its tasks. Release builds are
signed with the debug identity when no keystore is configured, so re-sign before
publishing.

The Go side can be checked without an Android toolchain:

```
cd library/core
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -tags with_clash \
    github.com/exclavenetwork/exclave-core/v5/main/distro/all
```

The Kotlin side cannot; CI is the first thing that compiles it.

## Left undone

- The two connect-ip-go fixes are not upstream yet, nor is the usque report.
- A url test starts an instance per proxy, and each builds its own tunnel to
  Cloudflare. That is how url test works, not something this outbound decides,
  but it makes the measurements pessimistic.
- Profile strings are English only.
