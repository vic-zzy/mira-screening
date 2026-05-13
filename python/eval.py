"""Evaluate a trained checkpoint on a holdout split.

Reports the metrics that matter for clinical screening evidence:
  - AUC (ranking performance)
  - Sensitivity / Specificity at the chosen operating threshold
  - Cohen's kappa (vs gold-standard label)
  - Confusion matrix
  - Full classification report

Run:
    python eval.py --checkpoint ./checkpoints/best.pt --data ./data/processed --split test
"""
from __future__ import annotations

import argparse
from pathlib import Path

import torch
from sklearn.metrics import (
    classification_report,
    cohen_kappa_score,
    confusion_matrix,
    roc_auc_score,
)
from torch.utils.data import DataLoader

from datasets.augmentation import eval_transform
from datasets.loader import CervicalViaDataset
from train import build_model


@torch.no_grad()
def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--split", default="test")
    parser.add_argument("--threshold", type=float, default=0.5)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--image-size", type=int, default=224)
    parser.add_argument("--num-workers", type=int, default=4)
    args = parser.parse_args()

    from train import pick_device
    device = pick_device()
    # weights_only=False is safe here, we wrote the checkpoint ourselves.
    # PyTorch 2.6 default flipped to True, which rejects pathlib.PosixPath
    # (stored in our args dict) as an unsafe global.
    ckpt = torch.load(args.checkpoint, map_location=device, weights_only=False)
    backbone = ckpt["args"]["backbone"]

    model = build_model(backbone).to(device)
    model.load_state_dict(ckpt["model"])
    model.eval()

    ds = CervicalViaDataset(args.data, args.split, eval_transform(args.image_size))
    loader = DataLoader(ds, batch_size=args.batch_size, shuffle=False, num_workers=args.num_workers)

    probs: list[float] = []
    preds: list[int] = []
    labels: list[int] = []
    for imgs, lbls in loader:
        logits = model(imgs.to(device))
        p_pos = torch.softmax(logits, dim=1)[:, 1].cpu().tolist()
        probs.extend(p_pos)
        preds.extend([1 if p >= args.threshold else 0 for p in p_pos])
        labels.extend(lbls.tolist())

    n = len(labels)
    auc = roc_auc_score(labels, probs)
    cm = confusion_matrix(labels, preds, labels=[0, 1])
    tn, fp, fn, tp = cm.ravel()
    sens = tp / (tp + fn) if tp + fn else 0.0
    spec = tn / (tn + fp) if tn + fp else 0.0
    kappa = cohen_kappa_score(labels, preds)

    print(f"N           : {n}")
    print(f"AUC         : {auc:.4f}")
    print(f"Sensitivity : {sens:.4f}  (operating threshold {args.threshold})")
    print(f"Specificity : {spec:.4f}")
    print(f"Cohen kappa : {kappa:.4f}")
    print()
    print("Confusion matrix (rows=true, cols=pred)")
    print(f"            negative  positive")
    print(f"  negative  {tn:8d}  {fp:8d}")
    print(f"  positive  {fn:8d}  {tp:8d}")
    print()
    print(classification_report(labels, preds, target_names=["negative", "positive"], digits=4))


if __name__ == "__main__":
    main()
