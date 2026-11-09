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
import socket
import threading
import time
from typing import Optional, Tuple

from proxy import __version__
from proxy import raw_websocket, tg_ws_proxy
from proxy.config import coerce_domain_list, parse_dc_ip_list, proxy_config
from proxy.tg_ws_proxy import _run
from proxy.utils import DomainCensorFilter, get_link_host

log = logging.getLogger("tg-mtproto-proxy")

DEFAULTS = {
    "port": 1443,
    "host": "127.0.0.1",
    "secret": "",
    "dc_ip": ["2:149.154.167.220", "4:149.154.167.220"],
    "verbose": False,
    "buf_kb": 256,
    "pool_size": 4,
    "cfproxy": True,
    "h2": True,
    "cfproxy_user_domain": [],
    "cfproxy_worker_domain": [],
    "force_test_dc": False,
    "no_secure": False,
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

    def emit(self, record):
        try:
            self.lines.append(self.format(record))
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


def network_changed() -> None:
    """Called from Kotlin when the default network switched or came back.

    Drops pooled upstream sockets (they belong to the old network) and the
    active client sessions, so Telegram reconnects immediately instead of
    waiting for TCP timeouts, then warms the pools up again.
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
        for task in list(tg_ws_proxy._client_tasks):
            task.cancel()
        await ws_pool.warmup()
        await cf_worker_pool.warmup()
        log.info("Network changed: connections reset, pools warming up")

    try:
        asyncio.run_coroutine_threadsafe(_reset(), loop)
    except RuntimeError:
        pass


def core_version() -> str:
    return __version__


def get_logs() -> str:
    return "\n".join(_ring.lines)


def last_error() -> str:
    return _error or ""
