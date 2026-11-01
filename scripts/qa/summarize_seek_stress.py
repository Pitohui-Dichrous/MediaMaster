#!/usr/bin/env python3
"""Read sustained Surface stress results and report comparable burst-only picture staleness."""

import argparse
import json
import math
from pathlib import Path
import sys


def summarize(path):
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if data.get("protocol") not in ("sustained-surface-v1", "sustained-surface-v2", "sustained-surface-v3", "sustained-surface-v4"):
        raise ValueError(f"Unsupported stress protocol: {path}")
    end = data.get("finalTapUpMs")
    if end is None:
        taps = data.get("taps", [])
        end = taps[-1].get("upMs") if taps else None
    if end is None:
        raise ValueError(f"No final dispatched tap; cannot bound burst: {path}")
    ages, intervals = [], []
    last_change = 0
    previous_capture = 0
    max_gap = 0
    discontinuities = 0
    largest_unknown_replacement_gap = 0
    for sample in data.get("timeline", []):
        when = sample.get("endMs")
        if when is None or not 0 <= when <= end or "changeRgb" not in sample:
            continue
        gap = when - previous_capture
        max_gap = max(max_gap, gap)
        if sample.get("surfaceDiscontinuity"):
            intervals.append(max(0, previous_capture - last_change))
            discontinuities += 1
            largest_unknown_replacement_gap = max(largest_unknown_replacement_gap, gap)
            last_change = when
            previous_capture = when
            ages.append(0)
            continue
        previous_capture = when
        age = max(0, when - last_change)
        if sample["changeRgb"] > .8:
            intervals.append(age)
            last_change = when
            ages.append(0)
        else:
            ages.append(age)
    if not ages:
        raise ValueError(f"No valid burst-only picture samples: {path}")
    # Close the observed burst interval. Missing captures remain a measurement limitation and are
    # exposed separately; they do not prove that the picture was frozen throughout the gap.
    tail = max(0, previous_capture - last_change)
    max_gap = max(max_gap, end - previous_capture)
    sorted_ages = sorted(ages)
    p95 = sorted_ages[max(0, math.ceil(len(sorted_ages) * .95) - 1)]
    return {
        "run": data.get("run"), "protocol": data["protocol"],
        "mediaAlias": data.get("mediaAlias"), "state": data.get("state"),
        "input": data.get("input"), "scheduledHz": data.get("scheduledHz"),
        "completed": data.get("completed"),
        "cadence": {key: value for key, value in data.get("cadence", {}).items()
                    if key != "actualWindowTouchDispatch"},
        "validBurstPictureSamples": len(ages), "sampledStaleAgeP95Ms": p95,
        "sampledStaleAgeMaxMs": max(ages),
        "longestObservedChangeIntervalMs": max(intervals + [tail]),
        "largestBurstSamplingGapMs": max_gap,
        "surfaceDiscontinuities": discontinuities,
        "largestUnknownReplacementGapMs": largest_unknown_replacement_gap,
        "reportedLongestBurstUnchangedMs": data.get("longestBurstUnchangedMs"),
        "finalReferenceRecoveryMs": data.get("firstReferenceMatchAfterFinalUpMs"),
        "finalTargetRecoveryMs": data.get("finalTargetRecoveryMs", data.get("firstReferenceMatchAfterFinalUpMs")),
        "barcodeOracle": data.get("barcodeOracle"),
        "mainSnapshotTimeouts": data.get("mainSnapshotTimeouts"),
        "note": "Only samples between the first planned DOWN and final UP are used. Stale ages use an RGB-change threshold of 0.8; these are sampled content-change intervals, not decoded PTS or frame presentation times. Report sampling gaps alongside them. The test's 2-second hang gate is not a sub-500-ms responsiveness certificate.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", nargs="+", type=Path, help="Explicit results.json files")
    parser.add_argument("--output", type=Path, help="Optional new report; existing files are never overwritten")
    args = parser.parse_args()
    report = {"runs": [summarize(path) for path in args.results]}
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        with args.output.open("x", encoding="utf-8") as destination:
            destination.write(encoded)
    else:
        print(encoded, end="")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError) as error:
        print(f"Stress summary failed: {error}", file=sys.stderr)
        sys.exit(1)
