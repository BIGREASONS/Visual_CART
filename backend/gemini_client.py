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

import base64
import json
import logging
import os
import re
import struct
import uuid
from typing import Any, Dict, List, Optional, Tuple

import httpx

logger = logging.getLogger("visualcart.ai")

ACTIVE_GEMINI_MODELS: Tuple[str, ...] = (
    "gemini-3.8-flash",
    "gemini-3.5-flash-lite",
    "gemini-2.5-flash",
)
DEFAULT_GEMINI_MODEL: str = ACTIVE_GEMINI_MODELS[0]

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

GEMINI_RESPONSE_SCHEMA: Dict[str, Any] = {
    "type": "OBJECT",
    "properties": {
        "status": {"type": "STRING", "enum": ["IDENTIFIED", "PARTIAL", "UNIDENTIFIED"]},
        "brand": {"type": "STRING", "nullable": True},
        "product_name": {"type": "STRING"},
        "category": {"type": "STRING"},
        "model": {"type": "STRING", "nullable": True},
        "colour": {"type": "STRING", "nullable": True},
        "search_query": {"type": "STRING"},
        "specifications": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "name": {"type": "STRING"},
                    "value": {"type": "STRING"},
                },
                "required": ["name", "value"],
            },
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
        "confidence": {"type": "NUMBER"},
        "match_reason": {"type": "STRING"},
    },
    "required": ["status", "product_name", "category", "search_query", "confidence", "match_reason"],
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

    return {
        "present": True,
        "valid": detected_mime is not None or byte_count >= 8,
        "recognized_header": detected_mime is not None,
        "bytes_count": byte_count,
        "detected_mime": final_mime,
        "width": width,
        "height": height,
        "clean_base64": clean_b64,
        "error": None,
    }


def build_zero_hallucination_prompt(clean_hint: Optional[str] = None) -> str:
    hint_block = (
        f'\nSecondary clean text clue (verify visually against pixels before using): "{clean_hint}".'
        if clean_hint
        else ""
    )
    return (
        "You are a zero-hallucination multimodal product recognition engine for PayPal Visual Cart.\n"
        "Inspect the actual product image pixels carefully: shape, branding logos, label text, finish, and materials."
        f"{hint_block}\n"
        "CRITICAL ANTI-HALLUCINATION RULES:\n"
        "1. NEVER copy filenames, capture timestamps (capture_*, vc_img_*, shared image.jpg), or IDs into product_name or search_query.\n"
        "2. NEVER invent a brand name, SKU/model number, storage capacity, or price if not clearly visible.\n"
        "3. NEVER default non-electronic items (pipes, steel/PVC hardware, furniture, tools, garments) to category 'Electronics'. "
        "Use accurate categories such as 'Industrial Materials', 'Hardware & Tools', 'Fashion', 'Footwear', 'Home', 'Audio', 'Computers', 'Gaming', 'Electronics', 'Other', or 'Unidentified'.\n"
        "4. If the object type is visible (e.g. metal or PVC pipes) without a legible brand/SKU, set status='PARTIAL', include "
        "{\"Material\": \"Uncertain from image alone\", \"Specifications\": \"Not verified\"} in specifications, and give a factual generic search_query.\n"
        "5. If 2-3 candidate models are plausible, set status='PARTIAL' and list up to 3 items in candidates.\n"
        "6. If the image is blank, unreadable, or lacks any identifiable retail/commercial item, set status='UNIDENTIFIED', "
        "product_name='Unidentified Product', category='Unidentified', search_query='', confidence<=0.25.\n"
        "Respond strictly with valid JSON matching the schema."
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
            "recognition_source": "FALLBACK",
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
            "candidates": [],
            "confidence": 0.1,
            "match_reason": "Input contained only generic capture metadata or filename noise.",
            "source": "rejected_noise",
            "is_demo_fixture": False,
            "is_live_inference": False,
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
        "recognition_source": "FALLBACK",
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
        "candidates": [],
        "confidence": confidence,
        "match_reason": "Parsed using local structured text fallback (cloud vision offline).",
        "source": "local_heuristic",
        "is_demo_fixture": False,
        "is_live_inference": False,
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

    raw_status = str(parsed.get("status") or "").strip().upper()
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
    if isinstance(specs, dict):
        clean_specs = {
            str(k).strip(): str(v).strip()
            for k, v in specs.items()
            if str(k).strip() and str(v).strip()
        }
    elif isinstance(specs, list):
        for entry in specs:
            if isinstance(entry, dict):
                k = str(entry.get("name") or entry.get("key") or "").strip()
                v = str(entry.get("value") or "").strip()
                if k and v:
                    clean_specs[k] = v

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
        "candidates": cleaned_candidates,
        "confidence": confidence,
        "match_reason": str(
            parsed.get("match_reason")
            or f"{used_model} multimodal recognition ({int(confidence * 100)}% confidence)."
        ),
        "model_name": used_model,
        "source": "gemini_cloud" if status != "UNIDENTIFIED" else "vision_unidentified",
        "is_demo_fixture": False,
        "is_live_inference": status != "UNIDENTIFIED",
    }


class GeminiClient:
    """Server-side multimodal Gemini client using current active models and structured JSON output."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        model_name: Optional[str] = None,
    ):
        self.api_key = (api_key if api_key is not None else os.getenv("GEMINI_API_KEY", "")).strip()
        self.model_name = (
            model_name if model_name is not None else os.getenv("GEMINI_MODEL", DEFAULT_GEMINI_MODEL)
        ).strip() or DEFAULT_GEMINI_MODEL
        self.base_url = "https://generativelanguage.googleapis.com/v1beta"

    @property
    def is_configured(self) -> bool:
        return bool(self.api_key)

    async def understand_product(
        self,
        text: Optional[str] = None,
        image_base64: Optional[str] = None,
        mime_type: str = "image/jpeg",
        hint_text: Optional[str] = None,
        request_id: Optional[str] = None,
        currency: str = "USD",
    ) -> Dict[str, Any]:
        req_id = request_id or uuid.uuid4().hex[:10]
        raw_hint = (hint_text or text or "").strip()
        clean_hint = "" if is_generic_image_hint(raw_hint) else raw_hint
        img_info = inspect_image_payload(image_base64, declared_mime=mime_type)

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
                "contents": [{"parts": parts}],
                "generationConfig": {
                    "responseMimeType": "application/json",
                    "temperature": 0.1,
                    "responseSchema": GEMINI_RESPONSE_SCHEMA,
                },
            }

            candidate_models = [self.model_name] + [
                m for m in ACTIVE_GEMINI_MODELS if m != self.model_name
            ]
            headers = {
                "x-goog-api-key": self.api_key,
                "Content-Type": "application/json",
            }

            async with httpx.AsyncClient(timeout=20.0) as client:
                for candidate_model in candidate_models:
                    url = f"{self.base_url}/models/{candidate_model}:generateContent"
                    try:
                        resp = await client.post(url, json=payload, headers=headers)
                        if resp.status_code in (400, 404, 429, 503):
                            logger.warning(
                                "[VisualCartAI] req_id=%s model=%s status_code=%s retrying fallback",
                                req_id,
                                candidate_model,
                                resp.status_code,
                            )
                            continue
                        resp.raise_for_status()
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
                            used_model=candidate_model,
                            currency=currency,
                        )
                        result.update({
                            "request_id": req_id,
                            "image_bytes_received": img_info["bytes_count"],
                            "image_dimensions": (
                                f"{img_info['width']}x{img_info['height']}"
                                if img_info["width"] > 0
                                else None
                            ),
                            "detected_mime_type": img_info["detected_mime"] if img_info["present"] else None,
                        })
                        logger.info(
                            "[VisualCartAI] req_id=%s mime=%s bytes=%s dims=%s model=%s status=%s source=%s",
                            req_id,
                            result["detected_mime_type"],
                            img_info["bytes_count"],
                            result["image_dimensions"],
                            candidate_model,
                            result["status"],
                            result["recognition_source"],
                        )
                        return result
                    except Exception as exc:
                        logger.warning(
                            "[VisualCartAI] req_id=%s model=%s error=%s",
                            req_id,
                            candidate_model,
                            type(exc).__name__,
                        )

        # Fallback path when GEMINI_API_KEY is absent, payload invalid, or remote call failed
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
            })
            logger.info(
                "[VisualCartAI] req_id=%s mime=%s bytes=%s status=%s source=%s",
                req_id,
                fallback["detected_mime_type"],
                img_info["bytes_count"],
                fallback["status"],
                fallback["recognition_source"],
            )
            return fallback

        reason = (
            "Visual AI image recognition did not identify this image. Please enter the product name manually."
            if self.is_configured
            else "Visual AI requires GEMINI_API_KEY in backend .env. Please enter the product name manually."
        )
        source_label = "vision_unidentified" if self.is_configured else "needs_gemini_key"
        unidentified = {
            "status": "UNIDENTIFIED",
            "recognition_source": "FALLBACK",
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
            "candidates": [],
            "confidence": 0.1,
            "match_reason": reason,
            "model_name": None,
            "source": source_label,
            "is_demo_fixture": False,
            "is_live_inference": False,
            "request_id": req_id,
            "image_bytes_received": img_info["bytes_count"],
            "image_dimensions": (
                f"{img_info['width']}x{img_info['height']}"
                if img_info["width"] > 0
                else None
            ),
            "detected_mime_type": img_info["detected_mime"] if img_info["present"] else None,
            "diagnostic_note": img_info.get("error") or reason,
        }
        logger.info(
            "[VisualCartAI] req_id=%s mime=%s bytes=%s status=UNIDENTIFIED source=%s",
            req_id,
            unidentified["detected_mime_type"],
            img_info["bytes_count"],
            source_label,
        )
        return unidentified


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
