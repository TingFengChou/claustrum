"""Sample a phone's thermal, power and memory state over adb during a benchmark.

The phone-first analogue of tegra_monitor.py (ADR-0004). Same interface --
`start()` then `stop() -> dict` -- so run_bench.py can use either behind one
factory. Sampling is done from the host via `adb shell`; the benchmark HTTP
client already reaches the on-device server over `adb forward`, so nothing else
needs a phone-side agent.

Thermal and battery, not fan noise, are the sustainability limits on a phone:
continuous inference throttles and drains. Temperature *drift* across a run is
the signal that a long run will eventually throttle, so it is reported the same
way tegra_monitor does.

Falls back to a no-op with a warning when adb is missing or no device is
attached, so the benchmark still runs on a workstation.

NOTE: sysfs paths and units vary by device. `current_now` is micro-amps on most
Pixels but milli-amps on some phones, and its sign convention for charge vs
discharge differs; we take the magnitude. Validate the fields below against the
actual Pixel 10 the first time it is in hand before trusting the power figures.
"""

from __future__ import annotations

import shutil
import statistics
import subprocess
import sys
import threading
from dataclasses import dataclass, field

_MEM = "cat /proc/meminfo"
_CURRENT = "cat /sys/class/power_supply/battery/current_now 2>/dev/null"
_VOLTAGE = "cat /sys/class/power_supply/battery/voltage_now 2>/dev/null"
_THERMAL = "cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null"


@dataclass
class _Samples:
    ram_used_mb: list[int] = field(default_factory=list)
    ram_total_mb: int = 0
    temps_c: list[float] = field(default_factory=list)
    power_mw: list[int] = field(default_factory=list)


class AndroidMonitor:
    def __init__(self, interval_ms: int = 1000, serial: str | None = None) -> None:
        self.interval_ms = interval_ms
        self.serial = serial
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._s = _Samples()
        self.available = shutil.which("adb") is not None and self._device_present()

    # -- adb plumbing ------------------------------------------------------- #

    def _adb(self, *args: str) -> str | None:
        cmd = ["adb"]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += list(args)
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        except (subprocess.TimeoutExpired, OSError):
            return None
        return out.stdout if out.returncode == 0 else None

    def _device_present(self) -> bool:
        state = self._adb("get-state")
        return state is not None and state.strip() == "device"

    # -- sampling ----------------------------------------------------------- #

    def start(self) -> None:
        if not self.available:
            print("  (adb / device not found -- skipping thermal/power sampling)", file=sys.stderr)
            return
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def _loop(self) -> None:
        while not self._stop.is_set():
            self._tick()
            self._stop.wait(self.interval_ms / 1000)

    def _tick(self) -> None:
        # One combined shell to keep it to a single adb round-trip per sample.
        blob = self._adb(
            "shell",
            f"{_MEM}; echo __P__; {_CURRENT}; echo __V__; {_VOLTAGE}; echo __T__; {_THERMAL}",
        )
        if not blob:
            return
        mem_part, _, rest = blob.partition("__P__")
        cur_part, _, rest = rest.partition("__V__")
        volt_part, _, temp_part = rest.partition("__T__")

        total = avail = None
        for line in mem_part.splitlines():
            if line.startswith("MemTotal:"):
                total = int(line.split()[1])  # kB
            elif line.startswith("MemAvailable:"):
                avail = int(line.split()[1])  # kB
        if total is not None and avail is not None:
            self._s.ram_total_mb = total // 1024
            self._s.ram_used_mb.append((total - avail) // 1024)

        cur = _first_int(cur_part)
        volt = _first_int(volt_part)
        if cur is not None and volt is not None:
            # mW = |uA| * uV / 1e9  (see units caveat in the module docstring)
            self._s.power_mw.append(abs(cur) * volt // 1_000_000_000)

        temps = [int(v) / 1000 for v in temp_part.split() if v.strip().lstrip("-").isdigit()]
        # thermal_zone temps are milli-degC; a few zones report ambient/garbage,
        # so the hotspot (max) is the number that matters for throttling.
        plausible = [t for t in temps if 0 < t < 150]
        if plausible:
            self._s.temps_c.append(max(plausible))

    def stop(self) -> dict:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=5)

        s = self._s
        if not s.ram_used_mb and not s.temps_c:
            return {"adb": "unavailable"}

        out: dict = {"samples": max(len(s.ram_used_mb), len(s.temps_c))}
        if s.ram_used_mb:
            out["peak_ram_mb"] = max(s.ram_used_mb)
            out["mean_ram_mb"] = round(statistics.mean(s.ram_used_mb))
            out["total_ram_mb"] = s.ram_total_mb
        if s.temps_c:
            out["peak_temp_c"] = round(max(s.temps_c), 1)
            out["mean_temp_c"] = round(statistics.mean(s.temps_c), 1)
            half = len(s.temps_c) // 2
            if half:
                out["temp_drift_c"] = round(
                    statistics.mean(s.temps_c[half:]) - statistics.mean(s.temps_c[:half]), 1
                )
        if s.power_mw:
            out["peak_power_mw"] = max(s.power_mw)
            out["mean_power_mw"] = round(statistics.mean(s.power_mw))
        return out


def _first_int(text: str) -> int | None:
    for line in text.splitlines():
        line = line.strip()
        if line.lstrip("-").isdigit():
            return int(line)
    return None
