#!/usr/bin/env python3
"""M0 backend benchmark.

Nothing downstream in claustrum can be designed before this produces numbers.
The single-frame latency of the L1 caption stage determines the L0 keyframe
budget, whether a two-model tier is needed, and the ceiling on realtime alerting.

All three candidate backends expose an OpenAI-compatible /v1/chat/completions
endpoint, so the abstraction is one HTTP client against three server configs:

    litert-lm serve          --port 8081
    llama-server (CUDA)      --port 8082
    trtllm-serve             --port 8083

Start the servers yourself (one at a time -- they will contend for memory),
declare them in backends.yaml, then run this.

Usage
-----
    python bench/run_bench.py --frames bench/frames --out eval/reports
    python bench/run_bench.py --backend llamacpp --repeats 20 --grid 2x2
"""

from __future__ import annotations

import argparse
import base64
import json
import statistics
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import requests
import yaml

from tegra_monitor import TegraMonitor

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))

PROMPT_PATH = REPO_ROOT / "prompts" / "caption_v1.md"


# --------------------------------------------------------------------------- #
# Backend abstraction
#
# This interface is deliberately the same shape as core.vlm.VlmBackend will be.
# The benchmark and the production caption stage share one abstraction so the
# work is not done twice -- and so that a backend swap later is a config change
# rather than a rewrite.
# --------------------------------------------------------------------------- #


@dataclass
class BackendConfig:
    name: str
    base_url: str
    model: str
    api_key: str = "not-needed"
    max_tokens: int = 512
    temperature: float = 0.1
    notes: str = ""


@dataclass
class CallResult:
    ok: bool
    latency_s: float
    ttft_s: float | None
    raw_text: str
    parsed: dict | None
    parse_error: str | None = None


class OpenAICompatBackend:
    """One client for litert-lm serve, llama-server and trtllm-serve alike."""

    def __init__(self, cfg: BackendConfig) -> None:
        self.cfg = cfg
        self.session = requests.Session()

    def health(self) -> bool:
        for path in ("/v1/models", "/health", "/"):
            try:
                r = self.session.get(f"{self.cfg.base_url}{path}", timeout=5)
                if r.status_code < 500:
                    return True
            except requests.RequestException:
                continue
        return False

    def caption(self, prompt: str, image_paths: list[Path], stream: bool = True) -> CallResult:
        content: list[dict] = [{"type": "text", "text": prompt}]
        for p in image_paths:
            b64 = base64.b64encode(p.read_bytes()).decode()
            mime = "image/png" if p.suffix.lower() == ".png" else "image/jpeg"
            content.append(
                {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}}
            )

        payload = {
            "model": self.cfg.model,
            "messages": [{"role": "user", "content": content}],
            "max_tokens": self.cfg.max_tokens,
            "temperature": self.cfg.temperature,
            "stream": stream,
        }
        headers = {"Authorization": f"Bearer {self.cfg.api_key}"}

        t0 = time.perf_counter()
        ttft: float | None = None
        chunks: list[str] = []

        try:
            r = self.session.post(
                f"{self.cfg.base_url}/v1/chat/completions",
                json=payload,
                headers=headers,
                timeout=300,
                stream=stream,
            )
            r.raise_for_status()

            if stream:
                for line in r.iter_lines():
                    if not line or not line.startswith(b"data: "):
                        continue
                    body = line[6:]
                    if body.strip() == b"[DONE]":
                        break
                    try:
                        delta = json.loads(body)["choices"][0]["delta"].get("content")
                    except (KeyError, IndexError, json.JSONDecodeError):
                        continue
                    if delta:
                        if ttft is None:
                            ttft = time.perf_counter() - t0
                        chunks.append(delta)
                text = "".join(chunks)
            else:
                text = r.json()["choices"][0]["message"]["content"]
        except requests.RequestException as e:
            return CallResult(False, time.perf_counter() - t0, None, "", None, str(e))

        latency = time.perf_counter() - t0
        parsed, err = parse_kineme_json(text)
        return CallResult(True, latency, ttft, text, parsed, err)


# --------------------------------------------------------------------------- #
# JSON tolerance
#
# Models wrap JSON in markdown fences and prepend commentary regardless of what
# the prompt says. Measuring the *unrecovered* parse failure rate is what
# matters; recovering the recoverable ones is cheap and belongs in production.
# --------------------------------------------------------------------------- #


def parse_kineme_json(text: str) -> tuple[dict | None, str | None]:
    s = text.strip()
    if s.startswith("```"):
        s = s.split("```")[1] if "```" in s[3:] else s[3:]
        if s.lstrip().lower().startswith("json"):
            s = s.lstrip()[4:]
    start, end = s.find("{"), s.rfind("}")
    if start == -1 or end <= start:
        return None, "no JSON object found"
    try:
        return json.loads(s[start : end + 1]), None
    except json.JSONDecodeError as e:
        return None, f"JSONDecodeError: {e}"


# --------------------------------------------------------------------------- #
# Frame fixtures
# --------------------------------------------------------------------------- #


def load_fixtures(frames_dir: Path, grid: str) -> list[list[Path]]:
    """Group frames into VLM calls.

    grid '1x1' -> one image per call.
    grid '2x2' -> four consecutive frames per call, to test whether the model can
    read temporal order from a single composite. If it can, the L0 keyframe
    budget stretches by 4x, which changes the entire power envelope.
    """
    imgs = sorted(
        p for p in frames_dir.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"}
    )
    if not imgs:
        raise SystemExit(
            f"no frames in {frames_dir} -- see bench/frames/README.md for how to build the fixture set"
        )
    per_call = {"1x1": 1, "1x2": 2, "2x2": 4, "1x4": 4}.get(grid)
    if per_call is None:
        raise SystemExit(f"unsupported grid {grid!r}")
    return [imgs[i : i + per_call] for i in range(0, len(imgs), per_call) if len(imgs[i : i + per_call]) == per_call]


# --------------------------------------------------------------------------- #
# Reporting
# --------------------------------------------------------------------------- #


@dataclass
class BackendReport:
    backend: str
    model: str
    grid: str
    n_calls: int = 0
    n_ok: int = 0
    n_parsed: int = 0
    latencies: list[float] = field(default_factory=list)
    ttfts: list[float] = field(default_factory=list)
    parse_errors: list[str] = field(default_factory=list)
    samples: list[dict] = field(default_factory=list)
    tegra: dict = field(default_factory=dict)

    def pct(self, p: float) -> float | None:
        if not self.latencies:
            return None
        xs = sorted(self.latencies)
        k = min(int(round(p / 100 * (len(xs) - 1))), len(xs) - 1)
        return xs[k]

    def summary(self) -> dict:
        return {
            "backend": self.backend,
            "model": self.model,
            "grid": self.grid,
            "calls": self.n_calls,
            "success_rate": round(self.n_ok / self.n_calls, 4) if self.n_calls else None,
            "json_parse_rate": round(self.n_parsed / self.n_ok, 4) if self.n_ok else None,
            "latency_p50_s": round(self.pct(50), 3) if self.latencies else None,
            "latency_p95_s": round(self.pct(95), 3) if self.latencies else None,
            "latency_mean_s": round(statistics.mean(self.latencies), 3) if self.latencies else None,
            "ttft_p50_s": round(statistics.median(self.ttfts), 3) if self.ttfts else None,
            "tegra": self.tegra,
        }


def markdown_table(reports: list[BackendReport]) -> str:
    hdr = (
        "| backend | grid | p50 (s) | p95 (s) | TTFT p50 | JSON ok | peak RAM (MB) | "
        "peak GPU % | peak temp (C) | mean power (mW) |\n"
        "|---|---|---|---|---|---|---|---|---|---|\n"
    )
    rows = []
    for r in reports:
        s = r.summary()
        t = s["tegra"] or {}
        rows.append(
            "| {b} | {g} | {p50} | {p95} | {ttft} | {js} | {ram} | {gpu} | {temp} | {pw} |".format(
                b=s["backend"],
                g=s["grid"],
                p50=s["latency_p50_s"],
                p95=s["latency_p95_s"],
                ttft=s["ttft_p50_s"],
                js=f"{s['json_parse_rate']:.0%}" if s["json_parse_rate"] is not None else "-",
                ram=t.get("peak_ram_mb", "-"),
                gpu=t.get("peak_gpu_pct", "-"),
                temp=t.get("peak_temp_c", "-"),
                pw=t.get("mean_power_mw", "-"),
            )
        )
    return hdr + "\n".join(rows) + "\n"


# --------------------------------------------------------------------------- #


def run_backend(cfg: BackendConfig, calls: list[list[Path]], prompt: str, grid: str,
                repeats: int, monitor: bool) -> BackendReport:
    be = OpenAICompatBackend(cfg)
    rep = BackendReport(backend=cfg.name, model=cfg.model, grid=grid)

    if not be.health():
        print(f"  ! {cfg.name} unreachable at {cfg.base_url} -- skipping", file=sys.stderr)
        return rep

    # Warm-up: first call includes weight load and graph build. Excluding it is
    # the honest thing to do, but note the cost separately -- a 90 s cold start
    # matters for a service that may restart.
    print(f"  warming up {cfg.name} ...", flush=True)
    w0 = time.perf_counter()
    be.caption(prompt, calls[0])
    rep.tegra["cold_start_s"] = round(time.perf_counter() - w0, 2)

    tm = TegraMonitor() if monitor else None
    if tm:
        tm.start()

    total = len(calls) * repeats
    done = 0
    for rep_i in range(repeats):
        for frames in calls:
            res = be.caption(prompt, frames)
            rep.n_calls += 1
            done += 1
            if res.ok:
                rep.n_ok += 1
                rep.latencies.append(res.latency_s)
                if res.ttft_s is not None:
                    rep.ttfts.append(res.ttft_s)
                if res.parsed is not None:
                    rep.n_parsed += 1
                else:
                    rep.parse_errors.append(res.parse_error or "unknown")
                if rep_i == 0 and len(rep.samples) < 8:
                    # Retain first-pass outputs for manual caption-quality scoring.
                    rep.samples.append(
                        {
                            "frames": [f.name for f in frames],
                            "raw": res.raw_text[:1200],
                            "parsed": res.parsed,
                            "manual_score": None,  # fill in 1-5 by hand
                            "hallucinated": None,  # fill in true/false by hand
                        }
                    )
            else:
                rep.parse_errors.append(res.parse_error or "request failed")
            print(f"\r  {cfg.name}: {done}/{total}", end="", flush=True)
    print()

    if tm:
        rep.tegra.update(tm.stop())
    return rep


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", type=Path, default=Path(__file__).parent / "backends.yaml")
    ap.add_argument("--frames", type=Path, default=Path(__file__).parent / "frames")
    ap.add_argument("--out", type=Path, default=REPO_ROOT / "eval" / "reports")
    ap.add_argument("--backend", action="append", help="restrict to named backend(s)")
    ap.add_argument("--grid", default="1x1", choices=["1x1", "1x2", "2x2", "1x4"])
    ap.add_argument("--repeats", type=int, default=5)
    ap.add_argument("--no-monitor", action="store_true", help="skip tegrastats sampling")
    args = ap.parse_args()

    if not args.config.exists():
        raise SystemExit(f"missing {args.config} -- copy backends.example.yaml and edit it")

    raw = yaml.safe_load(args.config.read_text())
    configs = [BackendConfig(**b) for b in raw["backends"]]
    if args.backend:
        configs = [c for c in configs if c.name in args.backend]
    if not configs:
        raise SystemExit("no backends selected")

    prompt = PROMPT_PATH.read_text()
    calls = load_fixtures(args.frames, args.grid)
    print(f"{len(calls)} call(s) per repeat x {args.repeats} repeat(s), grid={args.grid}\n")

    reports = [
        run_backend(c, calls, prompt, args.grid, args.repeats, not args.no_monitor)
        for c in configs
    ]

    args.out.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    base = args.out / f"m0_backend_bench_{stamp}_{args.grid}"

    (base.with_suffix(".json")).write_text(
        json.dumps(
            {
                "generated_at": stamp,
                "grid": args.grid,
                "repeats": args.repeats,
                "prompt_version": "caption_v1",
                "results": [r.summary() for r in reports],
                "samples": {r.backend: r.samples for r in reports},
                "parse_errors": {r.backend: r.parse_errors[:20] for r in reports},
            },
            indent=2,
            ensure_ascii=False,
        )
    )
    table = markdown_table(reports)
    (base.with_suffix(".md")).write_text(
        f"# M0 backend benchmark — {stamp}\n\n"
        f"grid `{args.grid}` · {args.repeats} repeats · prompt `caption_v1`\n\n"
        f"{table}\n"
        "## Interpreting this\n\n"
        "- **p95 latency sets the L0 keyframe budget.** If p95 is 3 s, the pipeline can "
        "sustain roughly one keyframe every 3 s at 100% duty; halve that for headroom.\n"
        "- **If `2x2` grid latency is under ~2x the `1x1` latency**, composite framing wins "
        "and the VLM call budget stretches accordingly. Run both grids and compare.\n"
        "- **peak temp under sustained load** decides whether 30 W mode is viable in a "
        "domestic setting. Watch for a rising trend across repeats, not just the peak.\n"
        "- **JSON parse rate below ~98%** means prompt work, not backend work.\n"
        "- Fill in `manual_score` (1-5) and `hallucinated` (bool) in the JSON samples by hand. "
        "Hallucination rate is the metric that decides whether this project is viable at all.\n"
    )

    print("\n" + table)
    print(f"written: {base.with_suffix('.md')}")
    print(f"         {base.with_suffix('.json')}")
    print("\nNext: score the samples by hand, then decide the keyframe budget.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
