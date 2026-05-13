"""Aggregate public cervical VIA datasets into data/raw/.

Sources covered:
  Public, no auth:
    - Direct URL downloads (Mendeley, Zenodo, IARC public tier), see DIRECT_URL_SOURCES
    - Hugging Face Hub datasets, see HF_DATASETS

  Auth-required:
    - Kaggle Intel & MobileODT 2017, needs KAGGLE_USERNAME + KAGGLE_KEY

  Partnership-only (manual download, drop into data/raw/manual/):
    - IARC full image bank (institutional MOU)
    - NCI Hu et al. credentialed access
    - MobileODT proprietary

Run:
    python -m datasets.aggregator --out ./data --sources auto

Then run label_split.py to produce data/processed/{train,val,test}/{negative,positive}/.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import urllib.request
from pathlib import Path
from typing import Optional

# Verified by the bio-research literature pass (see python/DATA_PLAN.md).
# Triple is (name, url, expected_label_or_None). label=None means manual labeling
# required after download (most cervical datasets ship with patient-level labels
# rather than image-level negative/positive, label_split.py handles this).
DIRECT_URL_SOURCES: list[tuple[str, str, Optional[str]]] = [
    # Mendeley: "A deep learning mobile-based image analysis for cervical cancer
    # detection", VIA-style cervical images. Confirm license + class layout
    # before plugging into label_split.py.
    (
        "mendeley_mobile_via",
        "https://data.mendeley.com/datasets/hwmpww97rs/1",
        None,
    ),
    # Mendeley: "cervical cancer", generic dataset, verify modality + labels
    # before training (may be cytology, may be VIA).
    (
        "mendeley_cervical_generic",
        "https://data.mendeley.com/datasets/76zk3dpyxx/1",
        None,
    ),
]

HF_DATASETS: list[str] = [
    # Cervical VIA pre/post-acetic-acid image pairs, expert-validated to
    # 100% nurse/gynecologist agreement, anonymized. Closest direct fit
    # for the Mira training task on Hugging Face Hub.
    "emergentai/cervical-cancer-via-images",
]


def download_kaggle_intel_mobileodt(out: Path) -> None:
    if not (os.getenv("KAGGLE_USERNAME") and os.getenv("KAGGLE_KEY")):
        print("[skip] kaggle: KAGGLE_USERNAME / KAGGLE_KEY not set")
        return
    out.mkdir(parents=True, exist_ok=True)
    cmd = [
        "kaggle",
        "competitions",
        "download",
        "-c",
        "intel-mobileodt-cervical-cancer-screening",
        "-p",
        str(out),
    ]
    print(f"[kaggle] {' '.join(cmd)}")
    subprocess.run(cmd, check=True)
    for archive in out.glob("*.zip"):
        print(f"  unpacking {archive.name}")
        shutil.unpack_archive(archive, out)


def download_hf_dataset(repo_id: str, out: Path) -> None:
    from huggingface_hub import snapshot_download

    out.mkdir(parents=True, exist_ok=True)
    print(f"[hf] {repo_id} -> {out}")
    snapshot_download(repo_id=repo_id, repo_type="dataset", local_dir=str(out))


def download_direct(name: str, url: str, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    target = out / Path(url).name
    print(f"[direct] {name}: {url}")
    urllib.request.urlretrieve(url, target)
    if target.suffix in {".zip", ".tar", ".tgz", ".gz"}:
        try:
            shutil.unpack_archive(target, out)
        except shutil.ReadError:
            pass


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=Path("./data"))
    parser.add_argument(
        "--sources",
        default="auto",
        help="comma-separated subset of: kaggle,hf,direct (or 'auto' for all)",
    )
    args = parser.parse_args()

    raw = args.out / "raw"
    raw.mkdir(parents=True, exist_ok=True)

    sources = (
        ["kaggle", "hf", "direct"]
        if args.sources == "auto"
        else [s.strip() for s in args.sources.split(",")]
    )

    # One failing source must not block the others. Each source is wrapped so
    # that auth failures, gated-repo errors, network blips, etc. surface as
    # warnings without crashing the whole aggregation run.
    failures: list[tuple[str, str]] = []

    def attempt(label: str, fn) -> None:
        try:
            fn()
        except Exception as e:  # noqa: BLE001, intentionally broad at top level
            failures.append((label, f"{type(e).__name__}: {e}"))
            print(f"[warn] {label} failed: {type(e).__name__}: {e}")
            print(f"[warn] continuing with remaining sources")

    if "kaggle" in sources:
        attempt("kaggle", lambda: download_kaggle_intel_mobileodt(raw / "kaggle_intel_mobileodt"))

    if "hf" in sources:
        if not HF_DATASETS:
            print("[hf] HF_DATASETS list is empty, add repo IDs to enable")
        for repo_id in HF_DATASETS:
            safe_name = repo_id.replace("/", "__")
            attempt(f"hf:{repo_id}", lambda r=repo_id, s=safe_name: download_hf_dataset(r, raw / f"hf_{s}"))

    if "direct" in sources:
        if not DIRECT_URL_SOURCES:
            print("[direct] DIRECT_URL_SOURCES is empty, add (name, url, label) tuples to enable")
        for name, url, _ in DIRECT_URL_SOURCES:
            attempt(f"direct:{name}", lambda n=name, u=url: download_direct(n, u, raw / n))

    print()
    print(f"Raw data is in {raw}")
    if failures:
        print(f"\n[!] {len(failures)} source(s) failed:")
        for label, msg in failures:
            print(f"    - {label}: {msg}")
        print("Re-run with --sources <label> after fixing each.")
    print("\nNext: run python -m datasets.label_split to produce data/processed/")


if __name__ == "__main__":
    main()
