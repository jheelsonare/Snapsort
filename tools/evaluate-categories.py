#!/usr/bin/env python3
"""Evaluate recorded, uncorrected SnapSort suggestions without reading image/text data."""
import argparse
import csv
import json
from pathlib import Path

CATEGORIES = ("STUDY", "RECEIPTS", "SHOPPING", "TICKETS", "CHATS", "OTHER")


def label(value):
    value = str(value).strip().upper()
    if value not in CATEGORIES:
        raise ValueError("expected and suggested must use the six SnapSort category names")
    return value


def evaluate(records):
    confusion = {actual: {predicted: 0 for predicted in CATEGORIES} for actual in CATEGORIES}
    total = correct = accepted = accepted_correct = 0
    for number, record in enumerate(records, 1):
        try:
            actual, predicted = label(record["expected"]), label(record["suggested"])
        except (KeyError, TypeError, ValueError):
            raise ValueError(f"Invalid category record at row {number}; expected and suggested are required") from None
        total += 1
        correct += actual == predicted
        accepted += predicted != "OTHER"
        accepted_correct += predicted != "OTHER" and actual == predicted
        confusion[actual][predicted] += 1
    if not total:
        raise ValueError("No category records were supplied")
    represented = [category for category in CATEGORIES if sum(confusion[category].values())]
    recalls = {
        category: confusion[category][category] / sum(confusion[category].values())
        for category in represented
    }
    return {
        "sample_size": total,
        "accuracy": correct / total,
        "accepted_suggestion_count": accepted,
        "accepted_suggestion_accuracy": accepted_correct / accepted if accepted else None,
        "other_or_needs_review_fraction": (total - accepted) / total,
        "macro_recall_represented_categories": sum(recalls.values()) / len(recalls),
        "per_category_recall": recalls,
        "confusion_actual_rows_suggested_columns": confusion,
        "note": "Describes this supplied sample only; no population accuracy or probability calibration is implied.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="Local CSV or JSONL with expected and suggested category fields")
    parser.add_argument("--consent-confirmed", action="store_true", help="Confirm consent to evaluate the supplied screenshot-derived labels")
    parser.add_argument("--output", type=Path, help="Optional aggregate JSON output; otherwise stdout")
    args = parser.parse_args()
    if not args.consent_confirmed:
        parser.error("Pass --consent-confirmed only after obtaining consent for the supplied sample")
    try:
        with args.manifest.open(encoding="utf-8-sig", newline="") as source:
            if args.manifest.suffix.lower() == ".csv":
                records = list(csv.DictReader(source))
            else:
                records = [json.loads(line) for line in source if line.strip()]
        report = evaluate(records)
    except (OSError, ValueError, TypeError) as error:
        # Never include record contents, OCR, filenames, or paths from a screenshot sample.
        parser.exit(2, f"Could not evaluate manifest ({type(error).__name__}). Check its labels and format.\n")
    output = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(output, encoding="utf-8")
        print(f"Evaluated {report['sample_size']} records; aggregate report saved.")
    else:
        print(output, end="")


if __name__ == "__main__":
    main()
