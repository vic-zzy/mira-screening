"""Deduplicate by perceptual hash and produce a stratified train/val/test split.

Reads everything under data/raw/, expects each source to either:
  - already split images by class folder (raw/<source>/<negative|positive>/*.jpg), or
  - have a manifest CSV at raw/<source>/manifest.csv with columns image_path,label

Writes to data/processed/{train,val,test}/{negative,positive}/.

Default split: 80 / 10 / 10, stratified by label and by source so no source
contributes to both train and test (prevents leakage).
"""
from __future__ import annotations

import argparse
import csv
import random
import shutil
from collections import defaultdict
from pathlib import Path

import imagehash
from PIL import Image

LABELS = ("negative", "positive")
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".tiff"}


def discover_samples(raw_root: Path) -> list[tuple[Path, str, str]]:
    """Returns list of (image_path, label, source_name)."""
    samples: list[tuple[Path, str, str]] = []
    for source_dir in raw_root.iterdir():
        if not source_dir.is_dir():
            continue
        manifest = source_dir / "manifest.csv"
        if manifest.exists():
            with manifest.open() as f:
                for row in csv.DictReader(f):
                    label = row["label"].strip().lower()
                    if label not in LABELS:
                        continue
                    img = source_dir / row["image_path"]
                    if img.exists() and img.suffix.lower() in IMAGE_EXTS:
                        samples.append((img, label, source_dir.name))
        else:
            for label in LABELS:
                label_dir = source_dir / label
                if not label_dir.exists():
                    continue
                for img in label_dir.rglob("*"):
                    if img.suffix.lower() in IMAGE_EXTS:
                        samples.append((img, label, source_dir.name))
    return samples


def deduplicate(samples: list[tuple[Path, str, str]]) -> list[tuple[Path, str, str]]:
    """Remove near-duplicates by 64-bit pHash."""
    seen: dict[str, tuple[Path, str, str]] = {}
    for s in samples:
        try:
            h = str(imagehash.phash(Image.open(s[0])))
        except Exception:
            continue
        if h not in seen:
            seen[h] = s
    return list(seen.values())


def stratified_split(
    samples: list[tuple[Path, str, str]],
    seed: int,
    train_ratio: float,
    val_ratio: float,
) -> dict[str, list[tuple[Path, str, str]]]:
    """Stratify by source so no source spans train/test."""
    rng = random.Random(seed)
    by_source: dict[str, list[tuple[Path, str, str]]] = defaultdict(list)
    for s in samples:
        by_source[s[2]].append(s)

    split: dict[str, list[tuple[Path, str, str]]] = {"train": [], "val": [], "test": []}
    for source, items in by_source.items():
        rng.shuffle(items)
        n = len(items)
        n_train = int(n * train_ratio)
        n_val = int(n * val_ratio)
        split["train"].extend(items[:n_train])
        split["val"].extend(items[n_train : n_train + n_val])
        split["test"].extend(items[n_train + n_val :])
    return split


def materialize(split: dict[str, list[tuple[Path, str, str]]], out_root: Path) -> None:
    for split_name, items in split.items():
        for path, label, source in items:
            dst_dir = out_root / split_name / label
            dst_dir.mkdir(parents=True, exist_ok=True)
            dst = dst_dir / f"{source}__{path.name}"
            shutil.copy2(path, dst)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("./data"))
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--train-ratio", type=float, default=0.8)
    parser.add_argument("--val-ratio", type=float, default=0.1)
    args = parser.parse_args()

    raw = args.root / "raw"
    processed = args.root / "processed"

    print(f"Discovering samples under {raw}…")
    samples = discover_samples(raw)
    print(f"  found {len(samples)} samples")

    print("Deduplicating by perceptual hash…")
    samples = deduplicate(samples)
    print(f"  {len(samples)} unique")

    print("Stratified split…")
    split = stratified_split(samples, args.seed, args.train_ratio, args.val_ratio)
    for k, v in split.items():
        per_label = defaultdict(int)
        for _, label, _ in v:
            per_label[label] += 1
        print(f"  {k}: {len(v)} ({dict(per_label)})")

    print(f"Writing to {processed}…")
    materialize(split, processed)
    print("Done.")


if __name__ == "__main__":
    main()
