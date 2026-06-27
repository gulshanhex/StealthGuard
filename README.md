# StealthGuard
**Covert Personal Safety App — Android (Kotlin)**

> Camouflaged as "System Cache Manager". Fake call + silent SOS via volume button hold gestures.

---

## ⚠️ Before You Start — Two Binary Files Needed

This ZIP contains all source code. You need **two binary files** that cannot be distributed as source:

| File | How to get it |
|---|---|
| `gradlew` | Copy from any Android Studio project → root folder |
| `gradle/wrapper/gradle-wrapper.jar` | Copy from any Android Studio project |

**Quickest way:** Open Android Studio → New Project (any template) → copy `gradlew` and `gradle/wrapper/gradle-wrapper.jar` from that project into this repo root.

---

## Prerequisites

| Tool | Version | Download |
|---|---|---|
| Android Studio | Hedgehog 2023.1+ | https://developer.android.com/studio |
| JDK | 17 (Temurin) | Bundled with Android Studio |
| Android SDK | API 34 | Install via Android Studio SDK Manager |
| Offline STT Pack | Your language | Settings → General Management → Language → TTS |

---

## Method 1 — GitHub Actions (Recommended)

**Zero local setup required. APK builds in the cloud.**

### Step 1: Create GitHub Repository
```bash
git init
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
```

### Step 2: Add the two binary files
Copy `gradlew` and `gradle/wrapper/gradle-wrapper.jar` from a fresh Android Studio project.
```bash
chmod +x gradlew   # Make executable on macOS/Linux
```

### Step 3: Push code
```bash
git add .
git commit -m "Initial commit"
git push -u origin main
```

### Step 4: Download APK
```
GitHub → Your Repo → Actions tab
→ Click the latest workflow run (green ✓ or yellow ●)
→ Scroll to "Artifacts" section at the bottom
→ Download "StealthGuard-debug-apk-N"
→ Unzip → you have app-debug.apk
```

The workflow runs automatically on every push. Build takes ~3-5 minutes.

---

## Method 2 — Android Studio (Local Build)

### Step 1: Open project
```
Android Studio → File → Open → select this folder
Wait for Gradle sync to complete (2-5 min first time)
```

### Step 2: Build APK
```
Build menu → Build Bundle(s) / APK(s) → Build APK(s)
```
APK location after build:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Step 3: Install via ADB
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```
Or transfer to phone and open the file directly.

---

## Method 3 — Command Line (Fastest)

```bash
# From the project root directory:
chmod +x gradlew
./gradlew assembleDebug

# APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## Post-Install Setup (Manual — Do This Once)

After installing the APK, grant these permissions **in this order**:

### 1. Display Over Other Apps
```
Settings → Apps → System Cache Manager
→ Special App Access → Display over other apps → Allow
```

### 2. Accessibility Service (MOST IMPORTANT)
```
Settings → Accessibility → Installed Services
→ System Input Monitor → ON
```
> Without this, volume button triggers will NOT work.

### 3. Battery Optimization (CRITICAL for MIUI/ColorOS)
```
Settings → Apps → System Cache Manager → Battery → Unrestricted
```
Or open the app → Admin Panel → SETUP tab → tap "Open Settings →" next to Battery.

**MIUI extra steps:**
```
Settings → Apps → Manage Apps → System Cache Manager
→ Autostart → Enable
→ Battery Saver → No restrictions
```

**ColorOS extra steps:**
```
Settings → Battery → App Energy Management
→ System Cache Manager → Allow background activity
```

### 4. Location (for SOS GPS coordinates)
```
Settings → Apps → System Cache Manager → Permissions
→ Location → Allow all the time
```

### 5. Open app once to complete setup
```
1. Open "System Cache Manager" from app drawer
2. Long-press the "Clear Cache" button (~700ms)
3. Set your 4+ digit security PIN when prompted
4. Admin Panel opens → go to SETUP tab
5. Grant all remaining permissions shown there
```

---

## How to Use

### Initial Audio Recording (Required for Fake Call)
```
Admin Panel → GENTS tab (or LADY tab)
→ Tap + next to HOOK AUDIO → Record a sentence → Save → Set as Active (⭐)
→ Tap + next to FILLER AUDIO → Record 2-3 natural responses → Save
→ MATRIX tab → Add keywords with response audio (e.g., "market")
```

### Configure SOS
```
Admin Panel → SOS tab
→ Add up to 3 trusted contact phone numbers
```

### Configure Caller Personas
```
Admin Panel → PERSONAS tab
→ Default pool: Papa, Bhai, Chachu (GENTS), Mom, Didi, Bua (LADY)
→ Add/remove names as needed
```

---

## Trigger Reference

| Gesture | Action |
|---|---|
| Vol UP × 2 → hold 800ms | Fake Call |
| Vol UP × 3 → hold 800ms | Silent SOS |
| Triple-tap bottom-right corner | Fake Call (backup) |
| Triple-tap corner + hold 2s | SOS (backup) |
| 3-finger tap during call | Silent cancel |
| Long-press "Clear Cache" button | PIN → Admin Panel |

> **Tip:** The 1st volume press goes through normally (volume adjusts +1). This is intentional — it looks like a normal volume adjustment to a bystander.

---

## Project Structure

```
app/src/main/java/com/system/cacheclean/
├── StealthGuardApp.kt          Application class
├── audio/                      MediaRecorder + MediaPlayer wrappers
├── call/                       CallState, CallStateManager, PersonaRepository
├── db/                         Room DB (entities, DAOs, database)
├── model/                      Gender, AudioType enums
├── security/                   PinManager (AES-256 encrypted storage)
├── service/                    AccessibilityService, ForegroundService, WorkManager
├── sos/                        LocationResolver, SOSManager
├── storage/                    StorageManager, AudioResolver
├── ui/                         Activities, Fragments, Adapters
└── util/                       PermissionManager, BatteryHelper, SilenceGenerator
```

---

## Build Configuration

| Setting | Value |
|---|---|
| Package name | `com.system.cacheclean` |
| App label | System Cache Manager |
| Min SDK | API 24 (Android 7.0) |
| Target SDK | API 34 (Android 14) |
| Compile SDK | API 34 |
| Kotlin | 1.9.22 |
| AGP | 8.2.2 |
| Gradle | 8.2 |
| JVM target | 17 |

---

## Signed Release APK (Optional)

For a release build, add these GitHub Secrets to your repo:

| Secret Name | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 your_keystore.jks` output |
| `KEY_STORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Generate keystore:
```bash
keytool -genkey -v -keystore stealthguard.jks \
  -alias stealthguard -keyalg RSA \
  -keysize 2048 -validity 10000
```

Encode for GitHub Secret:
```bash
# macOS
base64 -i stealthguard.jks | pbcopy

# Linux
base64 stealthguard.jks | xclip -selection clipboard
```

Then uncomment the `build-release-apk` job in `.github/workflows/android.yml`.

---

## Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| Gradle sync fails | Missing SDK | SDK Manager → install API 34 |
| `gradlew: not found` | Binary missing | Copy from fresh Android Studio project |
| `gradle-wrapper.jar not found` | Binary missing | Same as above |
| Accessibility not detected | Service label mismatch | Disable → re-enable in Settings |
| Call doesn't appear on lockscreen | Overlay permission missing | Grant Display over other apps |
| SOS not sending | SMS permission missing | Grant in App Permissions |
| Service killed after minutes | Battery optimization | SETUP tab → fix Battery row |
| STT not working | No offline pack | Download from TTS settings |
| Build fails: "Unresolved reference" | Wrong JDK | Use JDK 17 only |

---

## Testing

See `TESTING_CHECKLIST.md` for complete dry-run procedures covering all 6 phases.

---

*StealthGuard — Personal use only*
