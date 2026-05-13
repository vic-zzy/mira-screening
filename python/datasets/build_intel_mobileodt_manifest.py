"""Build a manifest.csv mapping Intel/MobileODT TZ-type labels to binary
{negative, positive} for the V1 heuristic baseline.

The Intel/MobileODT 2017 Kaggle dataset labels images by cervix transformation-zone
(TZ) type, anatomical classification, NOT VIA-positive/negative. This script
applies a documented proxy mapping so we can train a V1 baseline today and swap
to true VIA labels in V2 once partnership data lands:

    Type_1 + Type_2 -> negative   (TZ visible on ectocervix or partially in canal
                                   with upper limit visible, typical anatomy)
    Type_3          -> positive   (TZ entirely in the canal, upper limit not
                                   visible, anatomy where lesions are harder to
                                   detect, often triggers referral)

This is a proxy, not a clinical truth. The reasoning is documented in DATA_PLAN.md
and will be cited explicitly in the prize submission.

Run:
    python -m datasets.build_intel_mobileodt_manifest \\
        --source-dir ./data/raw/kaggle_intel_mobileodt_224

Then run label_split.py to dedup and split into train/val/test.
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path

LABELED_FOLDERS: list[tuple[str, str]] = [
    ("kaggle/train/train/Type_1", "negative"),
    ("kaggle/train/train/Type_2", "negative"),
    ("kaggle/train/train/Type_3", "positive"),
    ("kaggle/additional_Type_1_v2/Type_1", "negative"),
    ("kaggle/additional_Type_2_v2/Type_2", "negative"),
    ("kaggle/additional_Type_3_v2/Type_3", "positive"),
]

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".tiff"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=Path("./data/raw/kaggle_intel_mobileodt_224"),
        help="root of the unzipped Intel/MobileODT dataset",
    )
    args = parser.parse_args()

    source = args.source_dir
    if not source.exists():
        raise SystemExit(f"Source dir does not exist: {source}")

    manifest_path = source / "manifest.csv"
    rows: list[dict[str, str]] = []
    counts = {"negative": 0, "positive": 0}

    for rel_folder, label in LABELED_FOLDERS:
        folder = source / rel_folder
        if not folder.exists():
            print(f"[skip] {folder} not found")
            continue
        for img in folder.iterdir():
            if img.suffix.lower() in IMAGE_EXTS:
                rel_path = img.relative_to(source)
                rows.append({"image_path": str(rel_path), "label": label})
                counts[label] += 1

    with manifest_path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["image_path", "label"])
        writer.writeheader()
        writer.writerows(rows)

    total = len(rows)
    imbalance = counts["negative"] / max(counts["positive"], 1)
    print(f"Wrote {manifest_path} ({total} entries)")
    print(f"  negative: {counts['negative']}")
    print(f"  positive: {counts['positive']}")
    print(f"  class imbalance: {imbalance:.2f}:1 (handled by class-balanced sampler in train.py)")


if __name__ == "__main__":
    main()
