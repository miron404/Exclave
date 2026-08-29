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
`MasqueBean` holds the device material, which is configuration only: enrolling a
device is out of scope, values come from an existing usque `config.json`.
Pasting that file, or a `masque://` link (the same document, base64url), creates
a profile.

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

**Below 1280 there is no IPv6.** gVisor refuses a link smaller than that
outright, so the tunnel drops IPv6 addresses when it is resized below it, and
says so. A profile MTU under 1280 costs IPv6 the same way.

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

## Updating from upstream

**Exclave.** An ordinary merge. The app diff is confined to the files any
protocol touches, plus `version.properties` (the application id, so this
installs beside the original), `buildSrc/Helpers.kt` and the two workflows.

**exclave-core.** Merge upstream into the fork's `masque` branch. Only two lines
are in shared files, so conflicts are unlikely: the entry in the outbound loader
map in `infra/conf/v4/v2ray.go` and the import in `main/distro/all/all.go`.
Everything else is `proxy/masque`, `infra/conf/v4/masque.go`, and the netstack
close fix. Then move the submodule and `go mod tidy` in `library/core`.

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
