# Free-Tier AI Configuration (`gemini-3.5-flash-lite`)

Visual Cart V1.3 uses **Google Gemini 3.5 Flash-Lite** (`gemini-3.5-flash-lite`) on the **Google AI Studio free tier** exclusively, with a strict **`$0` zero-billing safeguard policy**.

---

## 1. Environment Variables

Configure these values in your gitignored `.env` file (`backend/.env`, `server/.env`, or root `.env` — never commit real keys):

```env
GEMINI_API_KEY=
GEMINI_MODEL=gemini-3.5-flash-lite
GEMINI_FREE_TIER_ONLY=true
AI_MAX_COST_USD=0
```

Official references:
- Get a free API key: https://aistudio.google.com/api-keys
- Model overview: https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite
- Free-tier pricing & limits: https://ai.google.dev/gemini-api/docs/pricing

---

## 2. Strict Zero-Billing & Anti-Fallback Safeguards

Both the FastAPI backend (`backend/gemini_client.py`, `backend/main.py`, `server/main.py`) and the Android client (`GeminiFreeVisionProvider.kt`, `CloudProductUnderstandingProvider.kt`) validate configuration on startup and before inference:

1. **Approved Model Allowlist**: Only `gemini-3.5-flash-lite` is permitted. Paid or heavier models (`gemini-2.5-pro`, `gemini-2.5-flash`, `gemini-3.8-flash`, `gemini-1.5-pro`, etc.) raise `FreeTierPolicyError` / `FreeTierPolicyException` (HTTP `400` via API proxy).
2. **Spend Cap Lock**: `GEMINI_FREE_TIER_ONLY=true` and `AI_MAX_COST_USD=0` must be enforced. Any attempt to set `AI_MAX_COST_USD > 0` or `GEMINI_FREE_TIER_ONLY=false` is rejected immediately.
3. **Zero Paid Fallbacks & Zero Search Grounding**: No automatic model upgrades, no Google Search grounding, and no secondary paid endpoints exist.
4. **Google AI Studio Billing Setup**: Ensure the Google Cloud project backing your `GEMINI_API_KEY` has **no billing account attached** so requests remain strictly on the free tier.

---

## 3. Quota, Rate-Limit (`429`), and Token Minimization

A free-tier API key is still governed by Google AI Studio RPM/RPD quotas:

- **HTTP `429` Circuit Breaker**: If a `429` rate-limit or quota exhaustion response occurs, Visual Cart immediately stops sending AI requests (60-second cooldown or `Retry-After` duration), performs **zero infinite retries**, and displays:
  > `"Free AI limit reached. Try again later."`
- **Graceful Local Fallback**: During rate limits or when `GEMINI_API_KEY` is absent/invalid, Visual Cart degrades cleanly to:
  - `LOCAL OCR` (`LocalProductParser` heuristic attribute extraction when readable product text exists)
  - `MANUAL SEARCH` (user-entered product name and direct merchant search URLs — never demo fixtures or fabricated prices)
- **Token & Bandwidth Efficiency**:
  - Real image bytes are validated (`PNG`/`JPEG`/`WEBP`/`GIF`) and downscaled locally to `<= 1024px` long edge and `<= 512 KB` (`compressed_for_quota`) before transmission.
  - Corrupt or truncated image payloads are rejected locally before making any network call.
  - Prompts use a compact `systemInstruction`, strict JSON schema, `maxOutputTokens: 350`, and `thinkingConfig: {"thinkingLevel": "MINIMAL"}` (`gemini-3.5-flash-lite` omits custom `temperature`).
  - Identical images are deduplicated in memory by **SHA-256 content hash** (`cache_hit: true`), preventing repeated quota usage.
  - One AI call per image capture; normalization, merchant matching, and search URL construction run deterministically in local Python/Kotlin code.

---

## 4. Provenance Badges in the Android UI

Every product card and capture sheet displays an explicit provenance pill:

| Badge | Enum Value | Meaning |
| --- | --- | --- |
| `LIVE AI` | `RecognitionSource.LIVE_AI` | Real multimodal identification returned by free-tier `gemini-3.5-flash-lite` |
| `LOCAL OCR` | `RecognitionSource.LOCAL_OCR` | Offline heuristic extraction from clean product text (`LocalProductParser`) |
| `MANUAL SEARCH` | `RecognitionSource.MANUAL_SEARCH` | Explicit manual query input or direct merchant search fallback |
| `DEMO DATA` | `RecognitionSource.DEMO` | Offline hackathon demo catalog fixtures (never mixed into live captures) |

---

## 5. Free-Tier Privacy Notice

Under Google AI Studio's **Free Tier** terms, Google may review prompts, images, and outputs to improve its products and train models. Do not share confidential, personally identifiable, or payment-card images while running on the free tier.

---

## 6. Mobile Connectivity (USB, Wi-Fi LAN, and Standalone 5G+)

On a physical Android device, Visual Cart resolves free-tier `gemini-3.5-flash-lite` inference in this order:

1. **Custom Backend URL (Optional in Settings -> Free-Tier AI Connection)**: e.g., `http://<PC-LAN-IP>:8000` on the same Wi-Fi network or an `https://` tunnel URL.
2. **USB ADB Reverse (`http://localhost:8000`)**: Run `adb reverse tcp:8000 tcp:8000` while the FastAPI backend is running on port `8000`. Note that Android clears `adb reverse` if USB disconnects or re-enumerates.
3. **Direct Google AI Studio Free-Tier Key (Optional in Settings)**: When away from USB/PC on **5G+ cellular or Wi-Fi**, save your free Google AI Studio key in **Settings -> AI Matching & Recognition Engine** (stored only in device-private `SharedPreferences`, still strictly locked to `gemini-3.5-flash-lite` and `$0` spend cap).

---

## 7. Verification Endpoints & Tests

- Health check (verifies model allowlist & $0 policy): `GET /health`
- Model reachability (GET without spending generation quota): `GET /api/v1/ai/model-status`
- Product recognition gateway: `POST /api/v1/ai/recognize-product`
- Backend test suite: `python -m pytest -v server/test_paypal_server.py backend/test_backend_security.py`
- Android test suite & APK build: `.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon`
