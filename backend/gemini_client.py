"""Gemini Multimodal Client for PayPal Visual Cart.

Extracts canonical brand, product_name, category, colour, model, search_query,
specifications, confidence, recognition status, and disambiguation candidates
from real image bytes and optional clean text context.

Security & Anti-Hallucination Guarantees:
- API keys remain strictly server-side (`x-goog-api-key` header, never URL params).
- Validates actual image bytes, sniffs MIME type from magic bytes, and inspects dimensions.
- Strips capture filenames, timestamps (`capture_*`, `vc_img_*`, `shared image.jpg`), URIs,
  and cache paths before sending hints to the model or parser.
- Never falls back to fabricated prices, electronics defaults for industrial items, or fake SSDs.
"""

from __future__ import annotations

import asyncio
import base64
from collections import OrderedDict
import hashlib
import io
import json
import logging
import os
import re
import struct
import time
import uuid
from typing import Any, Dict, List, Optional, Tuple

import httpx

logger = logging.getLogger("visualcart.ai")

APPROVED_FREE_TIER_MODELS: Tuple[str, ...] = ("gemini-3.5-flash-lite",)
ACTIVE_GEMINI_MODELS: Tuple[str, ...] = APPROVED_FREE_TIER_MODELS
DEFAULT_GEMINI_MODEL: str = APPROVED_FREE_TIER_MODELS[0]
RATE_LIMIT_MESSAGE: str = "Free AI limit reached. Try again later."
RATE_LIMIT_COOLDOWN_SECONDS: float = 60.0
MAX_IMAGE_INPUT_BYTES: int = 4 * 1024 * 1024
TARGET_IMAGE_MAX_DIM: int = 1024
TARGET_IMAGE_MAX_BYTES: int = 524_288
MAX_OUTPUT_TOKENS: int = 350


class FreeTierPolicyError(ValueError):
    """Raised when configuration violates Visual Cart's strict zero-billing free-tier policy."""


def _parse_bool_env(raw: Optional[str], default: bool = True) -> bool:
    if raw is None or not str(raw).strip():
        return default
    val = str(raw).strip().lower()
    if val in {"1", "true", "yes", "on"}:
        return True
    if val in {"0", "false", "no", "off"}:
        return False
    return default


def validate_free_tier_configuration(
    model_name: Optional[str] = None,
    free_tier_only: Optional[bool] = None,
    max_cost_usd: Optional[float] = None,
) -> Dict[str, Any]:
    """Validates AI settings against Visual Cart's strict $0 zero-billing policy.

    Rejects any paid model outside `APPROVED_FREE_TIER_MODELS`, non-zero spending caps,
    or attempts to disable `GEMINI_FREE_TIER_ONLY`.
    """
    resolved_model = (
        model_name if model_name is not None else os.getenv("GEMINI_MODEL", DEFAULT_GEMINI_MODEL)
    ).strip() or DEFAULT_GEMINI_MODEL

    resolved_free_only = (
        free_tier_only
        if free_tier_only is not None
        else _parse_bool_env(os.getenv("GEMINI_FREE_TIER_ONLY"), default=True)
    )

    if max_cost_usd is not None:
        resolved_max_cost = float(max_cost_usd)
    else:
        raw_cost = (os.getenv("AI_MAX_COST_USD", "0") or "0").strip()
        try:
            resolved_max_cost = float(raw_cost)
        except ValueError as exc:
            raise FreeTierPolicyError(f"Invalid AI_MAX_COST_USD value '{raw_cost}'; must be 0.") from exc

    if not resolved_free_only:
        raise FreeTierPolicyError(
            "GEMINI_FREE_TIER_ONLY must be true. Paid-tier inference is disabled for Visual Cart."
        )
    if resolved_max_cost > 0.0:
        raise FreeTierPolicyError(
            f"AI_MAX_COST_USD={resolved_max_cost} rejected; Visual Cart requires AI_MAX_COST_USD=0."
        )
    if resolved_model not in APPROVED_FREE_TIER_MODELS:
        raise FreeTierPolicyError(
            f"Model '{resolved_model}' is outside approved free-tier allowlist {APPROVED_FREE_TIER_MODELS}."
        )

    return {
        "model": resolved_model,
        "free_tier_only": True,
        "max_cost_usd": 0.0,
        "paid_fallback_disabled": True,
    }


def is_valid_api_key_format(api_key: Optional[str]) -> bool:
    """Checks whether a Gemini API key has a non-placeholder format (supports AQ.* and AIza* keys)."""
    if not api_key:
        return False
    cleaned = api_key.strip()
    if len(cleaned) < 12 or any(ch.isspace() for ch in cleaned):
        return False
    lower = cleaned.lower()
    if lower in {"your_gemini_api_key_here", "changeme", "placeholder", "none", "null"}:
        return False
    return True

_URI_OR_PATH_RE = re.compile(
    r"^(?:https?|content|file|data|intent|blob|javascript|android\.resource):|^/(?:storage|sdcard|data|cache|mnt|tmp|var|home|dcim|pictures|downloads?)/|^[A-Za-z]:\\",
    re.IGNORECASE,
)
_MEDIA_EXT_RE = re.compile(
    r"^.+\.(?:jpe?g|png|webp|gif|bmp|heic|heif|avif|tiff?|svg|raw|dng|mp4|mov|mkv|pdf|tmp|bin)(?:[?#].*)?$",
    re.IGNORECASE,
)
_CAPTURE_STEM_RE = re.compile(
    r"^(?:vc[\s_-]*img[\s_-]*[0-9a-f-]+|(?:capture|vc[\s_-]*img|shared[\s_-]*image|share|img|pxl|screenshot|screen[\s_-]*shot|photo|pic|picture|media|dcim|dsc|vid|snap|wa|received|attachment|tmp|temp)(?:[\s_-]*\d+|[\s_-]*\(\d+\)|[\s_-]*copy|[\s_-]*edited|[\s_-]*thumb)*)$",
    re.IGNORECASE,
)
_EMBEDDED_CAPTURE_PHRASE_RE = re.compile(
    r"\b(?:capture|vc_img|shared[\s_-]+image|img|pxl|screenshot|dsc)[\s_-]+\d{4,}(?:[\s_-]+\d+)*\b",
    re.IGNORECASE,
)
_UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.IGNORECASE)
_TIMESTAMP_NUM_RE = re.compile(r"^(?:\d{6,}|20\d{2}[-_]?\d{2}[-_]?\d{2}(?:[-_\s]?\d{4,6})?)$")

_GENERIC_PLACEHOLDERS = {
    "unidentified product",
    "unidentified",
    "unidentified image",
    "shared product",
    "shared image",
    "identified product",
    "analyzed product",
    "detected product",
    "recognized product",
    "product",
    "unknown",
    "unknown product",
    "generic",
    "general",
    "electronics",
    "image",
    "photo",
    "picture",
    "screenshot",
    "n/a",
    "none",
    "null",
}

GEMINI_SYSTEM_INSTRUCTION: str = (
    "Extract e-commerce product JSON from pixels. "
    "Never invent brands, models, prices, or Electronics for non-electronics. "
    "Use Industrial Materials for pipes/hardware and Fashion for dresses/apparel. "
    "Use identification_status='PARTIAL' if exact model is uncertain, or 'UNIDENTIFIED' if unreadable."
)

GEMINI_RESPONSE_SCHEMA: Dict[str, Any] = {
    "type": "OBJECT",
    "properties": {
        "brand": {"type": "STRING", "nullable": True},
        "product_name": {"type": "STRING", "nullable": True},
        "category": {"type": "STRING", "nullable": True},
        "model": {"type": "STRING", "nullable": True},
        "colour": {"type": "STRING", "nullable": True},
        "specifications": {
            "type": "ARRAY",
            "items": {"type": "STRING"},
        },
        "search_query": {"type": "STRING", "nullable": True},
        "confidence": {"type": "NUMBER"},
        "identification_status": {
            "type": "STRING",
            "enum": ["IDENTIFIED", "PARTIAL", "UNIDENTIFIED"],
        },
        "candidates": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "brand": {"type": "STRING", "nullable": True},
                    "title": {"type": "STRING"},
                    "category": {"type": "STRING"},
                    "model": {"type": "STRING", "nullable": True},
                    "colour": {"type": "STRING", "nullable": True},
                    "search_query": {"type": "STRING"},
                    "confidence": {"type": "NUMBER"},
                },
                "required": ["title", "category"],
            },
        },
    },
    "required": [
        "product_name",
        "category",
        "specifications",
        "search_query",
        "confidence",
        "identification_status",
    ],
}


def is_generic_image_hint(hint: Optional[str]) -> bool:
    """Returns True if hint is empty, a filename, capture timestamp, URI, path, or generic noise."""
    if not hint or not hint.strip():
        return True
    cleaned = hint.strip()
    lower = cleaned.lower()

    if _URI_OR_PATH_RE.search(cleaned):
        return True
    if _MEDIA_EXT_RE.match(cleaned):
        return True

    norm_stem = re.sub(r"[_\-]+", " ", lower)
    norm_stem = re.sub(r"\s+", " ", norm_stem).strip()

    if (
        _EMBEDDED_CAPTURE_PHRASE_RE.search(cleaned)
        or _CAPTURE_STEM_RE.match(lower)
        or _CAPTURE_STEM_RE.match(norm_stem)
    ):
        return True
    if _UUID_RE.match(cleaned) or _TIMESTAMP_NUM_RE.match(cleaned):
        return True
    if lower in _GENERIC_PLACEHOLDERS:
        return True

    return False


def _extract_jpeg_dimensions(data: bytes) -> Tuple[int, int]:
    idx = 2
    n = len(data)
    while idx + 4 < n:
        if data[idx] != 0xFF:
            idx += 1
            continue
        while idx < n and data[idx] == 0xFF:
            idx += 1
        if idx >= n:
            break
        marker = data[idx]
        idx += 1
        if marker in (0xD8, 0xD9, 0x01) or (0xD0 <= marker <= 0xD7):
            continue
        if idx + 2 > n:
            break
        seg_len = struct.unpack(">H", data[idx : idx + 2])[0]
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            if idx + 7 <= n:
                height, width = struct.unpack(">HH", data[idx + 3 : idx + 7])
                return width, height
            break
        if seg_len < 2:
            break
        idx += seg_len
    return 0, 0


def _extract_webp_dimensions(data: bytes) -> Tuple[int, int]:
    if len(data) < 30:
        return 0, 0
    chunk = data[12:16]
    if chunk == b"VP8X":
        w = 1 + int.from_bytes(data[24:27], "little")
        h = 1 + int.from_bytes(data[27:30], "little")
        return w, h
    if chunk == b"VP8L":
        b0, b1, b2, b3 = data[21:25]
        w = 1 + (((b1 & 0x3F) << 8) | b0)
        h = 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6))
        return w, h
    if chunk == b"VP8 " and data[23:26] == b"\x9d\x01\x2a":
        w, h = struct.unpack("<HH", data[26:30])
        return w & 0x3FFF, h & 0x3FFF
    return 0, 0


def inspect_image_payload(
    image_base64: Optional[str],
    declared_mime: Optional[str] = None,
) -> Dict[str, Any]:
    """Decodes base64 image payload, verifies magic bytes, and extracts dimensions."""
    if not image_base64 or not str(image_base64).strip():
        return {
            "present": False,
            "valid": False,
            "bytes_count": 0,
            "detected_mime": (declared_mime or "image/jpeg").strip(),
            "width": 0,
            "height": 0,
            "clean_base64": None,
            "error": "No image payload provided",
        }

    raw_str = str(image_base64).strip()
    if raw_str.lower().startswith("data:") and ";base64," in raw_str.lower():
        header, _, b64_part = raw_str.partition(";base64,")
        if not declared_mime and ":" in header:
            declared_mime = header.split(":", 1)[1].strip()
        clean_b64 = re.sub(r"\s+", "", b64_part)
    else:
        clean_b64 = re.sub(r"\s+", "", raw_str)

    try:
        padded = clean_b64 + ("=" * ((4 - len(clean_b64) % 4) % 4))
        raw_bytes = base64.b64decode(padded, validate=False)
    except Exception:
        return {
            "present": True,
            "valid": False,
            "bytes_count": 0,
            "detected_mime": (declared_mime or "image/jpeg").strip(),
            "width": 0,
            "height": 0,
            "clean_base64": None,
            "error": "Invalid base64 image payload",
        }

    byte_count = len(raw_bytes)
    if byte_count == 0:
        return {
            "present": True,
            "valid": False,
            "bytes_count": 0,
            "detected_mime": (declared_mime or "image/jpeg").strip(),
            "width": 0,
            "height": 0,
            "clean_base64": None,
            "error": "Empty 0-byte image payload",
        }

    # Reject accidental HTML/JSON error pages passed as base64
    head_l = raw_bytes[:32].lstrip().lower()
    if head_l.startswith((b"<html", b"<!doctype", b"{", b"[")):
        return {
            "present": True,
            "valid": False,
            "bytes_count": byte_count,
            "detected_mime": "text/plain",
            "width": 0,
            "height": 0,
            "clean_base64": None,
            "error": "Payload is text/HTML rather than image binary",
        }

    detected_mime: Optional[str] = None
    width = 0
    height = 0

    if raw_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
        detected_mime = "image/png"
        if byte_count >= 24 and raw_bytes[12:16] == b"IHDR":
            width, height = struct.unpack(">II", raw_bytes[16:24])
    elif raw_bytes.startswith(b"\xff\xd8\xff"):
        detected_mime = "image/jpeg"
        width, height = _extract_jpeg_dimensions(raw_bytes)
    elif byte_count >= 12 and raw_bytes[0:4] == b"RIFF" and raw_bytes[8:12] == b"WEBP":
        detected_mime = "image/webp"
        width, height = _extract_webp_dimensions(raw_bytes)
    elif raw_bytes.startswith((b"GIF87a", b"GIF89a")):
        detected_mime = "image/gif"
        if byte_count >= 10:
            width, height = struct.unpack("<HH", raw_bytes[6:10])
    elif raw_bytes.startswith(b"BM"):
        detected_mime = "image/bmp"
        if byte_count >= 26:
            width, height = struct.unpack("<ii", raw_bytes[18:26])
            height = abs(height)
    elif byte_count >= 12 and raw_bytes[4:8] == b"ftyp":
        brand = raw_bytes[8:12]
        if brand in (b"heic", b"heix", b"hevc", b"hevx", b"mif1", b"msf1"):
            detected_mime = "image/heic"
        elif brand in (b"avif", b"avis"):
            detected_mime = "image/avif"

    final_mime = detected_mime or (declared_mime or "image/jpeg").strip().lower()
    if final_mime == "image/*":
        final_mime = detected_mime or "image/jpeg"

    if detected_mime is None:
        return {
            "present": True,
            "valid": False,
            "recognized_header": False,
            "bytes_count": byte_count,
            "detected_mime": final_mime,
            "width": 0,
            "height": 0,
            "sha256": hashlib.sha256(raw_bytes).hexdigest(),
            "clean_base64": None,
            "compressed": False,
            "error": "Unsupported or corrupt image binary (missing recognized image magic header)",
        }

    # Verify structural integrity of compressed raster bytes and downscale oversized photos cleanly
    sha256_hex = hashlib.sha256(raw_bytes).hexdigest()
    compressed = False
    try:
        from PIL import Image, ImageOps  # type: ignore

        with Image.open(io.BytesIO(raw_bytes)) as probe_img:
            probe_img.verify()

        with Image.open(io.BytesIO(raw_bytes)) as img:
            img = ImageOps.exif_transpose(img)
            img.load()
            width, height = img.size
            if width <= 0 or height <= 0:
                raise ValueError("Invalid image dimensions")

            needs_resize = max(width, height) > TARGET_IMAGE_MAX_DIM or byte_count > TARGET_IMAGE_MAX_BYTES
            if needs_resize:
                work_img = img.convert("RGB")
                if max(width, height) > TARGET_IMAGE_MAX_DIM:
                    work_img.thumbnail((TARGET_IMAGE_MAX_DIM, TARGET_IMAGE_MAX_DIM), Image.Resampling.LANCZOS)
                buf = io.BytesIO()
                work_img.save(buf, format="JPEG", quality=80, optimize=True)
                out_bytes = buf.getvalue()
                if len(out_bytes) <= byte_count or max(width, height) > TARGET_IMAGE_MAX_DIM:
                    clean_b64 = base64.b64encode(out_bytes).decode("ascii")
                    final_mime = "image/jpeg"
                    width, height = work_img.size
                    compressed = True
    except ImportError:
        if detected_mime == "image/png" and (width <= 0 or height <= 0):
            return {
                "present": True,
                "valid": False,
                "recognized_header": True,
                "bytes_count": byte_count,
                "detected_mime": final_mime,
                "width": 0,
                "height": 0,
                "sha256": sha256_hex,
                "clean_base64": None,
                "compressed": False,
                "error": "Corrupt PNG header dimensions",
            }
    except Exception as exc:
        return {
            "present": True,
            "valid": False,
            "recognized_header": True,
            "bytes_count": byte_count,
            "detected_mime": final_mime,
            "width": 0,
            "height": 0,
            "sha256": sha256_hex,
            "clean_base64": None,
            "compressed": False,
            "error": f"Corrupt or unreadable image payload ({type(exc).__name__})",
        }

    if len(base64.b64decode(clean_b64)) > MAX_IMAGE_INPUT_BYTES:
        return {
            "present": True,
            "valid": False,
            "recognized_header": True,
            "bytes_count": byte_count,
            "detected_mime": final_mime,
            "width": width,
            "height": height,
            "sha256": sha256_hex,
            "clean_base64": None,
            "compressed": compressed,
            "error": f"Image exceeds maximum allowed size of {MAX_IMAGE_INPUT_BYTES} bytes",
        }

    return {
        "present": True,
        "valid": True,
        "recognized_header": True,
        "bytes_count": byte_count,
        "detected_mime": final_mime,
        "width": width,
        "height": height,
        "sha256": sha256_hex,
        "clean_base64": clean_b64,
        "compressed": compressed,
        "error": None,
    }


def build_zero_hallucination_prompt(clean_hint: Optional[str] = None) -> str:
    hint_line = f'\nClue: "{clean_hint}"' if clean_hint else ""
    return (
        "Identify the primary retail/commercial item in the image as strict JSON."
        f"{hint_line}\n"
        "Rules: never copy filenames/timestamps; never invent brand/SKU/price; "
        "classify pipes/fasteners as 'Industrial Materials' and apparel/dresses as 'Fashion'; "
        "if only generic item type is visible set identification_status='PARTIAL' with generic search_query; "
        "if blank/unreadable/ambiguous with no product set identification_status='UNIDENTIFIED', "
        "product_name=null, category=null, search_query=null, confidence<=0.2."
    )


def heuristic_parse_product(clean_hint: str, currency: str = "USD") -> Dict[str, Any]:
    """Deterministic parser for clean text hints when cloud Gemini inference is unavailable.

    Never guesses a Samsung SSD, mouse, or electronics category from unrelated or noisy text.
    """
    text = clean_hint.strip()
    lower = text.lower()

    if is_generic_image_hint(text):
        return {
            "status": "UNIDENTIFIED",
            "identification_status": "UNIDENTIFIED",
            "recognition_source": "MANUAL_SEARCH",
            "brand": None,
            "product_name": "Unidentified Product",
            "title": "Unidentified Product",
            "category": "Unidentified",
            "model": None,
            "colour": None,
            "search_query": "",
            "estimated_price": None,
            "currency": currency,
            "specifications": {},
            "specifications_list": [],
            "candidates": [],
            "confidence": 0.1,
            "match_reason": "Input contained only generic capture metadata or filename noise.",
            "source": "rejected_noise",
            "is_demo_fixture": False,
            "is_live_inference": False,
            "rate_limited": False,
            "cache_hit": False,
        }

    brand: Optional[str] = None
    category = "Other"
    model: Optional[str] = None
    specs: Dict[str, str] = {}

    if re.search(r"\b(?:pipes?|piping|pvc|upvc|cpvc|hdpe|steel\s+pipe|metal\s+pipe|flange|valve|conduit|rebar|tubing)\b", lower):
        category = "Industrial Materials"
        if "pvc" in lower or "upvc" in lower:
            specs["Material"] = "PVC"
        elif "steel" in lower:
            specs["Material"] = "Steel"
        elif "copper" in lower:
            specs["Material"] = "Copper"
        else:
            specs["Material"] = "Uncertain from image alone"
        specs["Specifications"] = "Not verified"
    elif re.search(r"\b(?:wrench|drill|hammer|screwdriver|hardware)\b", lower):
        category = "Hardware & Tools"
    elif "samsung" in lower:
        brand = "Samsung"
        category = "Electronics"
        specs["Brand"] = "Samsung"
        if "990 evo" in lower:
            model = "990 EVO"
    elif "crucial" in lower:
        brand = "Crucial"
        category = "Electronics"
        specs["Brand"] = "Crucial"
        if "p3 plus" in lower:
            model = "P3 Plus"
    elif "sony" in lower:
        brand = "Sony"
        category = "Audio"
        specs["Brand"] = "Sony"
        if "wh-1000xm5" in lower or "1000xm5" in lower:
            model = "WH-1000XM5"
    elif "logitech" in lower:
        brand = "Logitech"
        category = "Electronics"
        specs["Brand"] = "Logitech"
    elif "apple" in lower or "iphone" in lower or "macbook" in lower:
        brand = "Apple"
        category = "Computers" if "macbook" in lower else "Smartphones"
        specs["Brand"] = "Apple"
    elif "polago" in lower or "maxi dress" in lower:
        if "polago" in lower:
            brand = "Polago Clothing"
            specs["Brand"] = "Polago Clothing"
        category = "Fashion"
    elif "nike" in lower or "running shoe" in lower or "sneaker" in lower:
        if "nike" in lower:
            brand = "Nike"
            specs["Brand"] = "Nike"
        category = "Footwear"
    elif re.search(r"\b(?:mouse|keyboard|ssd|nvme|laptop|charger|monitor|camera)\b", lower):
        category = "Electronics"

    if re.search(r"\b1tb\b", lower):
        specs["Capacity"] = "1TB"
    elif re.search(r"\b2tb\b", lower):
        specs["Capacity"] = "2TB"
    elif re.search(r"\b500gb\b|\b512gb\b", lower):
        specs["Capacity"] = "512GB" if "512gb" in lower else "500GB"

    if "nvme" in lower or "ssd" in lower:
        specs["Form Factor"] = "M.2 2280 NVMe"

    status = "IDENTIFIED" if (brand or len(specs) > 0 or len(text.split()) >= 2) else "PARTIAL"
    confidence = 0.92 if brand else (0.72 if status == "IDENTIFIED" else 0.5)

    return {
        "status": status,
        "identification_status": status,
        "recognition_source": "LOCAL_OCR",
        "brand": brand,
        "product_name": text,
        "title": text,
        "category": category,
        "model": model,
        "colour": None,
        "search_query": text,
        "estimated_price": None,
        "currency": currency,
        "specifications": specs,
        "specifications_list": [f"{k}: {v}" for k, v in specs.items()],
        "candidates": [],
        "confidence": confidence,
        "match_reason": "Parsed using local structured text fallback (LOCAL OCR; not live AI).",
        "source": "local_ocr",
        "is_demo_fixture": False,
        "is_live_inference": False,
        "rate_limited": False,
        "cache_hit": False,
    }


def sanitize_gemini_parsed_output(
    parsed: Dict[str, Any],
    clean_hint: Optional[str] = None,
    used_model: str = DEFAULT_GEMINI_MODEL,
    currency: str = "USD",
) -> Dict[str, Any]:
    raw_name = str(
        parsed.get("product_name")
        or parsed.get("productName")
        or parsed.get("title")
        or clean_hint
        or ""
    ).strip()

    if not raw_name or (is_generic_image_hint(raw_name) and raw_name.lower() != "unidentified product"):
        raw_name = "Unidentified Product"

    raw_status = str(parsed.get("identification_status") or parsed.get("status") or "").strip().upper()
    raw_brand = parsed.get("brand")
    brand = str(raw_brand).strip() if raw_brand and not is_generic_image_hint(str(raw_brand)) else None

    raw_category = str(parsed.get("category") or "").strip()
    if raw_name.lower() == "unidentified product" or raw_status == "UNIDENTIFIED":
        category = "Unidentified"
        status = "UNIDENTIFIED"
    else:
        category = raw_category or "Other"
        if category.lower() == "electronics" and re.search(
            r"\b(?:pipes?|piping|pvc|upvc|cpvc|hdpe|steel\s+pipe|metal\s+pipe|flange|valve|conduit|rebar|tubing)\b",
            raw_name.lower(),
        ):
            category = "Industrial Materials"
        elif category.lower() in {"electronics", "other", "general"} and re.search(
            r"\b(?:dress|gown|skirt|blouse|kurti|saree|apparel|frock)\b",
            raw_name.lower(),
        ):
            category = "Fashion"
        status = raw_status if raw_status in {"IDENTIFIED", "PARTIAL", "UNIDENTIFIED"} else "IDENTIFIED"

    raw_candidates = parsed.get("candidates")
    cleaned_candidates: List[Dict[str, Any]] = []
    if isinstance(raw_candidates, list):
        for cand in raw_candidates[:3]:
            if not isinstance(cand, dict):
                continue
            cand_title = str(cand.get("title") or cand.get("product_name") or "").strip()
            if not cand_title or is_generic_image_hint(cand_title):
                continue
            cand_query = str(cand.get("search_query") or cand_title).strip()
            if is_generic_image_hint(cand_query):
                cand_query = cand_title
            cleaned_candidates.append({
                "brand": cand.get("brand"),
                "title": cand_title,
                "category": str(cand.get("category") or category),
                "model": cand.get("model"),
                "colour": cand.get("colour") or cand.get("color"),
                "search_query": cand_query,
                "confidence": float(cand.get("confidence", 0.5)),
            })

    if len(cleaned_candidates) > 1 and status == "IDENTIFIED":
        status = "PARTIAL"

    raw_search_query = str(parsed.get("search_query") or "").strip()
    if status == "UNIDENTIFIED":
        search_query = ""
    elif not raw_search_query or is_generic_image_hint(raw_search_query):
        search_query = "" if is_generic_image_hint(raw_name) else raw_name
    else:
        search_query = raw_search_query

    specs = parsed.get("specifications") or parsed.get("specs") or {}
    clean_specs: Dict[str, str] = {}
    clean_specs_list: List[str] = []
    if isinstance(specs, dict):
        for k, v in specs.items():
            ks, vs = str(k).strip(), str(v).strip()
            if ks and vs:
                clean_specs[ks] = vs
                clean_specs_list.append(f"{ks}: {vs}")
    elif isinstance(specs, list):
        spec_idx = 1
        for entry in specs:
            if isinstance(entry, dict):
                k = str(entry.get("name") or entry.get("key") or "").strip()
                v = str(entry.get("value") or "").strip()
                if k and v:
                    clean_specs[k] = v
                    clean_specs_list.append(f"{k}: {v}")
            elif isinstance(entry, str):
                raw_entry = entry.strip()
                if not raw_entry or is_generic_image_hint(raw_entry):
                    continue
                if ":" in raw_entry:
                    k, _, v = raw_entry.partition(":")
                    ks, vs = k.strip(), v.strip()
                    if ks and vs:
                        clean_specs[ks] = vs
                        clean_specs_list.append(f"{ks}: {vs}")
                else:
                    clean_specs[f"spec_{spec_idx}"] = raw_entry
                    clean_specs_list.append(raw_entry)
                    spec_idx += 1

    try:
        confidence = float(parsed.get("confidence", 0.9 if status == "IDENTIFIED" else 0.55))
    except (TypeError, ValueError):
        confidence = 0.5
    if status == "UNIDENTIFIED":
        confidence = min(confidence, 0.2)
    confidence = max(0.0, min(1.0, confidence))

    estimated_price = parsed.get("estimated_price")
    try:
        estimated_price = float(estimated_price) if estimated_price is not None else None
        if estimated_price is not None and estimated_price <= 0:
            estimated_price = None
    except (TypeError, ValueError):
        estimated_price = None

    return {
        "status": status,
        "identification_status": status,
        "recognition_source": "LIVE_AI" if status != "UNIDENTIFIED" else "FALLBACK",
        "brand": brand,
        "product_name": raw_name,
        "title": raw_name,
        "category": category,
        "colour": parsed.get("colour") or parsed.get("color"),
        "model": parsed.get("model"),
        "search_query": search_query,
        "estimated_price": estimated_price,
        "currency": currency,
        "specifications": clean_specs,
        "specifications_list": clean_specs_list,
        "candidates": cleaned_candidates,
        "confidence": confidence,
        "match_reason": str(
            parsed.get("match_reason")
            or f"{used_model} free-tier vision ({int(confidence * 100)}% confidence)."
        ),
        "model_name": used_model,
        "source": "gemini_free_vision" if status != "UNIDENTIFIED" else "vision_unidentified",
        "is_demo_fixture": False,
        "is_live_inference": status != "UNIDENTIFIED",
        "rate_limited": False,
        "cache_hit": False,
    }


class GeminiClient:
    """Server-side free-tier Gemini Vision client enforcing strict $0 zero-billing safeguards.

    - Strictly allows only `APPROVED_FREE_TIER_MODELS` (`gemini-3.5-flash-lite`).
    - Rejects unapproved/paid models, non-zero spend caps, and paid fallbacks.
    - Enforces SHA-256 image-content hash caching to eliminate redundant quota calls.
    - Stops requests immediately on HTTP 429 (`RATE_LIMIT_MESSAGE`) without paid fallback.
    """

    def __init__(
        self,
        api_key: Optional[str] = None,
        model_name: Optional[str] = None,
        free_tier_only: Optional[bool] = None,
        max_cost_usd: Optional[float] = None,
        cache_max_entries: int = 128,
        rate_limit_cooldown_seconds: float = RATE_LIMIT_COOLDOWN_SECONDS,
    ):
        self.api_key = (api_key if api_key is not None else os.getenv("GEMINI_API_KEY", "")).strip()
        policy = validate_free_tier_configuration(
            model_name=model_name,
            free_tier_only=free_tier_only,
            max_cost_usd=max_cost_usd,
        )
        self.model_name: str = policy["model"]
        self.free_tier_only: bool = policy["free_tier_only"]
        self.max_cost_usd: float = policy["max_cost_usd"]
        self.base_url = "https://generativelanguage.googleapis.com/v1beta"
        self.cache_max_entries = max(1, cache_max_entries)
        self.rate_limit_cooldown_seconds = rate_limit_cooldown_seconds
        self._cache: OrderedDict[str, Dict[str, Any]] = OrderedDict()
        self._rate_limit_until: float = 0.0
        self.api_requests_made: int = 0
        self.cache_hits: int = 0
        self.rate_limit_hits: int = 0

    @property
    def is_configured(self) -> bool:
        return is_valid_api_key_format(self.api_key)

    @property
    def is_rate_limited(self) -> bool:
        return self._rate_limit_until > time.monotonic()

    def reset_rate_limit(self) -> None:
        self._rate_limit_until = 0.0

    def _build_cache_key(self, img_info: Dict[str, Any], clean_hint: str) -> Optional[str]:
        img_hash = img_info.get("sha256")
        if img_info.get("valid") and img_hash:
            hint_hash = hashlib.sha256(clean_hint.encode("utf-8")).hexdigest()[:16] if clean_hint else "nohint"
            return f"img:{img_hash}:{hint_hash}:{self.model_name}"
        return None

    async def check_model_availability(self) -> Dict[str, Any]:
        """Verifies the approved free-tier model endpoint without invoking paid fallbacks."""
        if self.model_name not in APPROVED_FREE_TIER_MODELS:
            raise FreeTierPolicyError(f"Unapproved model: {self.model_name}")
        if not self.is_configured:
            return {
                "available": False,
                "model": self.model_name,
                "free_tier_only": self.free_tier_only,
                "reason": "missing_or_invalid_api_key",
            }
        url = f"{self.base_url}/models/{self.model_name}"
        headers = {"x-goog-api-key": self.api_key}
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(url, headers=headers)
            return {
                "available": resp.status_code == 200,
                "status_code": resp.status_code,
                "model": self.model_name,
                "free_tier_only": self.free_tier_only,
            }
        except Exception as exc:
            return {
                "available": False,
                "model": self.model_name,
                "free_tier_only": self.free_tier_only,
                "reason": type(exc).__name__,
            }

    def _build_unidentified_or_fallback(
        self,
        *,
        clean_hint: str,
        img_info: Dict[str, Any],
        req_id: str,
        currency: str,
        reason: str,
        source_label: str,
        rate_limited: bool = False,
        error_code: Optional[str] = None,
    ) -> Dict[str, Any]:
        if clean_hint:
            fallback = heuristic_parse_product(clean_hint, currency=currency)
            fallback.update({
                "request_id": req_id,
                "image_bytes_received": img_info["bytes_count"],
                "image_dimensions": (
                    f"{img_info['width']}x{img_info['height']}"
                    if img_info["width"] > 0
                    else None
                ),
                "detected_mime_type": img_info["detected_mime"] if img_info["present"] else None,
                "rate_limited": rate_limited,
                "error_code": error_code,
                "diagnostic_note": reason,
                "cache_hit": False,
                "api_requests_made": self.api_requests_made,
            })
            return fallback

        payload = {
            "status": "UNIDENTIFIED",
            "identification_status": "UNIDENTIFIED",
            "recognition_source": "MANUAL_SEARCH",
            "brand": None,
            "product_name": "Unidentified Product",
            "title": "Unidentified Product",
            "category": "Unidentified",
            "colour": None,
            "model": None,
            "search_query": "",
            "estimated_price": None,
            "currency": currency,
            "specifications": {},
            "specifications_list": [],
            "candidates": [],
            "confidence": 0.1,
            "match_reason": reason,
            "model_name": None,
            "source": source_label,
            "is_demo_fixture": False,
            "is_live_inference": False,
            "rate_limited": rate_limited,
            "error_code": error_code,
            "request_id": req_id,
            "image_bytes_received": img_info["bytes_count"],
            "image_dimensions": (
                f"{img_info['width']}x{img_info['height']}"
                if img_info["width"] > 0
                else None
            ),
            "detected_mime_type": img_info["detected_mime"] if img_info["present"] else None,
            "diagnostic_note": reason,
            "cache_hit": False,
            "api_requests_made": self.api_requests_made,
        }
        return payload

    async def understand_product(
        self,
        text: Optional[str] = None,
        image_base64: Optional[str] = None,
        mime_type: str = "image/jpeg",
        hint_text: Optional[str] = None,
        request_id: Optional[str] = None,
        currency: str = "USD",
    ) -> Dict[str, Any]:
        # Re-validate zero-billing policy on every inference boundary.
        validate_free_tier_configuration(
            model_name=self.model_name,
            free_tier_only=self.free_tier_only,
            max_cost_usd=self.max_cost_usd,
        )

        req_id = request_id or uuid.uuid4().hex[:10]
        raw_hint = (hint_text or text or "").strip()
        clean_hint = "" if is_generic_image_hint(raw_hint) else raw_hint
        img_info = inspect_image_payload(image_base64, declared_mime=mime_type)

        # Reject corrupt/unreadable image binary immediately before spending free quota
        if img_info["present"] and not img_info["valid"] and not clean_hint:
            corrupt_source = "needs_gemini_key" if not self.is_configured else "corrupt_image"
            corrupt_reason = img_info.get("error") or "Corrupt or unreadable image payload."
            return self._build_unidentified_or_fallback(
                clean_hint="",
                img_info=img_info,
                req_id=req_id,
                currency=currency,
                reason=corrupt_reason if self.is_configured else "Visual AI requires GEMINI_API_KEY in backend .env. Please enter the product name manually.",
                source_label=corrupt_source,
                error_code="CORRUPT_IMAGE" if self.is_configured else None,
            )

        # SHA-256 content-hash cache lookup prevents redundant Gemini free-tier calls
        cache_key = self._build_cache_key(img_info, clean_hint)
        if cache_key and cache_key in self._cache:
            self.cache_hits += 1
            self._cache.move_to_end(cache_key)
            cached = dict(self._cache[cache_key])
            cached.update({
                "request_id": req_id,
                "cache_hit": True,
                "api_requests_made": self.api_requests_made,
            })
            logger.info(
                "[VisualCartAI] req_id=%s cache_hit=true sha256=%s model=%s",
                req_id,
                img_info.get("sha256", "")[:12],
                self.model_name,
            )
            return cached

        # Honor active rate-limit circuit breaker immediately (zero retries, zero paid fallback)
        if self.is_rate_limited:
            return self._build_unidentified_or_fallback(
                clean_hint=clean_hint,
                img_info=img_info,
                req_id=req_id,
                currency=currency,
                reason=RATE_LIMIT_MESSAGE,
                source_label="rate_limited",
                rate_limited=True,
                error_code="RATE_LIMIT_EXCEEDED",
            )

        if self.is_configured and (img_info["valid"] or clean_hint):
            parts: List[Dict[str, Any]] = []
            if img_info["valid"] and img_info["clean_base64"]:
                parts.append({
                    "inline_data": {
                        "mime_type": img_info["detected_mime"],
                        "data": img_info["clean_base64"],
                    }
                })
            parts.append({"text": build_zero_hallucination_prompt(clean_hint or None)})

            payload: Dict[str, Any] = {
                "systemInstruction": {"parts": [{"text": GEMINI_SYSTEM_INSTRUCTION}]},
                "contents": [{"parts": parts}],
                "generationConfig": {
                    "responseMimeType": "application/json",
                    "responseSchema": GEMINI_RESPONSE_SCHEMA,
                    "maxOutputTokens": MAX_OUTPUT_TOKENS,
                    "thinkingConfig": {"thinkingLevel": "MINIMAL"},
                },
            }

            headers = {
                "x-goog-api-key": self.api_key,
                "Content-Type": "application/json",
            }
            url = f"{self.base_url}/models/{self.model_name}:generateContent"

            # Transient upstream overload retry ONLY on HTTP 502/503/504 using the exact same
            # approved free-tier model — strictly zero retries on 429 and zero paid fallback.
            transient_delays = (0.4, 0.8)
            try:
                async with httpx.AsyncClient(timeout=20.0) as client:
                    resp = None
                    for attempt in range(len(transient_delays) + 1):
                        self.api_requests_made += 1
                        resp = await client.post(url, json=payload, headers=headers)
                        if resp.status_code in (502, 503, 504) and attempt < len(transient_delays):
                            delay_s = transient_delays[attempt]
                            logger.warning(
                                "[VisualCartAI] req_id=%s model=%s transient_http=%s retrying_in=%.1fs",
                                req_id,
                                self.model_name,
                                resp.status_code,
                                delay_s,
                            )
                            await asyncio.sleep(delay_s)
                            continue
                        break

                if resp.status_code == 429:
                    self.rate_limit_hits += 1
                    retry_after_raw = resp.headers.get("Retry-After", "").strip()
                    try:
                        cooldown = max(self.rate_limit_cooldown_seconds, float(retry_after_raw))
                    except ValueError:
                        cooldown = self.rate_limit_cooldown_seconds
                    self._rate_limit_until = time.monotonic() + cooldown
                    logger.warning(
                        "[VisualCartAI] req_id=%s model=%s status=429 stopping requests for %.0fs",
                        req_id,
                        self.model_name,
                        cooldown,
                    )
                    return self._build_unidentified_or_fallback(
                        clean_hint=clean_hint,
                        img_info=img_info,
                        req_id=req_id,
                        currency=currency,
                        reason=RATE_LIMIT_MESSAGE,
                        source_label="rate_limited",
                        rate_limited=True,
                        error_code="RATE_LIMIT_EXCEEDED",
                    )

                if resp.status_code in (401, 403) or (
                    resp.status_code == 400 and "API_KEY_INVALID" in resp.text
                ):
                    logger.warning(
                        "[VisualCartAI] req_id=%s model=%s auth_error=%s",
                        req_id,
                        self.model_name,
                        resp.status_code,
                    )
                    return self._build_unidentified_or_fallback(
                        clean_hint=clean_hint,
                        img_info=img_info,
                        req_id=req_id,
                        currency=currency,
                        reason="Gemini API key rejected or invalid. Using local OCR or manual search.",
                        source_label="invalid_api_key",
                        error_code="INVALID_API_KEY",
                    )

                if resp.status_code >= 400:
                    logger.warning(
                        "[VisualCartAI] req_id=%s model=%s status_code=%s",
                        req_id,
                        self.model_name,
                        resp.status_code,
                    )
                    return self._build_unidentified_or_fallback(
                        clean_hint=clean_hint,
                        img_info=img_info,
                        req_id=req_id,
                        currency=currency,
                        reason=f"Free-tier AI returned HTTP {resp.status_code}. Using local fallback / manual entry.",
                        source_label="vision_error",
                        error_code=f"HTTP_{resp.status_code}",
                    )

                data = resp.json()
                candidate_text = (
                    data.get("candidates", [{}])[0]
                    .get("content", {})
                    .get("parts", [{}])[0]
                    .get("text", "")
                    .strip()
                )
                if candidate_text.startswith("```json"):
                    candidate_text = candidate_text[7:]
                if candidate_text.startswith("```"):
                    candidate_text = candidate_text[3:]
                if candidate_text.endswith("```"):
                    candidate_text = candidate_text[:-3]
                parsed = json.loads(candidate_text.strip())
                result = sanitize_gemini_parsed_output(
                    parsed=parsed,
                    clean_hint=clean_hint or None,
                    used_model=self.model_name,
                    currency=currency,
                )
                usage = data.get("usageMetadata") or {}
                result.update({
                    "request_id": req_id,
                    "image_bytes_received": img_info["bytes_count"],
                    "image_dimensions": (
                        f"{img_info['width']}x{img_info['height']}"
                        if img_info["width"] > 0
                        else None
                    ),
                    "detected_mime_type": img_info["detected_mime"] if img_info["present"] else None,
                    "image_sha256": img_info.get("sha256"),
                    "compressed_for_quota": bool(img_info.get("compressed")),
                    "token_usage": {
                        "prompt_tokens": usage.get("promptTokenCount"),
                        "output_tokens": usage.get("candidatesTokenCount"),
                        "total_tokens": usage.get("totalTokenCount"),
                    },
                    "cache_hit": False,
                    "api_requests_made": self.api_requests_made,
                })
                if cache_key and result.get("status") in {"IDENTIFIED", "PARTIAL"} and result.get("is_live_inference"):
                    self._cache[cache_key] = dict(result)
                    self._cache.move_to_end(cache_key)
                    while len(self._cache) > self.cache_max_entries:
                        self._cache.popitem(last=False)

                logger.info(
                    "[VisualCartAI] req_id=%s mime=%s bytes=%s dims=%s model=%s status=%s source=%s",
                    req_id,
                    result["detected_mime_type"],
                    img_info["bytes_count"],
                    result["image_dimensions"],
                    self.model_name,
                    result["status"],
                    result["recognition_source"],
                )
                return result
            except Exception as exc:
                logger.warning(
                    "[VisualCartAI] req_id=%s model=%s error=%s",
                    req_id,
                    self.model_name,
                    type(exc).__name__,
                )

        # Fallback path when GEMINI_API_KEY is absent/invalid or remote call failed
        if clean_hint:
            fallback = heuristic_parse_product(clean_hint, currency=currency)
            fallback.update({
                "request_id": req_id,
                "image_bytes_received": img_info["bytes_count"],
                "image_dimensions": (
                    f"{img_info['width']}x{img_info['height']}"
                    if img_info["width"] > 0
                    else None
                ),
                "detected_mime_type": img_info["detected_mime"] if img_info["present"] else None,
                "api_requests_made": self.api_requests_made,
            })
            return fallback

        raw_key_present = bool(self.api_key)
        if not raw_key_present:
            reason = "Visual AI requires GEMINI_API_KEY in backend .env. Please enter the product name manually."
            source_label = "needs_gemini_key"
            err_code = "MISSING_API_KEY"
        elif not self.is_configured:
            reason = "Configured GEMINI_API_KEY is invalid or a placeholder. Please check your free-tier key or enter the product manually."
            source_label = "invalid_api_key"
            err_code = "INVALID_API_KEY"
        else:
            reason = "Visual AI image recognition did not identify this image. Please enter the product name manually."
            source_label = "vision_unidentified"
            err_code = None

        return self._build_unidentified_or_fallback(
            clean_hint="",
            img_info=img_info,
            req_id=req_id,
            currency=currency,
            reason=reason,
            source_label=source_label,
            error_code=err_code,
        )


def build_merchant_search_response(query: Optional[str]) -> Dict[str, Any]:
    """Constructs honest direct merchant search URLs without fabricated prices or stock.

    Rejects raw capture filenames, timestamps, URIs, and generic noise via `is_generic_image_hint`.
    """
    from urllib.parse import quote_plus

    cleaned = (query or "").strip()
    if is_generic_image_hint(cleaned):
        return {
            "query": None,
            "rejected": True,
            "listings": [],
        }

    encoded = quote_plus(cleaned)
    return {
        "query": cleaned,
        "rejected": False,
        "listings": [
            {
                "merchant": "Flipkart",
                "merchant_id": "flipkart",
                "price": None,
                "originalPrice": None,
                "status": "UNKNOWN",
                "delivery": None,
                "isLowest": False,
                "listing_origin": "DIRECT_SEARCH_URL",
                "url": f"https://www.flipkart.com/search?q={encoded}",
            },
            {
                "merchant": "Amazon India",
                "merchant_id": "amazon_in",
                "price": None,
                "originalPrice": None,
                "status": "UNKNOWN",
                "delivery": None,
                "isLowest": False,
                "listing_origin": "DIRECT_SEARCH_URL",
                "url": f"https://www.amazon.in/s?k={encoded}",
            },
            {
                "merchant": "Croma",
                "merchant_id": "croma",
                "price": None,
                "originalPrice": None,
                "status": "UNKNOWN",
                "delivery": None,
                "isLowest": False,
                "listing_origin": "DIRECT_SEARCH_URL",
                "url": f"https://www.croma.com/searchB?q={encoded}%3Arelevance&text={encoded}",
            },
        ],
    }
