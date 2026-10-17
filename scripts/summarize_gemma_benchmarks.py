"""Summarize raw GemmaEngineBenchmarkTest JSON without inventing quality scores.

Usage: python scripts/summarize_gemma_benchmarks.py path/to/raw/reports
P95 is nearest-rank; small-sample p95 is only descriptive, not a guarantee.
"""
import json
import math
import statistics
import sys
from pathlib import Path


def percentile95(values):
    return sorted(values)[math.ceil(len(values) * .95) - 1] if values else None


def summary(report):
    samples = [s for s in report.get("samples", []) if s.get("status") == "complete"]
    # The first generated response includes cold model page/shader effects.
    warm = samples[1:]
    ttft = [s["firstCallbackMs"] for s in warm]
    speed = [s["decodeTokensPerSecond"] for s in warm if s.get("decodeTokensPerSecond", 0) > 0]
    return {
        "model": report["model"], "runtime": report["runtime"],
        "backendRequested": report["backendRequested"], "status": report.get("status", "incomplete"),
        "actualGpuLayers": report.get("actualGpuLayers"),
        "completedSamples": len(samples), "initializationMs": report.get("initializationMs"),
        "firstResponseTtftMs": samples[0]["firstCallbackMs"] if samples else None,
        "warmMedianTtftMs": statistics.median(ttft) if ttft else None,
        "warmP95TtftMs": percentile95(ttft),
        "warmMedianDecodeTokensPerSecond": statistics.median(speed) if speed else None,
        "peakProcessPssMb": report.get("peakProcessPssKb", 0) / 1024,
        "thermalBefore": report.get("thermalBefore"), "thermalAfter": report.get("thermalAfter"),
        "error": report.get("error"),
        "quality": "Review raw responses; throughput is not a quality score.",
    }


if __name__ == "__main__":
    folder = Path(sys.argv[1])
    print(json.dumps([summary(json.loads(p.read_text(encoding="utf-8-sig")))
                      for p in sorted(folder.glob("gemma-*.json"))], indent=2, ensure_ascii=False))
