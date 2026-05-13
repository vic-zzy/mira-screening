"""PyTorch Dataset for aggregated cervical VIA images.

On-disk structure expected after aggregator + label_split:
  data/processed/
    train/
      negative/*.jpg
      positive/*.jpg
    val/
      negative/*.jpg
      positive/*.jpg
    test/
      negative/*.jpg
      positive/*.jpg
"""
from pathlib import Path
from typing import Callable, Optional

import numpy as np
from PIL import Image
from torch.utils.data import Dataset

LABELS = ("negative", "positive")


class CervicalViaDataset(Dataset):
    def __init__(
        self,
        root: Path,
        split: str,
        transform: Optional[Callable] = None,
    ):
        self.samples: list[tuple[Path, int]] = []
        for label_idx, label_name in enumerate(LABELS):
            label_dir = Path(root) / split / label_name
            if not label_dir.exists():
                continue
            for ext in ("*.jpg", "*.jpeg", "*.png"):
                for img in label_dir.glob(ext):
                    self.samples.append((img, label_idx))
        if not self.samples:
            raise RuntimeError(
                f"No samples found under {root}/{split}/. "
                f"Run aggregator.py and label_split.py first."
            )
        self.transform = transform

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, idx: int):
        path, label = self.samples[idx]
        # albumentations expects np.ndarray in HWC RGB
        img = np.array(Image.open(path).convert("RGB"))
        if self.transform is not None:
            img = self.transform(image=img)["image"]
        return img, label
