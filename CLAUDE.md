# Working in this fork

This is a fork of Exclave carrying MASQUE (Cloudflare WARP) as a first class
protocol, plus a smaller build.

**Read [MASQUE.md](MASQUE.md) before touching the packet path, the QUIC socket,
the netstack device, or anything MTU related.** Several of the constraints there
fail on a device rather than at compile time, and were expensive to find.

The core is a submodule at `library/core/deps/exclave-core`, substituted with a
filesystem replace in `library/core/go.mod`. Initialise it before building:

```
git submodule update --init library/core/deps/exclave-core
```
