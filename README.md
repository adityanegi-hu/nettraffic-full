# Network Traffic Monitor — Full Stack (Java backend + web frontend)

A live network traffic dashboard: a Java backend samples real interface
counters once a second and serves them over a small JSON API; a browser
frontend polls that API and renders live readouts, a scrolling dual-trace
"oscilloscope" waveform (RX vs TX), and a per-interface table.

Zero external dependencies on either side — the backend uses only the JDK
(`com.sun.net.httpserver`), and the frontend is plain HTML/CSS/JS (one
Google Fonts link for type, no frameworks, no build step).

```
nettraffic-full/
├── backend/
│   └── NetTrafficServer.java   ← Java HTTP server + sampler
├── public/
│   ├── index.html              ← dashboard markup
│   ├── style.css                ← design system (dark instrument-panel theme)
│   └── app.js                  ← polling, formatting, canvas waveform
└── README.md
```

## How it works

**Backend** (`backend/NetTrafficServer.java`)
- A background thread reads `/proc/net/dev` every second (the same kernel
  counters `ifconfig`/`netstat -i` use — no root or native packet-capture
  libraries required) and computes RX/TX byte and packet rates as the delta
  between samples.
- Keeps the last 120 samples (2 minutes) per interface and for network-wide
  totals in memory, so a browser that just connected gets a pre-filled chart.
- Loopback (`lo`) is excluded from the "totals" figure — it never leaves the
  machine — but it still shows up in the per-interface table.
- Serves a small JSON REST API:
  - `GET /api/stats` — latest snapshot for every interface + totals
  - `GET /api/history?iface=eth0` — rolling history for one interface
    (omit `iface`, or pass `iface=_total`, for network-wide totals)
- Serves the static frontend from `public/` on `/`.
- On non-Linux hosts (no `/proc/net/dev`), the API reports
  `"supported": false` and the frontend shows a message instead of throwing
  errors.

**Frontend** (`public/`)
- Polls `GET /api/stats` once a second and updates: two big instrument-style
  readouts (RX in cyan, TX in amber — direction encoded by color throughout
  the UI), a session-total counter, the interface table, and the waveform.
- The waveform is a hand-drawn HTML canvas scrolling line chart (no chart
  library) — two traces scaled to the recent max so both stay readable.
- A status pill in the header reflects connection state: `CONNECTING` on
  load, `LIVE` (pulsing) once data is flowing, `OFFLINE` if a poll fails,
  or a "no `/proc/net/dev`" message on unsupported platforms.
- Fully responsive down to a phone-width viewport; keyboard focus is
  visible and the live-status pulse respects `prefers-reduced-motion`.

## Run it

Requires a JDK (17+ recommended; the server uses modern `switch`/text-block
syntax). Run from the project root so the relative `public` path resolves:

```bash
cd nettraffic-full
javac backend/NetTrafficServer.java
java backend/NetTrafficServer 8080
```

Or, with a modern JDK, skip the separate compile step:

```bash
cd nettraffic-full
java backend/NetTrafficServer.java 8080
```

Then open **http://localhost:8080** in a browser. The port argument is
optional (defaults to `8080`).

Stop the server with `Ctrl+C`.

## Extending it

- **Packet-level detail** (source/destination IP, ports, protocol) needs
  raw-socket or libpcap access — e.g. via `pcap4j` — which typically
  requires elevated privileges and native libraries. This build
  intentionally stays dependency-free and reports interface-level
  throughput rather than individual packet contents.
- The history buffer is in memory only (2 minutes, capped at 120 samples
  per interface) and resets on server restart; swap in a file or database
  sink in `TrafficModel.applySample` if you want it to persist.
- Multiple browser tabs can be open at once — the backend samples once
  centrally and every client reads from the same in-memory snapshot, so
  polling cost doesn't multiply with the number of viewers.
