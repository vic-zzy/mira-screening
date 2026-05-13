"""Export a trained PyTorch checkpoint to TFLite for on-device Android inference.

Uses Google's litert-torch (the renamed ai-edge-torch, same team, same API).
The exported model returns TWO tensors per forward:
  - logits:   [1, 2]      classification logits (negative, positive)
  - saliency: [1, S, S]   per-patch saliency map (e.g. 16x16 for DINOv2-S/14)

Android's TfLiteClassifier reads both and feeds the saliency into the heatmap
overlay on the Result screen.

Inputs are channel-last (NHWC) [1, 224, 224, 3] to match the byte layout the
Android side produces in TfLiteClassifier.preprocess().

Run:
    pip install litert-torch  # one-time
    python export_tflite.py --checkpoint ./checkpoints/baseline/best.pt \\
                            --output ../app/src/main/assets/via_model.tflite
"""
from __future__ import annotations

import argparse
from pathlib import Path

import torch

from train import ClassifierWithSaliency, build_model


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--image-size", type=int, default=224)
    parser.add_argument(
        "--no-saliency",
        action="store_true",
        help="export only the classification head (drops the saliency output)",
    )
    args = parser.parse_args()

    ckpt = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    backbone = ckpt["args"]["backbone"]
    model = build_model(backbone)
    model.load_state_dict(ckpt["model"])
    model.eval()

    if not args.no_saliency:
        print("Wrapping model with saliency-output head…")
        model = ClassifierWithSaliency(model)

    # litert-torch import is local so the rest of the training pipeline doesn't
    # need to install it.
    import litert_torch

    print("Wrapping model with channel-last (NHWC) IO helper…")
    model_nhwc = litert_torch.to_channel_last_io(model, args=[0])

    sample_nhwc = torch.randn(1, args.image_size, args.image_size, 3)

    print("Converting PyTorch -> TFLite via litert-torch…")
    edge_model = litert_torch.convert(model_nhwc.eval(), (sample_nhwc,))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    edge_model.export(str(args.output))

    size_mb = args.output.stat().st_size / (1024 * 1024)
    print(f"\nWrote {args.output}  ({size_mb:.1f} MB)")
    if not args.no_saliency:
        print("Output 0 = logits [1, 2];  Output 1 = saliency [1, S, S]")
    print("Drop-in replacement for the existing model file. Rebuild the APK.")


if __name__ == "__main__":
    main()
