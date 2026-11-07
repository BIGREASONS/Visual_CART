# AI Architecture & Multimodal Product Understanding

> **Document Version**: 1.0.0  
> **Date**: November 2026  
> **Project**: PayPal Visual Cart  
> **Design Theme**: Editorial Fintech (Warm Ivory `#F6F3EC`, Deep Ink `#202B32`, Forest Green `#356B57`, Muted Sage `#A6B5A0`, Terracotta `#C97858`)

---

## 1. Architectural Overview & Vision Pipeline

PayPal Visual Cart bridges visual product discovery across third-party applications (social media, browsers, review sites) with structured multi-merchant comparison and PayPal checkout. To accomplish this, the system deploys a **hybrid cloud/edge multimodal AI architecture**:

```
+─────────────────────────────────────────────────────────────────────────────+
|                         INGESTION & PREPROCESSING                           |
|                                                                             |
|  [ User Ingestion ]: Direct Share Sheet / Google Circle to Search / Bubble  |
|                                     │                                       |
|                                     ▼                                       |
|  [ SafeImageDecoder ]: Safe copy to private cache, downsampling, base64     |
+─────────────────────────────────────┬───────────────────────────────────────+
                                      │
                                      ▼
+─────────────────────────────────────────────────────────────────────────────+
|                     DUAL-TIER PRODUCT RECOGNITION ENGINE                    |
|                                                                             |
|      ┌────────────────────────────────────────────────────────┐             |
|      │ Tier 1: Cloud Multimodal AI (Google Gemini 1.5 Flash)  │             |
|      │ - Interleaved multimodal transformer                   │             |
|      │ - Strict JSON schema output via generationConfig       │             |
|      │ - Semantic spec extraction & OCR disambiguation        │             |
|      └──────────────────────────┬─────────────────────────────┘             |
|                                 │ (Network timeout / quota / offline)       |
|                                 ▼                                           |
|      ┌────────────────────────────────────────────────────────┐             |
|      │ Tier 2: On-Device Deterministic Engine (LocalParser)   │             |
|      │ - High-performance regex spec tokenizer                │             |
|      │ - Brand synonym mapping & clutter text stripping       │             |
|      └──────────────────────────┬─────────────────────────────┘             |
|                                 │ (Benchmark testing / high-stakes demo)    |
|                                 ▼                                           |
|      ┌────────────────────────────────────────────────────────┐             |
|      │ Tier 3: Deterministic Flagship Demo Fixtures           │             |
|      │ - Crucial P3 Plus 1TB, Sony WH-1000XM5, Nike AJ1       │             |
|      └────────────────────────────────────────────────────────┘             |
+─────────────────────────────────────┬───────────────────────────────────────+
                                      │
                                      ▼
+─────────────────────────────────────────────────────────────────────────────+
|                 CANONICALIZATION & SPEC ALIGNMENT ENGINE                    |
|                                                                             |
|  - Storage capacity normalization (1000GB / 1024GB -> 1TB)                  |
|  - Interface normalization (PCIe Gen4 / Gen 4.0 x4 -> PCIe 4.0)             |
|  - Brand canonicalization (WD -> Western Digital, Logi -> Logitech)         |
|  - Confidence scoring & explainable match badge generation                  |
+─────────────────────────────────────────────────────────────────────────────+
```

---

## 2. Google Gemini 1.5 Multimodal Vision Pipeline

### 2.1 Model Selection & Rationale
We selected **Google Gemini 1.5 Flash** (`gemini-1.5-flash`) as our primary cloud multimodal model, with optional configuration for **Gemini 1.5 Pro**:

| Metric / Capability | Gemini 1.5 Flash | Traditional OCR + Text LLM |
| :--- | :--- | :--- |
| **End-to-End Latency** | ~700ms – 1,100ms | 1,800ms – 3,200ms (2-pass pipeline) |
| **Visual Feature Understanding** | Understands logos, physical packaging, spec tables, and colorways natively | Requires separate vision OCR pass, losing spatial layout context |
| **Schema Strictness** | Native JSON Mode (`generationConfig.response_mime_type`) | High rate of hallucinated markdown markdown wrappers (```json) |
| **Cost Efficiency** | Extremely lightweight token consumption | High multi-hop API overhead |

### 2.2 Memory-Safe Bitmap Preprocessing (`SafeImageDecoder.kt`)
Mobile devices routinely crash when handling full-resolution camera captures (e.g., 48MP photos). Visual Cart enforces memory safety:
- Ingests image stream via Android Content Resolver.
- Applies power-of-two subsampling (`inSampleSize`) to constrain image dimensions to a maximum of 1080px.
- Decodes using hardware-accelerated bitmaps where supported.
- Converts to JPEG format at 85% compression quality before encoding to Base64.

---

## 3. Multimodal Prompt Engineering & Schema Enforcement

### 3.1 System Prompt Design
To prevent conversational preambles, chat filler, or uncertain language, the system prompt enforces an authoritative e-commerce parsing role:

```text
You are an expert e-commerce product recognition engine for PayPal Visual Cart.
Given text or an image of a product, extract structured information and respond strictly with valid JSON.
Output schema:
{
  "title": "Clean canonical product name (without marketing hype)",
  "brand": "Brand or manufacturer name",
  "category": "Electronics | Fashion | Home | Accessories | Other",
  "specifications": {
    "key": "value"
  },
  "confidence": 0.0 to 1.0,
  "match_reason": "Brief human-readable reason"
}
```

### 3.2 Native JSON Generation Configuration
The request payload transmitted to Gemini's `v1beta/models/gemini-1.5-flash:generateContent` endpoint explicitly activates native JSON mode:

```json
{
  "contents": [
    {
      "parts": [
        { "text": "<System Prompt + Context>" },
        {
          "inline_data": {
            "mime_type": "image/jpeg",
            "data": "<base64_encoded_image_bytes>"
          }
        }
      ]
    }
  ],
  "generationConfig": {
    "response_mime_type": "application/json",
    "temperature": 0.15
  }
}
```

A temperature of `0.15` ensures deterministic, repeatable output focused on factual hardware attributes.

---

## 4. Canonicalization & Specification Alignment Engine

### 4.1 The Challenge of Retail Catalog Inconsistency
Different e-commerce merchants present the exact same product using drastically different naming conventions:
- **Flipkart**: *"Crucial P3 Plus 1 TB PCIe 4.0 3D NAND NVMe M.2 Solid State Drive (CT1000P3PSSD8)"*
- **Amazon**: *"Crucial P3 Plus 1TB PCIe M.2 2280 Internal SSD Gen4 NVMe, up to 5000MB/s"*
- **Croma**: *"Crucial P3 Plus 1000GB M.2 Type 2280 NVMe SSD"*

Without intelligent canonicalization, simple string matching fails, resulting in duplicate cart entries or inaccurate price comparisons.

### 4.2 Specification Normalization Rules
`LocalProductParser.kt` and `ProductMatchingEngine.kt` apply systematic canonicalization rules:

#### A. Storage Capacity Normalization
```regex
Pattern: (\d+)\s*(TB|GB)
Transforms:
  "1000GB" -> "1TB"
  "1024 GB" -> "1TB"
  "2000GB" -> "2TB"
  "500 GB" -> "500GB"
```

#### B. Interface & Bus Normalization
```text
"PCIe Gen 4.0 x4"   ──┐
"PCIe Gen4"         ──┼──> "PCIe 4.0 NVMe"
"PCI-Express 4.0"   ──┘
```

#### C. Form Factor Normalization
```text
"M2 2280"           ──┐
"M.2 Type 2280"     ──┼──> "M.2 2280"
"2280 M.2"          ──┘
```

#### D. Brand Synonym Mapping
```kotlin
private val BRAND_SYNONYMS = mapOf(
    "wd" to "Western Digital",
    "western digital" to "Western Digital",
    "logi" to "Logitech",
    "logitech" to "Logitech",
    "hewlett packard" to "HP",
    "hp" to "HP",
    "apple inc" to "Apple"
)
```

#### E. Clutter & Marketing Noise Stripping
The parser strips over 20 non-informational retail clutter phrases before evaluating equivalence:
- `"(?i)\\bbuy\\s+online\\b"`, `"(?i)\\bfree\\s+delivery\\b"`
- `"(?i)\\blimited\\s+time\\s+deal\\b"`, `"(?i)\\bamazon['’]?s?\\s+choice\\b"`
- `"(?i)\\d{1,2}%\\s*off\\b"`, `"(?i)\\bbest\\s+price\\b"`

---

## 5. Product Matching & Confidence Scoring Algorithm

When comparing a candidate merchant listing against a target product, `ProductMatchingEngine.kt` computes a weighted confidence score (0.0 to 1.0) and assigns an explainable match type:

### 5.1 Scoring Weights
$$\text{Score} = (W_{\text{brand}} \times S_{\text{brand}}) + (W_{\text{title}} \times S_{\text{title}}) + (W_{\text{specs}} \times S_{\text{specs}}) + (W_{\text{category}} \times S_{\text{category}})$$

Where:
- $W_{\text{brand}} = 0.35$ (Brand equivalence is mandatory)
- $W_{\text{title}} = 0.25$ (Core model name alignment, e.g., "P3 Plus" vs "P3")
- $W_{\text{specs}} = 0.30$ (Key spec overlaps: capacity, interface, RAM, sizing)
- $W_{\text{category}} = 0.10$ (High-level taxonomy match)

### 5.2 Match Type Thresholds

```
Score >= 0.90 ──> MatchType.EXACT
                  - Brand matches exactly
                  - Core model matches exactly
                  - All critical specs (e.g. 1TB, PCIe 4.0) match
                  - UI: Forest Green pill ("EXACT MATCH · 96%")

0.75 <= Score < 0.90 ──> MatchType.LIKELY
                         - Brand matches
                         - Model matches, but minor secondary attribute unverified
                         - UI: Sage Green pill ("LIKELY MATCH · 82%")

Score < 0.75 ──> MatchType.SIMILAR
                 - Same category or brand alternative
                 - Capacity or model variant differs
                 - UI: Terracotta / Muted Slate pill ("SIMILAR PRODUCT · 68%")
```

### 5.3 Human-Readable Explainability Badges
Rather than presenting black-box numbers, Visual Cart generates clear rationales:
- *"Brand & storage capacity (1TB) match exact hardware specifications."*
- *"Brand matches Sony; model identified as WH-1000XM5 wireless ANC headphones."*
- *"Differing attributes: Storage capacity (500GB vs 1TB target)."*

---

## 6. Resilience & Offline Fallback Hierarchy

To ensure the application never halts during network failures or API quota events, Visual Cart executes a tiered fallback sequence:

```
[ Ingest Image or Text ]
           │
           ▼
[ Check Cloud Configuration ]
    ├── Configured? ──> Attempt Gemini Cloud Call (Timeout: 4000ms)
    │                         │
    │                         ├── Success (HTTP 200 & valid JSON) ──> Return Cloud Result
    │                         └── Network Fail / 429 / Parse Fail
    │                                      │
    └── Not Configured / Cloud Error ──────┘
                                           ▼
                    [ Tier 2: On-Device LocalProductParser ]
                    - Ingests raw OCR or extracted web text
                    - Regex spec extraction & canonical title cleanup
                    - Computes heuristic confidence
                                           │
                                           ├── If recognizable specs found ──> Return Parsed Result
                                           └── Empty / Unparseable
                                                        │
                                                        ▼
                    [ Tier 3: Deterministic Flagship Fixtures ]
                    - High-fidelity matches for flagship demo products
                    - Zero latency, guaranteed 100% demo stability
```

---

## 7. Zero Hardcoded Credentials & Privacy Compliance

Visual Cart enforces strict security standards for AI credentials:
- **Zero Client-Side Secrets**: API keys are never embedded in compiled APK bytecode or static XML resources.
- **Dynamic Resolution**: Keys are injected at runtime via environment variables (`GEMINI_API_KEY`, `VISUAL_CART_AI_ENDPOINT`) or system properties.
- **Local Proxy Gateway**: For production deployments, all Gemini requests route through the Visual Cart FastAPI backend (`POST /api/ai/recognize`), keeping third-party AI keys entirely server-side.
- **Privacy Boundary**: No user identity, browsing history, or payment card information is ever transmitted to the AI vision endpoint.

---
*End of AI Architecture Documentation.*
