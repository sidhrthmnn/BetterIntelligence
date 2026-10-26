"""Report measured pairs; never infer correctness from a citation ID.

Quality reviews are separate, explicit annotations against independently read
references. Missing annotations stay unscored. No answers are sent to a model.
"""
import argparse
import json
import statistics
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--reviews", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = json.loads(args.input.read_text(encoding="utf-8-sig"))
    annotations = json.loads(args.reviews.read_text(encoding="utf-8-sig")) if args.reviews else {}
    reviews = {(r["id"], r["mode"]): r for r in annotations.get("reviews", [])}
    pairs = {}
    compact = []
    for row in report["results"]:
        row["review"] = reviews.get((row["id"], row["mode"]))
        pairs.setdefault(row["id"], {})[row["mode"]] = row
        item = {key: row.get(key) for key in (
            "id", "category", "mode", "question", "retrievalMs", "inferenceMs", "totalMs",
            "httpRequests", "support", "failure", "inputTokens", "outputTokens", "peakProcessPssKb",
            "beforeThermal", "afterThermal", "telemetry", "review")}
        item["answerPreview"] = row["answer"].replace("\n", " ")[:160]
        item["sources"] = [{k: source.get(k) for k in ("id", "url", "title", "updatedAt", "dateKind")} for source in row["sources"]]
        compact.append(item)
    aggregates = {}
    for mode in ("OFF", "ON"):
        rows = [r for r in report["results"] if r["mode"] == mode]
        reviewed = [r for r in rows if r["review"] is not None]
        aggregates[mode] = {
            "answers": len(rows), "httpRequests": sum(r["httpRequests"] for r in rows),
            "medianTotalMs": statistics.median(r["totalMs"] for r in rows) if rows else None,
            "totalRetrievalMs": sum(r["retrievalMs"] for r in rows),
            "totalInferenceMs": sum(r["inferenceMs"] for r in rows),
            "modelInvocations": sum(bool(r["inferenceMs"]) for r in rows),
            "failures": sum(bool(r["failure"]) for r in rows),
            "reviewed": len(reviewed),
            "reviewQualityPoints": sum(r["review"]["quality"] for r in reviewed),
            "reviewMaxPoints": 2 * len(reviewed),
            "unsupportedClaimsObserved": sum(r["review"]["unsupportedClaims"] for r in reviewed),
        }
    lines = ["# Web OFF / ON measured comparison", "",
        f"Model: **{report['model']}**, {report['device']}, Android API {report['sdk']}. "
        f"CPU, {report['threads']} threads, context {report['context']}, output {report['maxOutputTokens']}, temperature 0. "
        "Same weights, context, sampling and per-case history; alternating pair order, one run. Model reused; fresh conversation per answer.", "",
        "Network time is separate from model time. Research failure/clarification deliberately skips inference, as in the app. "
        "Direct source URLs occur in both variants; they may hint at an answer even offline. The optional correction pass is disabled for this controlled comparison. "
        "Charging and uncontrolled network prevent sustained/energy conclusions. PSS excludes some driver allocations.", "",
        "Quality is explicitly reviewed against independent reference snapshots (0 wrong/unsupported/failure, 1 useful but incomplete or appropriate uncertainty, "
        "2 correct and sufficiently complete/appropriate clarification). An abstention is never credited as a correct factual answer. "
        "These are limited agent reference reviews, not independent human ratings or a universal accuracy estimate. Missing reviews remain unscored.", "",
        "| Case | Web OFF (actual excerpt) | Web ON (actual excerpt) | Reviewed quality winner | Total latency winner |",
        "| --- | --- | --- | --- | --- |"]
    def excerpt(row):
        return (row["answer"].replace("\n", " ").replace("|", "\\|")[:110] or row["failure"][:110]) + f" ({row['totalMs']/1000:.2f}s)"
    for case, pair in sorted(pairs.items()):
        if set(pair) != {"OFF", "ON"}:
            lines.append(f"| {case} | Incomplete pair | Incomplete pair | Unscored | Unscored |")
            continue
        off, on = pair["OFF"], pair["ON"]
        winner = "Unscored"
        if off["review"] is not None and on["review"] is not None:
            delta = on["review"]["quality"] - off["review"]["quality"]
            winner = "ON" if delta > 0 else "OFF" if delta < 0 else "Tie"
        speed = "OFF" if off["totalMs"] < on["totalMs"] else "ON" if on["totalMs"] < off["totalMs"] else "Tie"
        lines.append(f"| {case}: {off['category']} | {excerpt(off)} | {excerpt(on)} | {winner} | {speed} |")
    lines += ["", "## Aggregate measurements", "", "```json", json.dumps(aggregates, indent=2), "```", "",
        "Per-category review points (maximum two per reviewed answer):", "",
        "| Category | OFF points / reviewed maximum | ON points / reviewed maximum |", "| --- | --- | --- |"]
    for category in sorted({r["category"] for r in report["results"]}):
        values = []
        for mode in ("OFF", "ON"):
            selected = [r["review"] for r in report["results"] if r["mode"] == mode and r["category"] == category and r["review"] is not None]
            values.append(f"{sum(r['quality'] for r in selected)} / {len(selected)*2}")
        lines.append(f"| {category} | {values[0]} | {values[1]} |")
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "WEB_ON_OFF.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (args.output / "web-on-off-measurements.json").write_text(json.dumps({
        "device": report["device"], "model": report["model"], "sha256": report["sha256"],
        "loadMs": report.get("loadMs"), "released": report.get("released"),
        "startedAt": report["startedAt"], "endedAt": report.get("endedAt"),
        "settings": {k: report[k] for k in ("context", "maxOutputTokens", "threads", "temperature", "repeats")},
        "aggregates": aggregates, "results": compact}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
