(() => {
  "use strict";

  const POLL_MS = 1000;
  const MAX_POINTS = 120;

  const el = (id) => document.getElementById(id);
  const statusPill = el("statusPill");
  const statusText = el("statusText");
  const hostLabel = el("hostLabel");
  const clockEl = el("clock");
  const rxValueEl = el("rxValue");
  const rxUnitEl = el("rxUnit");
  const rxPktEl = el("rxPkt");
  const txValueEl = el("txValue");
  const txUnitEl = el("txUnit");
  const txPktEl = el("txPkt");
  const totalValueEl = el("totalValue");
  const totalUnitEl = el("totalUnit");
  const ifaceTableBody = el("ifaceTableBody");
  const ifaceCountEl = el("ifaceCount");
  const unsupportedNote = el("unsupportedNote");
  const pollCountEl = el("pollCount");
  const canvas = el("scope");
  const ctx = canvas ? canvas.getContext("2d") : null;

  // Rolling buffers for the waveform, in {t, rxRate, txRate} form.
  let history = [];
  let pollCount = 0;
  let cumulativeBytes = 0;
  let initialTotalBytes = null;
  let lastPollTime = 0;
  let isPolling = false;

  // ---------------- Formatting helpers ----------------

  function splitBytes(value) {
    if (!Number.isFinite(value) || value < 0) {
      return { value: 0, unit: "B" };
    }
    const units = ["B", "KB", "MB", "GB", "TB"];
    let v = value;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i++;
    }
    return { value: v, unit: units[i] };
  }

  function setRate(valueEl, unitEl, bytesPerSec) {
    if (!valueEl || !unitEl) return;
    const { value, unit } = splitBytes(bytesPerSec);
    valueEl.textContent = value.toFixed(value >= 100 ? 0 : 1);
    unitEl.textContent = unit + "/s";
  }

  function setTotal(valueEl, unitEl, bytes) {
    if (!valueEl || !unitEl) return;
    const { value, unit } = splitBytes(bytes);
    valueEl.textContent = value.toFixed(value >= 100 ? 0 : 1);
    unitEl.textContent = unit;
  }

  function fmtRateShort(bytesPerSec) {
    const { value, unit } = splitBytes(bytesPerSec);
    return `${value.toFixed(1)} ${unit}/s`;
  }

  function fmtBytesShort(bytes) {
    const { value, unit } = splitBytes(bytes);
    return `${value.toFixed(1)} ${unit}`;
  }

  // ---------------- Status ----------------

  function setStatus(mode) {
    if (!statusPill || !statusText) return;
    statusPill.classList.remove("live", "down");
    if (mode === "live") {
      statusPill.classList.add("live");
      statusText.textContent = "LIVE";
    } else if (mode === "down") {
      statusPill.classList.add("down");
      statusText.textContent = "OFFLINE";
    } else if (mode === "unsupported") {
      statusText.textContent = "UNSUPPORTED OS";
    } else {
      statusText.textContent = "CONNECTING";
    }
  }

  function tickClock() {
    if (clockEl) {
      clockEl.textContent = new Date().toLocaleTimeString([], { hour12: false });
    }
  }

  // ---------------- Table ----------------

  function renderTable(interfaces) {
    if (!ifaceTableBody || !ifaceCountEl) return;
    if (!interfaces || interfaces.length === 0) {
      ifaceTableBody.innerHTML =
        '<tr class="empty-row"><td colspan="8">No interface data available.</td></tr>';
      ifaceCountEl.textContent = "0 interfaces";
      return;
    }
    ifaceCountEl.textContent = `${interfaces.length} interface${interfaces.length === 1 ? "" : "s"}`;
    const rows = interfaces.map((it) => {
      const d = it.data;
      const errTotal = (d.rxErr || 0) + (d.txErr || 0);
      const errClass = errTotal > 0 ? "num err-nonzero" : "num";
      return `<tr>
        <td class="iface-name"><span class="dot" aria-hidden="true"></span>${escapeHtml(it.name)}</td>
        <td class="num">${fmtRateShort(d.rxRate || 0)}</td>
        <td class="num">${fmtRateShort(d.txRate || 0)}</td>
        <td class="num">${Number(d.rxPktRate || 0).toFixed(1)}</td>
        <td class="num">${Number(d.txPktRate || 0).toFixed(1)}</td>
        <td class="num">${fmtBytesShort(d.rxTotal || 0)}</td>
        <td class="num">${fmtBytesShort(d.txTotal || 0)}</td>
        <td class="${errClass}">${errTotal}</td>
      </tr>`;
    });
    ifaceTableBody.innerHTML = rows.join("");
  }

  function escapeHtml(s) {
    if (!s) return "";
    return String(s).replace(/[&<>"']/g, (c) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    }[c]));
  }

  // ---------------- Oscilloscope waveform ----------------

  function resizeCanvas() {
    if (!canvas || !ctx) return;
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return;

    const targetW = Math.max(1, Math.floor(rect.width * dpr));
    const targetH = Math.max(1, Math.floor(rect.height * dpr));
    if (canvas.width !== targetW || canvas.height !== targetH) {
      canvas.width = targetW;
      canvas.height = targetH;
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function drawScope() {
    if (!canvas || !ctx) return;
    const rect = canvas.getBoundingClientRect();
    const w = rect.width;
    const h = rect.height;
    if (w === 0 || h === 0) return;

    ctx.clearRect(0, 0, w, h);

    if (history.length < 2) return;

    const maxVal = Math.max(
      1024, // floor so the trace isn't jumpy near zero
      ...history.map((p) => Math.max(p.rxRate || 0, p.txRate || 0))
    ) * 1.15;

    const stepX = w / (MAX_POINTS - 1);
    const startIdx = MAX_POINTS - history.length;

    const drawLine = (key, color, glow) => {
      ctx.beginPath();
      history.forEach((p, i) => {
        const x = (startIdx + i) * stepX;
        const val = Math.max(0, p[key] || 0);
        const y = h - (val / maxVal) * (h - 12) - 6;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.strokeStyle = color;
      ctx.lineWidth = 1.8;
      ctx.shadowColor = glow;
      ctx.shadowBlur = 6;
      ctx.stroke();
      ctx.shadowBlur = 0;
    };

    // Baseline
    ctx.beginPath();
    ctx.moveTo(0, h - 6);
    ctx.lineTo(w, h - 6);
    ctx.strokeStyle = "rgba(255,255,255,0.06)";
    ctx.lineWidth = 1;
    ctx.stroke();

    drawLine("txRate", "#F5A623", "rgba(245,166,35,0.55)");
    drawLine("rxRate", "#4FD1C5", "rgba(79,209,197,0.55)");
  }

  // ---------------- Polling ----------------

  async function pollStats() {
    try {
      const res = await fetch("/api/stats", { cache: "no-store" });
      if (!res.ok) throw new Error("bad status " + res.status);
      const data = await res.json();
      pollCount++;
      if (pollCountEl) pollCountEl.textContent = String(pollCount);

      if (data.os && hostLabel) {
        hostLabel.textContent = `${data.os} live telemetry`;
      }

      if (!data.supported) {
        setStatus("unsupported");
        if (unsupportedNote) unsupportedNote.hidden = false;
        renderTable([]);
        return;
      }
      if (unsupportedNote) unsupportedNote.hidden = true;
      setStatus("live");

      const totals = data.totals || {};
      const rxRate = totals.rxRate || 0;
      const txRate = totals.txRate || 0;
      const rxTotal = totals.rxTotal || 0;
      const txTotal = totals.txTotal || 0;

      setRate(rxValueEl, rxUnitEl, rxRate);
      setRate(txValueEl, txUnitEl, txRate);
      if (rxPktEl) rxPktEl.textContent = Number(totals.rxPktRate || 0).toFixed(1);
      if (txPktEl) txPktEl.textContent = Number(totals.txPktRate || 0).toFixed(1);

      // Session total calculation
      const currentHwTotal = rxTotal + txTotal;
      if (initialTotalBytes === null && currentHwTotal > 0) {
        initialTotalBytes = currentHwTotal;
      }

      let sessionBytes = 0;
      if (initialTotalBytes !== null && currentHwTotal >= initialTotalBytes) {
        sessionBytes = currentHwTotal - initialTotalBytes;
      } else {
        const now = Date.now();
        const elapsed = lastPollTime ? Math.max(0.1, (now - lastPollTime) / 1000) : 1;
        cumulativeBytes += (rxRate + txRate) * elapsed;
        sessionBytes = cumulativeBytes;
      }
      lastPollTime = Date.now();
      setTotal(totalValueEl, totalUnitEl, sessionBytes);

      history.push({ t: data.timestamp || Date.now(), rxRate, txRate });
      if (history.length > MAX_POINTS) history.shift();
      drawScope();

      renderTable(data.interfaces);
    } catch (err) {
      setStatus("down");
    }
  }

  async function prefillHistory() {
    try {
      const res = await fetch("/api/history?iface=_total", { cache: "no-store" });
      if (!res.ok) return;
      const data = await res.json();
      if (!data.supported || !Array.isArray(data.samples)) return;
      history = data.samples.map((s) => ({
        t: s.t,
        rxRate: Number(s.rxRate) || 0,
        txRate: Number(s.txRate) || 0
      }));
      if (history.length > MAX_POINTS) history = history.slice(-MAX_POINTS);
      drawScope();
    } catch (err) {
      // Non-fatal: live polling will populate the chart regardless.
    }
  }

  // ---------------- Init ----------------

  async function pollLoop() {
    if (!isPolling) {
      isPolling = true;
      try {
        await pollStats();
      } finally {
        isPolling = false;
      }
    }
    setTimeout(pollLoop, POLL_MS);
  }

  function init() {
    resizeCanvas();
    window.addEventListener("resize", () => {
      resizeCanvas();
      drawScope();
    });

    tickClock();
    setInterval(tickClock, 1000);
    setStatus("connecting");

    prefillHistory().then(() => {
      pollLoop();
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
