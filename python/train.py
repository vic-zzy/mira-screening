"""Fine-tune a vision foundation model on aggregated cervical VIA data.

Default backbone: DINOv2-base (Meta AI, self-supervised vision transformer).
Phase-2 will evaluate MedSAM and BiomedCLIP for potentially higher data
efficiency on medical imaging.

Run:
    python train.py --data ./data/processed --epochs 30 --batch-size 32 \
                    --backbone dinov2_vitb14 --output ./checkpoints/baseline
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import torch
import torch.nn as nn
from torch.utils.data import DataLoader, WeightedRandomSampler
from tqdm import tqdm

from datasets.augmentation import eval_transform, train_transform
from datasets.loader import LABELS, CervicalViaDataset


def build_model(backbone: str, num_classes: int = 2) -> nn.Module:
    if backbone.startswith("dinov2_"):
        # DINOv2 from torch.hub. Possible: dinov2_vits14, dinov2_vitb14, dinov2_vitl14, dinov2_vitg14
        encoder = torch.hub.load("facebookresearch/dinov2", backbone)
        embed_dim = encoder.embed_dim
    elif backbone == "vit_b_16":
        from torchvision.models import ViT_B_16_Weights, vit_b_16
        encoder = vit_b_16(weights=ViT_B_16_Weights.IMAGENET1K_V1)
        # strip classifier; use the CLS token output
        encoder.heads = nn.Identity()
        embed_dim = 768
    else:
        raise ValueError(f"Unknown backbone: {backbone}")

    head = nn.Sequential(
        nn.LayerNorm(embed_dim),
        nn.Linear(embed_dim, num_classes),
    )

    class FoundationClassifier(nn.Module):
        def __init__(self, encoder: nn.Module, head: nn.Module) -> None:
            super().__init__()
            self.encoder = encoder
            self.head = head

        def forward(self, x: torch.Tensor) -> torch.Tensor:
            features = self.encoder(x)
            if isinstance(features, dict):
                features = features.get("x_norm_clstoken", features["x_norm_patchtokens"][:, 0])
            return self.head(features)

    return FoundationClassifier(encoder, head)


class ClassifierWithSaliency(nn.Module):
    """Wrap a CLS-classifier so its forward returns BOTH logits AND a per-patch
    saliency map in a single forward pass.

    Saliency definition (V1.1): L2 norm of final patch token features, min-max
    normalized to [0, 1] per image. Patches with higher activation magnitude
    are the regions the classifier draws its signal from.

    This is "feature magnitude saliency", less rigorous than full attention
    rollout across all transformer blocks, but:
      - robust across DINOv2 versions (no monkey-patching attention layers)
      - one extra ``norm`` op, no autograd, converts cleanly to TFLite
      - visually plausible, high-activation regions are where the model looks

    V2 upgrades to attention rollout once we control more of the model
    internals. Tracked in BACKLOG.md.
    """

    def __init__(self, classifier: nn.Module) -> None:
        super().__init__()
        self.encoder = classifier.encoder
        self.head = classifier.head

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        out = self.encoder.forward_features(x)
        cls_features = out["x_norm_clstoken"]
        patch_features = out["x_norm_patchtokens"]  # [B, num_patches, embed_dim]

        logits = self.head(cls_features)

        # Per-patch L2 norm, then min-max normalize per image.
        saliency = patch_features.norm(dim=-1)  # [B, num_patches]
        sal_min = saliency.amin(dim=1, keepdim=True)
        sal_max = saliency.amax(dim=1, keepdim=True)
        saliency = (saliency - sal_min) / (sal_max - sal_min + 1e-6)

        # Reshape to a square grid (e.g. 16x16 for DINOv2-S/14 at 224 input).
        side = int(saliency.shape[1] ** 0.5)
        saliency = saliency.view(saliency.shape[0], side, side)

        return logits, saliency


def pick_device() -> torch.device:
    """Pick the best available device: CUDA > MPS (Apple Silicon) > CPU."""
    if torch.cuda.is_available():
        return torch.device("cuda")
    if torch.backends.mps.is_available() and torch.backends.mps.is_built():
        return torch.device("mps")
    return torch.device("cpu")


def class_balanced_sampler(dataset: CervicalViaDataset) -> WeightedRandomSampler:
    counts = [0] * len(LABELS)
    for _, label in dataset.samples:
        counts[label] += 1
    weights = [1.0 / counts[label] for _, label in dataset.samples]
    return WeightedRandomSampler(weights, num_samples=len(weights), replacement=True)


def run_epoch(
    model: nn.Module,
    loader: DataLoader,
    optimizer: torch.optim.Optimizer | None,
    criterion: nn.Module,
    device: torch.device,
    desc: str,
) -> tuple[float, float]:
    is_train = optimizer is not None
    model.train(is_train)
    total_loss = 0.0
    correct = 0
    seen = 0
    for imgs, labels in tqdm(loader, desc=desc):
        imgs = imgs.to(device, non_blocking=True)
        labels = labels.to(device, non_blocking=True)
        with torch.set_grad_enabled(is_train):
            logits = model(imgs)
            loss = criterion(logits, labels)
            if is_train:
                optimizer.zero_grad()
                loss.backward()
                optimizer.step()
        total_loss += loss.item() * imgs.size(0)
        correct += (logits.argmax(1) == labels).sum().item()
        seen += imgs.size(0)
    return total_loss / max(seen, 1), correct / max(seen, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--weight-decay", type=float, default=1e-4)
    parser.add_argument("--image-size", type=int, default=224)
    parser.add_argument("--backbone", default="dinov2_vitb14")
    parser.add_argument("--output", type=Path, default=Path("./checkpoints"))
    parser.add_argument("--num-workers", type=int, default=4)
    parser.add_argument("--freeze-backbone", action="store_true")
    args = parser.parse_args()

    device = pick_device()
    args.output.mkdir(parents=True, exist_ok=True)
    print(f"Device: {device}")
    if device.type == "mps":
        print("  (Apple Silicon GPU, slower than CUDA but workable for V1 baseline)")
    print(f"Args: {vars(args)}")

    train_ds = CervicalViaDataset(args.data, "train", train_transform(args.image_size))
    val_ds = CervicalViaDataset(args.data, "val", eval_transform(args.image_size))
    print(f"Train: {len(train_ds)}  Val: {len(val_ds)}")

    train_loader = DataLoader(
        train_ds,
        batch_size=args.batch_size,
        sampler=class_balanced_sampler(train_ds),
        num_workers=args.num_workers,
        pin_memory=True,
    )
    val_loader = DataLoader(
        val_ds,
        batch_size=args.batch_size,
        shuffle=False,
        num_workers=args.num_workers,
        pin_memory=True,
    )

    model = build_model(args.backbone).to(device)
    if args.freeze_backbone:
        for p in model.encoder.parameters():
            p.requires_grad = False

    trainable = [p for p in model.parameters() if p.requires_grad]
    optimizer = torch.optim.AdamW(trainable, lr=args.lr, weight_decay=args.weight_decay)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=args.epochs)
    criterion = nn.CrossEntropyLoss(label_smoothing=0.05)

    best_acc = 0.0
    history: list[dict] = []
    started = time.time()
    for epoch in range(args.epochs):
        train_loss, train_acc = run_epoch(model, train_loader, optimizer, criterion, device, f"train e{epoch}")
        val_loss, val_acc = run_epoch(model, val_loader, None, criterion, device, f"val   e{epoch}")
        scheduler.step()
        print(
            f"epoch {epoch}: "
            f"train_loss={train_loss:.4f} train_acc={train_acc:.4f}  "
            f"val_loss={val_loss:.4f} val_acc={val_acc:.4f}"
        )
        history.append(
            dict(epoch=epoch, train_loss=train_loss, train_acc=train_acc, val_loss=val_loss, val_acc=val_acc)
        )
        if val_acc > best_acc:
            best_acc = val_acc
            torch.save(
                {"model": model.state_dict(), "args": vars(args)},
                args.output / "best.pt",
            )
            print(f"  saved best (val_acc={val_acc:.4f})")

    elapsed = time.time() - started
    (args.output / "history.json").write_text(json.dumps(history, indent=2, default=str))
    print(f"Done in {elapsed/60:.1f} min. Best val_acc={best_acc:.4f}")


if __name__ == "__main__":
    main()
