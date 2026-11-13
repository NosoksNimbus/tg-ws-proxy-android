"""
Android entry point for tg-ws-proxy (called from Kotlin through Chaquopy).

Mirrors what utils/tray_common.py does on desktop, but without any of the
desktop-only dependencies (tray, tkinter, psutil, pyperclip, Pillow).
"""
from __future__ import annotations

import asyncio
import collections
import json
import logging
import re
import socket
import threading
import time
from typing import Optional, Tuple

from proxy import __version__
from proxy import raw_websocket, tg_ws_proxy
from proxy.balancer import balancer
from proxy.config import coerce_domain_list, parse_dc_ip_list, proxy_config
from proxy.tg_ws_proxy import _run
from proxy.raw_websocket import RawWebSocket
from proxy.stats import stats
from proxy.utils import DC_DEFAULT_IPS, DomainCensorFilter, get_link_host, ws_domains

log = logging.getLogger("tg-mtproto-proxy")

DEFAULTS = {
    "port": 1443,
    "host": "127.0.0.1",
    "secret": "",
    "dc_ip": ["2:149.154.167.220", "4:149.154.167.220"],
    "verbose": False,
    "buf_kb": 512,
    "pool_size": 8,
    "cfproxy": True,
    "h2": True,
    "cfproxy_user_domain": [],
    "cfproxy_worker_domain": [],
    "force_test_dc": False,
    "no_secure": False,
    # Cloudflare transport: "auto" tries TLS:443 then plain :80 (mobile operators
    # often block/throttle 443 to CF), "plain" = :80 only, "tls" = :443 only.
    "cf_mode": "auto",
    "fake_tls_domain": "",
}

_lock = threading.Lock()
_thread: Optional[threading.Thread] = None
_async_stop: Optional[Tuple[asyncio.AbstractEventLoop, asyncio.Event]] = None
_error: Optional[str] = None
_cfg: dict = dict(DEFAULTS)


class _RingHandler(logging.Handler):
    def __init__(self, capacity: int = 400):
        super().__init__()
        self.lines = collections.deque(maxlen=capacity)

    _HEX = re.compile(r"[0-9a-fA-F]{32,}")

    def emit(self, record):
        try:
            # The core logs its secret and tg:// links at startup; never keep them,
            # so the log is always safe to copy, share or paste into a bug report.
            self.lines.append(self._HEX.sub("<secret>", self.format(record)))
        except Exception:
            pass


_ring = _RingHandler()
_ring.setFormatter(logging.Formatter(
    "%(asctime)s %(levelname)-5s %(message)s", datefmt="%H:%M:%S"))
_ring.addFilter(DomainCensorFilter())


def _setup_logging(verbose: bool) -> None:
    root = logging.getLogger()
    if _ring not in root.handlers:
        root.addHandler(_ring)
    root.setLevel(logging.DEBUG if verbose else logging.INFO)
    for name in ("asyncio", "httpx", "httpcore", "hpack", "h2"):
        logging.getLogger(name).setLevel(logging.WARNING)


def _apply(cfg: dict) -> None:
    pc = proxy_config
    pc.port = int(cfg["port"])
    pc.host = cfg["host"]
    pc.secret = cfg["secret"]
    pc.dc_redirects = parse_dc_ip_list(cfg["dc_ip"])
    pc.buffer_size = max(4, int(cfg["buf_kb"])) * 1024
    pc.pool_size = max(0, int(cfg["pool_size"]))
    pc.fallback_cfproxy = bool(cfg["cfproxy"])
    pc.cfproxy_h2_media = bool(cfg["h2"])
    pc.cfproxy_user_domains = coerce_domain_list(cfg["cfproxy_user_domain"])
    pc.cfproxy_worker_domains = coerce_domain_list(cfg["cfproxy_worker_domain"])
    pc.force_test_dc = bool(cfg["force_test_dc"])
    pc.disable_secure = bool(cfg["no_secure"])
    pc.fake_tls_domain = (cfg.get("fake_tls_domain") or "").strip()
    pc.proxy_protocol = False


_orig_connect = RawWebSocket.connect
_plain_until = 0.0      # while now < this, CF connects try plain :80 first
_PLAIN_STICKY = 600.0


def _is_ip(host: str) -> bool:
    try:
        socket.inet_aton(host)
        return True
    except OSError:
        return ":" in host


async def _smart_connect(host, domain, timeout=10.0, path="/apiws", *,
                         sni=None, secure=True):
    """CF/worker hosts only (hostnames, not Telegram IPs): fall back 443 -> 80."""
    global _plain_until
    mode = _cfg.get("cf_mode", "auto")
    if _is_ip(host) or sni is not None or mode == "tls":
        return await _orig_connect(host, domain, timeout, path, sni=sni, secure=secure)
    if mode == "plain":
        return await _orig_connect(host, domain, timeout, path, sni=sni, secure=False)
    now = time.monotonic()
    first_plain = now < _plain_until
    order = (False, True) if first_plain else (True, False)
    last = None
    for sec in order:
        # keep the first attempt short so a blocked port does not stall the client
        t = min(timeout, 4.0) if sec == order[0] else timeout
        try:
            ws = await _orig_connect(host, domain, t, path, sni=sni, secure=sec)
            if not sec and not first_plain:
                _plain_until = time.monotonic() + _PLAIN_STICKY
                log.info("CF: TLS:443 failed, using plain :80 for %d min",
                         int(_PLAIN_STICKY // 60))
            elif sec and first_plain:
                _plain_until = 0.0
            return ws
        except Exception as exc:  # noqa: BLE001
            last = exc
    raise last


RawWebSocket.connect = staticmethod(_smart_connect)


def _thread_main() -> None:
    global _async_stop, _error
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    stop_ev = asyncio.Event()
    _async_stop = (loop, stop_ev)
    try:
        loop.run_until_complete(_run(stop_event=stop_ev))
    except Exception as exc:  # noqa: BLE001
        _error = _describe_error(exc)
        log.error("Proxy thread crashed: %r", exc)
    finally:
        try:
            pending = [t for t in asyncio.all_tasks(loop) if not t.done()]
            for t in pending:
                t.cancel()
            if pending:
                loop.run_until_complete(
                    asyncio.gather(*pending, return_exceptions=True))
            loop.run_until_complete(loop.shutdown_asyncgens())
        finally:
            loop.close()
            _async_stop = None


def _describe_error(exc: BaseException) -> str:
    if isinstance(exc, OSError) and getattr(exc, "errno", None) in (98, 48):
        return "Port %s is already in use" % proxy_config.port
    return "%s: %s" % (type(exc).__name__, exc)


def is_running() -> bool:
    return _thread is not None and _thread.is_alive()


def start(cfg_json: str) -> str:
    """Start the proxy. Returns "" on success or an error message."""
    global _thread, _error, _cfg
    with _lock:
        if is_running():
            return ""
        cfg = dict(DEFAULTS)
        cfg.update(json.loads(cfg_json))
        if len(cfg["secret"]) != 32:
            return "Secret must be exactly 32 hex characters"
        try:
            bytes.fromhex(cfg["secret"])
            _setup_logging(bool(cfg["verbose"]))
            _apply(cfg)
        except ValueError as exc:
            return "Bad config: %s" % exc
        _cfg = cfg
        _error = None
        tg_ws_proxy._server_instance = None
        log.info("tg-ws-proxy %s (Android) starting on %s:%d",
                 __version__, proxy_config.host, proxy_config.port)
        _thread = threading.Thread(
            target=_thread_main, daemon=True, name="proxy")
        _thread.start()

        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if tg_ws_proxy._server_instance is not None:
                return ""
            if not _thread.is_alive():
                _thread = None
                return _error or "Proxy failed to start"
            time.sleep(0.05)
        return ""  # still warming up, it is running


def stop() -> None:
    global _thread
    with _lock:
        stopper = _async_stop
        if stopper is not None:
            loop, ev = stopper
            try:
                loop.call_soon_threadsafe(ev.set)
            except RuntimeError:
                pass
        if _thread is not None:
            _thread.join(timeout=8)
            if _thread.is_alive():
                log.warning("Proxy thread did not stop in time")
        _thread = None
        log.info("Proxy stopped")


def link(cfg_json: str = "") -> str:
    cfg = dict(DEFAULTS)
    cfg.update(json.loads(cfg_json) if cfg_json else _cfg)
    host = get_link_host(cfg["host"])
    secret = cfg["secret"]
    ftls = (cfg.get("fake_tls_domain") or "").strip()
    if ftls:
        secret = "ee" + secret + ftls.encode("ascii").hex()
    else:
        secret = "dd" + secret
    return "tg://proxy?server=%s&port=%s&secret=%s" % (host, cfg["port"], secret)


# ---------------------------------------------------------------------------
# Mobile-network tuning
#
# Cellular carriers sit behind CGNAT and silently drop idle or half-dead TCP
# flows, and a handover (LTE <-> Wi-Fi, tower change) leaves old sockets
# hanging until a long timeout. The desktop core does not need to care, so we
# harden the sockets here instead of touching the shared core.
# ---------------------------------------------------------------------------
_KEEPIDLE = 25      # s of silence before the first keepalive probe
_KEEPINTVL = 10     # s between probes
_KEEPCNT = 3        # failed probes before the connection is dropped
_USER_TIMEOUT = 35000  # ms: give up on unacknowledged data


def _harden_socket(transport) -> None:
    try:
        sock = transport.get_extra_info("socket")
        if sock is None:
            return
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        for name, value in (
            ("TCP_KEEPIDLE", _KEEPIDLE),
            ("TCP_KEEPINTVL", _KEEPINTVL),
            ("TCP_KEEPCNT", _KEEPCNT),
            ("TCP_USER_TIMEOUT", _USER_TIMEOUT),
        ):
            opt = getattr(socket, name, None)
            if opt is not None:
                try:
                    sock.setsockopt(socket.IPPROTO_TCP, opt, value)
                except OSError:
                    pass
    except Exception:  # noqa: BLE001 - never let tuning break a connection
        pass


def _patch_sockets() -> None:
    original = raw_websocket.set_sock_opts
    if getattr(original, "_mobile_patched", False):
        return

    def set_sock_opts(transport, buffer_size):
        original(transport, buffer_size)
        _harden_socket(transport)

    set_sock_opts._mobile_patched = True  # type: ignore[attr-defined]
    # The core imports the function by name in two places.
    raw_websocket.set_sock_opts = set_sock_opts
    tg_ws_proxy.set_sock_opts = set_sock_opts


_patch_sockets()


def network_changed(hard: bool = True) -> None:
    """Called from Kotlin when the default network switched or came back.

    Always drops pooled upstream sockets (they may belong to a dead path) and
    warms the pools up again. With ``hard`` (the network really changed, e.g.
    LTE <-> Wi-Fi) it also closes active client sessions so Telegram reconnects
    at once instead of waiting for TCP timeouts. A brief blip on the *same*
    network is ``soft``: live sessions usually survive it, so we keep them.
    """
    stopper = _async_stop
    if stopper is None:
        return
    loop, _ = stopper

    async def _reset() -> None:
        from proxy.bridge import reset_tcp_backoff
        from proxy.pool import cf_worker_pool, ws_pool

        reset_tcp_backoff()
        ws_pool.reset()
        cf_worker_pool.reset()
        if hard:
            for task in list(tg_ws_proxy._client_tasks):
                task.cancel()
        await ws_pool.warmup()
        await cf_worker_pool.warmup()
        log.info("Network %s: pools reset%s", "changed" if hard else "blip",
                 ", sessions dropped" if hard else "")

    try:
        asyncio.run_coroutine_threadsafe(_reset(), loop)
    except RuntimeError:
        pass


def get_stats() -> str:
    """Cheap JSON snapshot for the UI (speed meter and diagnostics)."""
    pool_total = stats.pool_hits + stats.pool_misses
    return json.dumps({
        "up": stats.bytes_up,
        "down": stats.bytes_down,
        "active": stats.connections_active,
        "total": stats.connections_total,
        "ws": stats.connections_ws,
        "cf": stats.connections_cfproxy,
        "tcp": stats.connections_tcp_fallback,
        "errors": stats.ws_errors,
        "pool_hits": stats.pool_hits,
        "pool_total": pool_total,
    })


def core_version() -> str:
    return __version__


# ---------------------------------------------------------------------------
# Live tuning and connectivity test
# ---------------------------------------------------------------------------
def set_tuning(pool_size: int) -> None:
    """Apply a new pool size without restarting (used by per-network profiles)."""
    proxy_config.pool_size = max(1, min(16, int(pool_size)))
    stopper = _async_stop
    if stopper is None:
        return
    loop, _ = stopper

    async def _warm() -> None:
        from proxy.pool import ws_pool
        await ws_pool.warmup()

    try:
        asyncio.run_coroutine_threadsafe(_warm(), loop)
    except RuntimeError:
        pass
    log.info("Profile applied: pool size %d", proxy_config.pool_size)


def _short_error(exc: BaseException) -> str:
    if isinstance(exc, (asyncio.TimeoutError, TimeoutError)):
        return "timeout"
    if isinstance(exc, OSError) and getattr(exc, "errno", None):
        return exc.strerror or type(exc).__name__
    name = type(exc).__name__
    text = str(exc)
    return (name + ": " + text)[:60] if text else name


async def _probe(name: str, coro_factory, timeout: float = 6.0) -> dict:
    started = time.monotonic()
    try:
        await asyncio.wait_for(coro_factory(), timeout=timeout)
        return {"name": name, "ok": True,
                "ms": int((time.monotonic() - started) * 1000)}
    except BaseException as exc:  # noqa: BLE001
        if isinstance(exc, (KeyboardInterrupt, SystemExit)):
            raise
        return {"name": name, "ok": False, "error": _short_error(exc)}


async def _diagnose(cfg: dict) -> list:
    dcs = parse_dc_ip_list(cfg["dc_ip"]) or {2: "149.154.167.220"}
    probes = []

    for dc, ip in sorted(dcs.items()):
        async def ws_probe(dc=dc, ip=ip):
            ws = await RawWebSocket.connect(ip, ws_domains(dc, False)[0], timeout=6.0)
            await ws.close()
        probes.append(_probe("WS DC%d" % dc, ws_probe))

    for dc in sorted(dcs):
        direct = DC_DEFAULT_IPS.get(dc)
        if not direct:
            continue

        async def tcp_probe(direct=direct):
            _, writer = await asyncio.open_connection(direct, 443)
            writer.close()
        probes.append(_probe("TCP DC%d" % dc, tcp_probe))

    if cfg.get("cfproxy"):
        domains = list(balancer.domains) or coerce_domain_list(
            cfg.get("cfproxy_user_domain") or [])
        if domains:
            dc = min(dcs)
            for base in domains[:2]:
                async def cf_probe(base=base, dc=dc):
                    host = "kws%d.%s" % (dc, base)
                    ws = await RawWebSocket.connect(host, host, timeout=6.0)
                    await ws.close()
                probes.append(_probe("CF " + base[:18], cf_probe))

    return list(await asyncio.gather(*probes))


def diagnose(cfg_json: str = "") -> str:
    """Measure every route to Telegram. Safe to call whether or not the proxy runs."""
    cfg = dict(DEFAULTS)
    cfg.update(json.loads(cfg_json) if cfg_json else _cfg)
    results = asyncio.run(_diagnose(cfg))
    needs_proxy = bool(cfg.get("cfproxy")) and not any(
        r["name"].startswith("CF") for r in results)
    return json.dumps({"results": results, "cf_needs_proxy": needs_proxy})


def get_logs() -> str:
    return "\n".join(_ring.lines)


def last_error() -> str:
    return _error or ""
