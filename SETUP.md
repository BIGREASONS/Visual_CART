# PayPal Visual Cart - Developer Setup & Execution Guide

> **Document Version**: 1.0.0  
> **Date**: November 2026  
> **Target OS**: Android 8.0+ (API 26 to API 36) | Backend: Cross-platform (Windows, macOS, Linux)  
> **Visual Palette**: Warm Ivory (`#F6F3EC`), Deep Ink (`#202B32`), Forest Green (`#356B57`), Muted Sage (`#A6B5A0`), Terracotta (`#C97858`)

---

## 1. System Requirements & Prerequisites

### Android Development
- **JDK**: Java Development Kit 17 (Temurin 17 or Oracle JDK 17 recommended)
- **Android SDK**: API Level 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`)
- **Build Tool**: Gradle 9.1.0 (included via project Gradle Wrapper `gradlew` / `gradlew.bat`)
- **Device / Emulator**: Android 8.0 (Oreo) or higher; Android 14/15/16 recommended for Direct Share testing.

### Backend Gateway (Optional for live Gemini / PayPal REST calls)
- **Python**: Version 3.10, 3.11, or 3.12
- **Package Manager**: `pip` (or `uv`)

### API Accounts (Free Sandbox / Tier)
- **PayPal Developer Account**: Obtain Sandbox Client ID & Secret from [developer.paypal.com](https://developer.paypal.com/dashboard/applications/sandbox).
- **Google AI Studio Key**: (Optional) Obtain free API key from [aistudio.google.com](https://aistudio.google.com/app/apikey).
  *(Note: The Android app features built-in deterministic demo providers and local regex heuristics, allowing full evaluation even without live API keys!)*

---

## 2. Environment Configuration

### Step 2.1: Clone and Configure Environment Template
From the project root:

```powershell
# Copy the template to .env
Copy-Item .env.example .env
```

Open `.env` in your editor and configure your credentials:

```dotenv
# PayPal Sandbox Credentials
PAYPAL_CLIENT_ID=your_paypal_sandbox_client_id_here
PAYPAL_CLIENT_SECRET=your_paypal_sandbox_client_secret_here
PAYPAL_ENV=sandbox
PAYPAL_BASE_URL=https://api-m.sandbox.paypal.com

# Google Gemini Multimodal Vision API (Optional)
GEMINI_API_KEY=your_gemini_api_key_here
GEMINI_MODEL=gemini-1.5-flash

# Backend Host & Port
BACKEND_HOST=0.0.0.0
BACKEND_PORT=8000
CORS_ORIGINS=*

# Android Client Endpoint Configuration
VISUAL_CART_AI_ENDPOINT=http://10.0.2.2:8000/api/ai/recognize
VISUAL_CART_PAYPAL_ENDPOINT=http://10.0.2.2:8000/api/paypal
```

> **Note for Emulator Users**: In the Android emulator, `10.0.2.2` automatically routes to your host machine's `127.0.0.1`.

---

## 3. Running the FastAPI Backend Gateway

The FastAPI backend provides live endpoints for multimodal product parsing and PayPal Orders v2 token/order creation.

### Step 3.1: Create and Activate Virtual Environment
```powershell
# In the project root:
python -m venv venv

# On Windows PowerShell:
.\venv\Scripts\Activate.ps1

# On macOS/Linux:
# source venv/bin/activate
```

### Step 3.2: Install Python Dependencies
```powershell
pip install -r backend/requirements.txt
```

### Step 3.3: Start the Backend Server
```powershell
uvicorn backend.main:app --host 0.0.0.0 --port 8000 --reload
```

### Step 3.4: Verify Server Health
Open your web browser or run:
```powershell
curl http://localhost:8000/health
```
Expected output:
```json
{
  "status": "healthy",
  "environment": "sandbox",
  "paypal_base_url": "https://api-m.sandbox.paypal.com",
  "gemini_model": "gemini-1.5-flash"
}
```

Interactive OpenAPI Swagger documentation is available at [http://localhost:8000/docs](http://localhost:8000/docs).

---

## 4. Building & Running the Android Application

### Step 4.1: Run Unit Tests
Verify the entire test suite (security validation, spec parsing, matching engine, merchant normalization, and payment adapter):

```powershell
.\gradlew.bat testDebugUnitTest
```
All unit tests should complete with **BUILD SUCCESSFUL**.

### Step 4.2: Assemble Debug APK
Compile the debug application package:

```powershell
.\gradlew.bat assembleDebug
```
The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Step 4.3: Install onto Device or Emulator via ADB
Ensure your emulator or connected physical device is running (`adb devices`):

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 4.4: Launch Application
```powershell
adb shell am start -n com.paypal.visualcart/.MainActivity
```

---

## 5. System Permissions & OS Setup on Device

### 5.1 Overlay Permission (For Floating Cart Bubble)
To enable the draggable floating shopping bubble:
1. Open the Visual Cart app and navigate to **Settings** (or tap the floating bubble prompt).
2. Tap **"Enable Floating Overlay"**.
3. Android will prompt: *"Allow display over other apps"*. Toggle the switch to **Allowed**.
4. Alternatively, grant via ADB:
   ```powershell
   adb shell appops set com.paypal.visualcart SYSTEM_ALERT_WINDOW allow
   ```

### 5.2 Quick Settings Tile Setup
1. Swipe down twice from the top of your Android screen to open the full Quick Settings panel.
2. Tap the **Edit (Pencil)** icon to customize tiles.
3. Scroll down to find **"Visual Cart"** (with the cart icon).
4. Drag it into your active top tiles.
5. Tap the tile at any time to instantly toggle the floating bubble on or off!

### 5.3 Direct Share Shortcut Initialization
Upon first launch, `SharingShortcutsManager` automatically registers the dynamic shortcut with Android's system Sharesheet:
1. Open Google Chrome, Google Photos, or any web browser on your phone.
2. Long-press an image or open any product page.
3. Tap **Share**.
4. Look for **"Add to Visual Cart"** in the Direct Share target row at the top of the Sharesheet.

---

## 6. End-to-End Demonstration Walkthrough

### Scenario A: Ingest via System Direct Share
1. Open Chrome on your device and navigate to an e-commerce page (or use any product screenshot in Google Photos).
2. Tap **Share** -> Select **"Add to Visual Cart"**.
3. Visual Cart’s bottom popup dialog immediately appears over your current app.
4. Watch the AI parse the image into canonical specs (e.g., *Crucial P3 Plus 1TB PCIe 4.0 NVMe SSD*).
5. Review prices across Flipkart, Amazon India, and Croma.
6. Tap **"Save to Cart"** — it is instantly stored in your unified cart!

### Scenario B: Floating Bubble Screen Capture
1. Tap the Quick Settings tile or enable the overlay from app Settings.
2. The Forest Green (`#356B57`) circular bubble appears on the edge of your screen. Drag it anywhere; notice how it snaps gracefully to the nearest screen edge.
3. Tap the bubble to reveal the 4-action quick menu:
   - **Add from screenshot** (launches Photo Picker)
   - **Paste product link**
   - **Open my cart**
   - **Hide floating button**

### Scenario C: Multi-Merchant Price Comparison & PayPal Checkout
1. Open the main Visual Cart app.
2. Select any product (e.g., Crucial P3 Plus 1TB SSD).
3. Review the **30-Day Price Observation Graph** showing verified historical price drops.
4. Check the merchant availability list showing Flipkart as the **BEST DEAL** at ₹5,499 (saving ₹600 vs Amazon ₹6,099).
5. Tap **"Checkout with PayPal · ₹5,499"**.
6. Review the itemized order summary (Base Price: ₹4,660.17 + 18% GST: ₹838.83 + Free Delivery).
7. Tap **"Complete Payment (Demo)"** to authorize the simulated sandbox transaction and receive an authorized transaction ID.

---

## 7. Troubleshooting & FAQ

### Q: Does Visual Cart require live internet or paid API keys to test?
**A**: No! Visual Cart includes a dual-tier offline fallback. If no Gemini API key or backend is configured, it automatically deploys `DemoProductRecognitionProvider` and `LocalProductParser`, which provide 100% deterministic, high-fidelity parsing for flagship test items (Crucial SSD, Sony WH-1000XM5, Apple Watch Ultra 2, Nike Air Jordans).

### Q: Why does the emulator fail to connect to `http://localhost:8000`?
**A**: On the Android emulator, `localhost` refers to the emulator itself. To connect to your computer's development server, use `http://10.0.2.2:8000`.

### Q: What should I do if the floating bubble does not appear?
**A**: Ensure the overlay permission is granted (`Settings > Apps > Special app access > Display over other apps > Visual Cart`).

### Q: How do I clean the build cache if needed?
```powershell
.\gradlew.bat clean
```

---
*End of Setup & Execution Guide.*
