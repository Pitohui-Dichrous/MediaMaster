#!/usr/bin/env python3
"""Summarize validated optical benchmark collections; Python standard library only."""

import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
from statistics import median
import sys


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def interval(record, prefix):
    lower, upper = record.get(prefix + "LowerMs"), record.get(prefix + "UpperMs")
    if lower is None and upper is None:
        return None
    if not number(lower) or not number(upper) or lower < 0 or upper < lower:
        raise ValueError(f"Invalid {prefix} observation interval")
    return [lower, upper]


def summarize_intervals(values, eligible_count):
    observed = [value for value in values if value is not None]
    return {
        "eligible_count": eligible_count,
        "observed_count": len(observed),
        "unobserved_count": eligible_count - len(observed),
        "median_lower_ms": median(value[0] for value in observed) if observed else None,
        "median_upper_ms": median(value[1] for value in observed) if observed else None,
        "intervals_ms": observed,
    }


def validate_timeline(timeline):
    if not isinstance(timeline, list) or not timeline:
        raise ValueError("No validated optical frame timeline")
    previous_end = -math.inf
    for sample in timeline:
        start, end, pts = (sample.get(key) for key in ("captureStartMs", "captureEndMs", "frameMs"))
        if not all(number(value) for value in (start, end, pts)):
            raise ValueError("Non-numeric optical sample")
        if end < start or start < previous_end or pts < 0 or pts >= 30000 or pts % 40:
            raise ValueError("Invalid optical sample ordering or B2 frame PTS")
        previous_end = end


def anchored_trajectory(record, timeline, target, tolerance, offset=0):
    bounds = interval(record, "finalConsistentTrajectoryFirst")
    if bounds is None:
        return None
    duration = record.get("finalConsistentTrajectoryDurationMs")
    if not number(duration) or duration < 300:
        return None
    # This gate uses actual picture PTS, never a time label or implied clock advancement.
    # A phase-consistent suffix alone can describe the wrong video position.
    suffix = [sample for sample in timeline if sample["captureEndMs"] - offset >= bounds[1]]
    if not any(abs(sample["frameMs"] - target) <= tolerance for sample in suffix):
        return None
    return bounds


def summarize_run(data, artifact):
    kind = "isolated" if "rows" in data else "burst"
    state = data.get("state")
    if state not in ("playing", "paused"):
        raise ValueError("Unknown playback state")
    if kind == "isolated":
        if data.get("collectionCompleted") is not True:
            raise ValueError("Collection incomplete; interrupted/process-crash artifacts are not latency evidence")
        aggregate_failure = (
            state == "paused"
            and data.get("completed") is False
            and str(data.get("error", "")).startswith("java.lang.AssertionError: All targets were collected;")
        )
        if data.get("completed") is not True and not aggregate_failure:
            raise ValueError("Collection has an unexpected harness failure")
        records = data.get("rows", [])
        if not records or [row.get("trial") for row in records] != list(range(len(records))):
            raise ValueError("Missing or unordered trial rows")
    else:
        if data.get("completed") is not True:
            raise ValueError("Burst did not complete successfully")
        records = data.get("taps", [])
        if len(records) != 6 or [row.get("index") for row in records] != list(range(6)):
            raise ValueError("A burst needs six complete taps")
        if any(not number(row.get("downMs")) or not number(row.get("upMs"))
               or row["upMs"] < row["downMs"] for row in records):
            raise ValueError("Burst input sequence incomplete")
    if data.get("actualFixture") != data.get("file") or not data.get("fixtureIdentitySource"):
        raise ValueError("Fixture identity was not verified")
    if data.get("physicalTouchCalibration") is not True or data.get("calibration", {}).get("validated") is not True:
        raise ValueError("Not a validated physical-touch calibration run")
    if data.get("originReset", {}).get("verifiedPaused") is not True:
        raise ValueError("Common paused origin was not verified")
    if data.get("protocolDurationMs") != 30000 or data.get("frameRate") != 25:
        raise ValueError("Unknown fixture protocol")
    tolerance = data.get("targetToleranceMs")
    if not number(tolerance) or tolerance < 0:
        raise ValueError("Missing accuracy threshold")
    targets = [row.get("requestedMs") for row in records]
    if any(not number(target) or target < 0 or target >= 30000 or target % 40 for target in targets):
        raise ValueError("Targets are not valid frame-aligned fixture timestamps")
    for row in records:
        if not number(row.get("inputQuantizationErrorMs")) or abs(row["inputQuantizationErrorMs"]) >= 40:
            raise ValueError("Unbounded physical-input quantization")
    near, first_jump, stable, trajectory, target_errors, input_errors = [], [], [], [], [], []
    unknown_accuracy = 0
    if kind == "isolated":
        for row in records:
            validate_timeline(row.get("timeline"))
            near.append(interval(row, "nearTarget"))
            first_jump.append(interval(row, "firstJump"))
            if state == "paused":
                bounds = interval(row, "finalStableFirst")
                if bounds is not None:
                    if row.get("finalStableDurationMs", 0) < 300 or row["timeline"][-1]["captureEndMs"] < 1000:
                        raise ValueError("Paused landing lacks required observation/stability window")
                    landing = row["timeline"][-1]["frameMs"]
                    suffix = [sample for sample in row["timeline"] if sample["captureEndMs"] >= bounds[1]]
                    if (row.get("finalStableFrameMs") != landing or len(suffix) < 3
                            or suffix[0]["captureEndMs"] != bounds[1]
                            or any(sample["frameMs"] != landing for sample in suffix)
                            or suffix[-1]["captureEndMs"] - suffix[0]["captureEndMs"] < 300):
                        raise ValueError("Stable landing summary does not match the actual frame timeline")
                    target_errors.append(landing - row["requestedMs"])
                    input_errors.append(landing - row["inferredInputRequestMs"])
                else:
                    unknown_accuracy += 1
                stable.append(bounds)
            else:
                trajectory.append(anchored_trajectory(row, row["timeline"], row["requestedMs"], tolerance))
        eligible = len(records)
    else:
        timeline = data.get("timeline")
        validate_timeline(timeline)
        last = records[-1]
        near.append(interval(data, "nearLastTarget"))
        first_jump.append(interval(data, "firstChangeAfterLast"))
        if state == "paused":
            bounds = interval(data, "finalStableFirst")
            stable.append(bounds)
            if bounds is not None:
                target_errors.append(data["finalStableFrameMs"] - last["requestedMs"])
                input_errors.append(data["finalStableFrameMs"] - last["inferredInputRequestMs"])
            else:
                unknown_accuracy += 1
        else:
            trajectory.append(anchored_trajectory(data, timeline, last["requestedMs"], tolerance, last["downMs"]))
        eligible = 1
    runtime = data.get("nativeRuntime", {})
    runtime_paths = set()
    for owner in [data] + (records if kind == "isolated" else []):
        for key in ("nativeRuntime", "nativeRuntimeAfter"):
            value = owner.get(key)
            if isinstance(value, dict):
                runtime_paths.add((value.get("nativeSoftware"), value.get("surfaceType")))
    summary = {
        "run": data.get("run", artifact.parent.name), "artifact": str(artifact),
        "kind": kind, "state": state, "file": data["file"], "package": data.get("package"),
        "actual_engine": data.get("actualEngine"), "native_runtime": runtime,
        "observed_native_paths": [{"native_software": path[0], "surface_type": path[1]}
                                  for path in sorted(runtime_paths, key=str)],
        "native_path_changed": len(runtime_paths) > 1,
        "model": data.get("model"), "sdk": data.get("sdk"),
        "actual_targets_ms": targets, "target_tolerance_ms": tolerance,
        "collection_completed": True, "harness_assertions_passed": data.get("completed") is True,
        "first_picture_change": summarize_intervals(first_jump, eligible),
        "first_target_picture": summarize_intervals(near, eligible),
    }
    if state == "paused":
        summary["stable_landing"] = summarize_intervals(stable, eligible)
        summary["paused_accuracy"] = {
            "assessed_count": len(target_errors), "unassessed_count": unknown_accuracy,
            "outside_tolerance_count": sum(abs(error) > tolerance for error in target_errors),
            "signed_target_errors_ms": target_errors, "signed_input_errors_ms": input_errors,
            "median_signed_target_error_ms": median(target_errors) if target_errors else None,
            "median_absolute_target_error_ms": median(abs(error) for error in target_errors) if target_errors else None,
        }
    else:
        summary["target_anchored_consistent_trajectory"] = summarize_intervals(trajectory, eligible)
        summary["paused_accuracy"] = None
    summary["comparison_key"] = {
        "kind": kind, "state": state, "file": data["file"], "model": data.get("model"),
        "sdk": data.get("sdk"), "actual_targets_ms": targets, "target_tolerance_ms": tolerance,
        "protocol": "B2-calibrated-origin400-25fps",
        "paused_minimum_observation_ms": data.get("pausedMinimumObservationMs") if state == "paused" else None,
        "paused_stability_window_ms": data.get("pausedStableWindowMs") if state == "paused" else None,
    }
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path, help="results.json files or directories to search")
    parser.add_argument("--exclude-run", action="append", default=[], help="Explicitly exclude a known contaminated run")
    parser.add_argument("--output", type=Path, help="Write JSON to a new file instead of stdout")
    args = parser.parse_args()
    paths = set()
    for path in args.paths:
        if path.is_dir():
            paths.update(p.resolve() for p in path.rglob("results.json"))
        else:
            paths.add(path.resolve())
    included, excluded = [], []
    for path in sorted(paths):
        run = path.parent.name
        try:
            data = json.loads(path.read_text(encoding="utf-8-sig"))
            run = data.get("run", run)
            if run in args.exclude_run:
                raise ValueError("Explicitly excluded as contaminated; no product-crash inference")
            included.append(summarize_run(data, path))
        except (OSError, ValueError, TypeError, KeyError) as error:
            excluded.append({"run": run, "artifact": str(path), "reason": str(error)})
    groups = defaultdict(list)
    for run in included:
        groups[json.dumps(run["comparison_key"], sort_keys=True)].append(run["run"])
    report = {
        "schema": "optical-seek-summary-v1", "included": included, "excluded": excluded,
        "comparable_target_groups": [{"key": json.loads(key), "runs": names} for key, names in groups.items()],
        "interpretation": [
            "Median lower/upper values bound sampled observations; they are not confidence intervals.",
            "Stable landing latency and signed paused accuracy are independent; a fast wrong landing is not a pass.",
            "Playing trajectory onset is a retrospective120ms phase-envelope diagnostic, not exact smoothness or zero-stutter evidence.",
            "Trajectory values require a target-near frame in the suffix; unanchored suffixes remain unobserved.",
            "Burst first change after finalDOWN may belong to an earlier queued request.",
            "Compare actual target sequences, not supplied fractions; matching file names do not independently prove equal file hashes.",
            "Excluded interrupted/process-crash collections do not establish a product crash or decoder latency.",
        ],
    }
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(encoded)
    else:
        print(encoded, end="")
    return 0 if included else 1


if __name__ == "__main__":
    sys.exit(main())
