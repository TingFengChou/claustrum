"""Sample tegrastats during a benchmark run.

Peak memory, GPU utilisation, temperature and power draw are as important as
latency for this project: the target is a process that runs for seven days in a
domestic setting without thermal throttling or audible fan ramp.

Falls back to a no-op with a warning when tegrastats is absent, so the benchmark
still runs on a workstation.
"""

from __future__ import annotations

import re
import shutil
import statistics
import subprocess
import sys
import threading
from dataclasses import dataclass, field

RE_RAM = re.compile(r"RAM (\d+)/(\d+)MB")
RE_GPU = re.compile(r"GR3D_FREQ (\d+)%")
RE_TEMP = re.compile(r"(\w+)@([\d.]+)C")
RE_POWER = re.compile(r"(VDD_\w+|VIN_\w+) (\d+)mW/(\d+)mW")


@dataclass
class _Samples:
    ram_mb: list[int] = field(default_factory=list)
    ram_total_mb: int = 0
    gpu_pct: list[int] = field(default_factory=list)
    temps_c: list[float] = field(default_factory=list)
    power_mw: list[int] = field(default_factory=list)


class TegraMonitor:
    def __init__(self, interval_ms: int = 1000) -> None:
        self.interval_ms = interval_ms
        self._proc: subprocess.Popen | None = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._s = _Samples()
        self.available = shutil.which("tegrastats") is not None

    def start(self) -> None:
        if not self.available:
            print("  (tegrastats not found -- skipping thermal/power sampling)", file=sys.stderr)
            return
        self._proc = subprocess.Popen(
            ["tegrastats", "--interval", str(self.interval_ms)],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            bufsize=1,
        )
        self._thread = threading.Thread(target=self._read, daemon=True)
        self._thread.start()

    def _read(self) -> None:
        assert self._proc and self._proc.stdout
        for line in self._proc.stdout:
            if self._stop.is_set():
                break
            self._parse(line)

    def _parse(self, line: str) -> None:
        if m := RE_RAM.search(line):
            self._s.ram_mb.append(int(m.group(1)))
            self._s.ram_total_mb = int(m.group(2))
        if m := RE_GPU.search(line):
            self._s.gpu_pct.append(int(m.group(1)))
        temps = [float(v) for _, v in RE_TEMP.findall(line)]
        if temps:
            self._s.temps_c.append(max(temps))
        powers = [int(cur) for _, cur, _ in RE_POWER.findall(line)]
        if powers:
            self._s.power_mw.append(sum(powers))

    def stop(self) -> dict:
        self._stop.set()
        if self._proc:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._proc.kill()
        if self._thread:
            self._thread.join(timeout=5)

        s = self._s
        if not s.ram_mb and not s.temps_c:
            return {"tegrastats": "unavailable"}

        out: dict = {"samples": len(s.ram_mb) or len(s.temps_c)}
        if s.ram_mb:
            out["peak_ram_mb"] = max(s.ram_mb)
            out["mean_ram_mb"] = round(statistics.mean(s.ram_mb))
            out["total_ram_mb"] = s.ram_total_mb
        if s.gpu_pct:
            out["peak_gpu_pct"] = max(s.gpu_pct)
            out["mean_gpu_pct"] = round(statistics.mean(s.gpu_pct))
        if s.temps_c:
            out["peak_temp_c"] = round(max(s.temps_c), 1)
            out["mean_temp_c"] = round(statistics.mean(s.temps_c), 1)
            # A rising trend across the run matters more than the peak: it is the
            # signal that a seven-day run will eventually throttle.
            half = len(s.temps_c) // 2
            if half:
                out["temp_drift_c"] = round(
                    statistics.mean(s.temps_c[half:]) - statistics.mean(s.temps_c[:half]), 1
                )
        if s.power_mw:
            out["peak_power_mw"] = max(s.power_mw)
            out["mean_power_mw"] = round(statistics.mean(s.power_mw))
        return out
