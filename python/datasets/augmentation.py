"""Augmentation pipelines for cervical VIA training.

Designed to mimic the conditions an LMIC clinic camera produces:
  - mild rotation / flip (different speculum + camera angles)
  - moderate color jitter (variable lighting and white balance)
  - gaussian blur and noise (handheld phone capture, low-light noise)
  - random crop (varying field-of-view)
"""
import albumentations as A
from albumentations.pytorch import ToTensorV2

# ImageNet stats, used because foundation backbones (DINOv2, ViT) are
# pretrained on ImageNet-scale data normalized this way.
IMAGENET_MEAN = (0.485, 0.456, 0.406)
IMAGENET_STD = (0.229, 0.224, 0.225)


def train_transform(image_size: int = 224):
    return A.Compose(
        [
            A.RandomResizedCrop(size=(image_size, image_size), scale=(0.7, 1.0)),
            A.HorizontalFlip(p=0.5),
            A.RandomRotate90(p=0.5),
            A.ColorJitter(brightness=0.15, contrast=0.15, saturation=0.10, hue=0.04, p=0.7),
            A.GaussianBlur(blur_limit=(3, 5), p=0.3),
            A.GaussNoise(var_limit=(8.0, 24.0), p=0.3),
            A.Normalize(mean=IMAGENET_MEAN, std=IMAGENET_STD),
            ToTensorV2(),
        ]
    )


def eval_transform(image_size: int = 224):
    return A.Compose(
        [
            A.SmallestMaxSize(max_size=image_size + 32),
            A.CenterCrop(height=image_size, width=image_size),
            A.Normalize(mean=IMAGENET_MEAN, std=IMAGENET_STD),
            ToTensorV2(),
        ]
    )
