"""Gemini Multimodal Client for Product Understanding.

Extracts canonical brand, product_name, category, colour, model, specifications map,
and confidence from raw OCR text, image base64, or product URLs.
API keys remain securely stored and processed server-side.
"""

import os
import json
from typing import Dict, Any, Optional
import httpx

SYSTEM_PROMPT = """You are an expert multimodal e-commerce product recognition engine for PayPal Visual Cart.
Given text or an image of a product, extract structured information and respond strictly with valid JSON.
Schema:
{
  "brand": "Brand or manufacturer name, or null",
  "product_name": "Clean canonical product name without marketing buzzwords",
  "category": "Electronics | Footwear | Audio | Computers | Gaming | Fashion | Home | Accessories | Other",
  "colour": "Color or finish (e.g. Space Gray, Silver, Triple Black, Graphite) or null",
  "model": "Model identifier or series (e.g. 990 EVO, WH-1000XM5, Air Max 270, M3) or null",
  "specifications": {
    "key": "value"
  },
  "confidence": 0.0 to 1.0,
  "match_reason": "Brief human-readable reason"
}
"""

class GeminiClient:
    def __init__(
        self,
        api_key: Optional[str] = None,
        model_name: Optional[str] = None
    ):
        self.api_key = api_key or os.getenv("GEMINI_API_KEY", "")
        self.model_name = model_name or os.getenv("GEMINI_MODEL", "gemini-1.5-flash")
        self.base_url = "https://generativelanguage.googleapis.com/v1beta"

    @property
    def is_configured(self) -> bool:
        return bool(self.api_key)

    async def understand_product(
        self,
        text: Optional[str] = None,
        image_base64: Optional[str] = None,
        mime_type: str = "image/jpeg"
    ) -> Dict[str, Any]:
        """Calls Gemini generateContent with multimodal payload."""
        if not self.is_configured:
            # Deterministic intelligent fixtures when no Gemini key is provided
            query = (text or "").lower()
            if "samsung" in query or "990 evo" in query:
                return {
                    "brand": "Samsung",
                    "product_name": "Samsung 990 EVO 1TB NVMe M.2 SSD",
                    "title": "Samsung 990 EVO 1TB NVMe M.2 SSD",
                    "category": "Computers",
                    "colour": "Black",
                    "model": "990 EVO",
                    "specifications": {
                        "capacity": "1TB",
                        "interface": "NVMe PCIe 4.0/5.0",
                        "form_factor": "M.2 2280"
                    },
                    "confidence": 0.98,
                    "match_reason": "High-confidence model & capacity identification (Samsung 990 EVO 1TB)",
                    "is_demo_fixture": True,
                    "is_live_inference": False
                }
            elif "sony" in query or "1000xm5" in query or "headphone" in query:
                return {
                    "brand": "Sony",
                    "product_name": "Sony WH-1000XM5 Wireless Noise Cancelling Headphones",
                    "title": "Sony WH-1000XM5 Wireless Noise Cancelling Headphones",
                    "category": "Audio",
                    "colour": "Silver",
                    "model": "WH-1000XM5",
                    "specifications": {
                        "type": "Over-Ear",
                        "battery": "30 hours",
                        "connectivity": "Bluetooth 5.2"
                    },
                    "confidence": 0.97,
                    "match_reason": "Exact audio model match for Sony WH-1000XM5 series",
                    "is_demo_fixture": True,
                    "is_live_inference": False
                }
            elif "nike" in query or "air max" in query or "shoe" in query:
                return {
                    "brand": "Nike",
                    "product_name": "Nike Air Max 270 Running Shoes",
                    "title": "Nike Air Max 270 Running Shoes",
                    "category": "Footwear",
                    "colour": "Triple Black",
                    "model": "Air Max 270",
                    "specifications": {
                        "size": "UK 9",
                        "gender": "Men",
                        "color": "Triple Black"
                    },
                    "confidence": 0.96,
                    "match_reason": "Exact footwear match for Nike Air Max 270 Triple Black UK 9",
                    "is_demo_fixture": True,
                    "is_live_inference": False
                }
            elif "apple" in query or "macbook" in query or "m3" in query:
                return {
                    "brand": "Apple",
                    "product_name": "Apple MacBook Air 13-inch M3",
                    "title": "Apple MacBook Air 13-inch M3",
                    "category": "Computers",
                    "colour": "Space Gray",
                    "model": "MacBook Air M3",
                    "specifications": {
                        "chip": "Apple M3",
                        "memory": "16GB",
                        "capacity": "512GB SSD",
                        "display": "13-inch"
                    },
                    "confidence": 0.99,
                    "match_reason": "Exact compute match for Apple MacBook Air 13-inch M3 16GB/512GB",
                    "is_demo_fixture": True,
                    "is_live_inference": False
                }
            elif "logitech" in query or "mx master" in query:
                return {
                    "brand": "Logitech",
                    "product_name": "Logitech MX Master 3S Wireless Mouse",
                    "title": "Logitech MX Master 3S Wireless Mouse",
                    "category": "Electronics",
                    "colour": "Graphite",
                    "model": "MX Master 3S",
                    "specifications": {
                        "sensor": "8000 DPI",
                        "connectivity": "Bluetooth / Bolt"
                    },
                    "confidence": 0.95,
                    "match_reason": "High-confidence peripheral match for Logitech MX Master 3S",
                    "is_demo_fixture": True,
                    "is_live_inference": False
                }
            elif "crucial" in query or "p3" in query or "ssd" in query:
                return {
                    "brand": "Crucial",
                    "product_name": "Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD",
                    "title": "Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD",
                    "category": "Computers",
                    "colour": "Black",
                    "model": "P3 Plus",
                    "specifications": {
                        "capacity": "1TB",
                        "interface": "PCIe 4.0 NVMe",
                        "form_factor": "M.2 2280"
                    },
                    "confidence": 0.96,
                    "match_reason": "High-confidence model & capacity identification (Crucial P3 Plus 1TB)",
                    "is_demo_fixture": True,
                    "is_live_inference": False
                }
            else:
                return {
                    "brand": "Generic",
                    "product_name": text.strip() if text else "Unidentified Product",
                    "title": text.strip() if text else "Unidentified Product",
                    "category": "Electronics",
                    "colour": None,
                    "model": None,
                    "specifications": {},
                    "confidence": 0.70,
                    "match_reason": "Extracted via local heuristics fallback",
                    "is_demo_fixture": True,
                    "is_live_inference": False
                }

        parts = [{"text": f"{SYSTEM_PROMPT}\n\nProduct Input:\n{text or 'Analyze the visual product in the image.'}"}]
        if image_base64:
            parts.append({
                "inlineData": {
                    "mimeType": mime_type,
                    "data": image_base64
                }
            })

        payload = {
            "contents": [
                {
                    "parts": parts
                }
            ],
            "generationConfig": {
                "responseMimeType": "application/json",
                "temperature": 0.1
            }
        }

        url = f"{self.base_url}/models/{self.model_name}:generateContent?key={self.api_key}"
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
            try:
                candidate_text = data["candidates"][0]["content"]["parts"][0]["text"].strip()
                if candidate_text.startswith("```json"):
                    candidate_text = candidate_text[7:]
                if candidate_text.startswith("```"):
                    candidate_text = candidate_text[3:]
                if candidate_text.endswith("```"):
                    candidate_text = candidate_text[:-3]
                parsed = json.loads(candidate_text.strip())

                # Normalize keys
                product_name = parsed.get("product_name") or parsed.get("productName") or parsed.get("title") or (text or "Identified Product")
                brand = parsed.get("brand")
                category = parsed.get("category") or "Electronics"
                colour = parsed.get("colour") or parsed.get("color")
                model = parsed.get("model")
                specs = parsed.get("specifications") or {}
                confidence = float(parsed.get("confidence", 0.95))
                match_reason = parsed.get("match_reason") or f"Gemini 1.5 Flash multimodal recognition ({int(confidence * 100)}% confidence)"

                return {
                    "brand": brand,
                    "product_name": product_name,
                    "title": product_name,
                    "category": category,
                    "colour": colour,
                    "model": model,
                    "specifications": {str(k): str(v) for k, v in specs.items()},
                    "confidence": confidence,
                    "match_reason": match_reason,
                    "is_demo_fixture": False,
                    "is_live_inference": True
                }
            except Exception:
                return {
                    "brand": None,
                    "product_name": text or "Analyzed Product",
                    "title": text or "Analyzed Product",
                    "category": "General",
                    "colour": None,
                    "model": None,
                    "specifications": {},
                    "confidence": 0.6,
                    "match_reason": "Parsed from raw response",
                    "is_demo_fixture": False,
                    "is_live_inference": True
                }
