# StealthGuard — Testing Checklist
**Run this checklist after every fresh APK install.**

---

## Pre-Test Setup

- [ ] APK installed via ADB or direct transfer
- [ ] All permissions granted (verify in Admin → SETUP tab — all rows show ✅)
- [ ] Battery optimization exempted
- [ ] At least 1 GENTS hook audio recorded
- [ ] At least 1 LADY hook audio recorded
- [ ] At least 2 filler clips per gender recorded
- [ ] At least 1 keyword mapping added (e.g., "market")
- [ ] At least 1 SOS contact configured with a real number you can receive on
- [ ] Offline language pack installed:
      Settings → General Management → Language → Text-to-speech → your language → Download

---

## Phase 1 — Service Survival

### Test 1.1: Background Service Alive
```
1. Open app once → close it (swipe from recents)
2. Wait 30 seconds
3. Pull down notification shade
Expected: "Battery Monitor — Optimizing..." notification visible
```
- [ ] PASS  /  [ ] FAIL

### Test 1.2: Boot Survival
```
1. Restart the device completely
2. After boot, pull down notification shade
Expected: Notification visible within 30 seconds of unlock
```
- [ ] PASS  /  [ ] FAIL

### Test 1.3: Force Kill Survival
```
1. Settings → Apps → System Cache Manager → Force Stop
2. Wait 20 seconds
Expected: Notification reappears (WorkManager restarts it)
Note: May take up to 15 minutes on first attempt due to WorkManager scheduling
```
- [ ] PASS  /  [ ] FAIL

---

## Phase 2 — Triggers

### Test 2.1: Volume Button Fake Call Trigger (Primary)
```
1. Lock phone
2. Press Volume UP 4 times quickly (within 5 seconds)
Expected: Fake incoming call screen appears over lockscreen
          Caller name matches a persona from your configured pool
```
- [ ] PASS  /  [ ] FAIL
- Persona shown: _______________

### Test 2.2: Volume Button SOS Trigger
```
1. Keep phone unlocked (DO NOT send real SOS to test number unless ready)
2. Press Volume DOWN 5 times quickly
Expected: Phone vibrates 3 times (SOS sent confirmation)
          SMS received on test number with Maps link
NOTE: Turn off Wi-Fi and mobile data first to test pure SMS path
```
- [ ] PASS  /  [ ] FAIL
- SMS received: [ ] YES  [ ] NO
- Maps link correct: [ ] YES  [ ] NO

### Test 2.3: Corner Triple-Tap Trigger (Backup)
```
1. Open app (sees Cache Cleaner screen)
2. Triple-tap the bottom-right corner quickly
Expected: Fake call screen launches
```
- [ ] PASS  /  [ ] FAIL

### Test 2.4: Corner Triple-Tap + Hold (Backup SOS)
```
1. Open app
2. Triple-tap corner AND hold the 3rd tap for 2+ seconds
Expected: Phone vibrates 3 times (SOS triggered)
```
- [ ] PASS  /  [ ] FAIL

---

## Phase 3 — Admin Panel

### Test 3.1: PIN Gateway
```
1. Open app → see "System Cache Manager"
2. Long-press the "Clear Cache" button for ~700ms
Expected: PIN dialog appears with title "System Access"
3. Enter correct PIN → Admin panel opens with 6 tabs
4. Enter wrong PIN 3 times → dialog stops appearing for 60 seconds
```
- [ ] PASS  /  [ ] FAIL

### Test 3.2: Audio Recording
```
1. Admin → GENTS tab → FAB (+ button) next to HOOK AUDIO
2. Tap Record → speak a sentence → tap Stop → tap Save
Expected: New file appears in HOOK AUDIO list
3. Tap ⭐ (star) icon → file marked ACTIVE
4. Tap ▶ (play) → audio plays back correctly
```
- [ ] PASS  /  [ ] FAIL

### Test 3.3: Keyword Mapping
```
1. Admin → GENTS tab → FAB next to RESPONSE AUDIO
2. Enter keyword "help" in the keyword field → Record response → Save
Expected: Entry appears in MATRIX tab: "help" → [GENTS file]
```
- [ ] PASS  /  [ ] FAIL

---

## Phase 4 — Fake Call Engine (Dry Run 1: Quiet Environment)

### Test 4.1: Full Call Flow
```
1. Trigger fake call (4× Volume UP)
2. Verify: Caller name shown, phone number shown
3. Tap Accept (green button)
4. Start timer
Expected at ~0s: Hook audio plays (pre-recorded sentence)
Expected at ~3s: STT listening (should be silent)
5. Speak keyword "help" (or your configured keyword)
Expected: Correct response audio plays within 1-2 seconds
6. Stay silent for 4 seconds
Expected: Random filler audio plays within 3 seconds of silence
```
- [ ] PASS  /  [ ] FAIL
- Hook played: [ ] YES  [ ] NO
- Keyword response triggered: [ ] YES  [ ] NO  (keyword used: _______)
- Filler played on silence: [ ] YES  [ ] NO

### Test 4.2: Proximity Sensor
```
1. Trigger and accept fake call
2. Bring phone to your ear (cover proximity sensor)
Expected: Screen goes completely black within 1 second
3. Pull phone away from ear
Expected: Screen brightness restores immediately
```
- [ ] PASS  /  [ ] FAIL

### Test 4.3: Panic Cancel (3-Finger Tap)
```
1. Trigger and accept fake call
2. While call is in-call phase, tap screen with 3 fingers simultaneously
Expected: Activity closes instantly, silently, no animation
```
- [ ] PASS  /  [ ] FAIL

### Test 4.4: Lockscreen Trigger
```
1. Lock phone (press power button)
2. Press Volume UP 4 times quickly
Expected: Screen wakes, call UI appears over lockscreen without PIN entry
```
- [ ] PASS  /  [ ] FAIL

---

## Phase 4 — Dry Run 2: Noisy Environment

### Test 4.5: STT in Noise
```
Setup: Play background noise (traffic/crowd) at medium volume
1. Trigger and accept fake call
2. Do NOT speak any keywords for 5 seconds
Expected: Filler audio plays automatically (3s timeout fires)
3. In the noise, speak your keyword clearly
Expected: Correct response plays (may take 1-2 attempts)
```
- [ ] Filler auto-played after silence: [ ] YES  [ ] NO
- [ ] Keyword recognized in noise: [ ] YES  [ ] NO  [ ] PARTIAL

### Test 4.6: STT Completely Fails
```
Setup: Cover microphone with your finger
1. Trigger and accept fake call
2. Wait 5 seconds
Expected: Filler audio loops continuously (app does not freeze or crash)
3. Uncover microphone, speak keyword
Expected: Keyword may or may not be detected (mic was covered)
Expected either way: App continues running, no crash
```
- [ ] PASS  /  [ ] FAIL

---

## Phase 5 — SOS (Full Test)

### Test 5.1: SOS with Location
```
Pre: Stand outside or near a window for GPS fix
     Have the SOS test number ready to receive
1. Press Volume DOWN 5 times quickly
Expected within 10 seconds:
  - Phone vibrates 3 times
  - SMS received on test number
  - SMS contains a valid Google Maps URL
  - Maps URL opens to correct approximate location
```
- [ ] Vibration confirmed: [ ] YES  [ ] NO
- [ ] SMS received: [ ] YES  [ ] NO
- [ ] Maps link correct: [ ] YES  [ ] NO

### Test 5.2: SOS Without Data (Pure SMS)
```
Pre: Turn OFF Wi-Fi AND Mobile Data
1. Press Volume DOWN 5 times quickly
Expected: SMS still arrives (SMS uses cellular voice channel, not data)
```
- [ ] PASS  /  [ ] FAIL

### Test 5.3: SOS Without Location (Airplane Mode)
```
Pre: Enable Airplane Mode (turns off GPS + data + calls)
     Then re-enable SMS only (if your ROM allows)
     OR test with only Wi-Fi disabled + location disabled in settings
1. Press Volume DOWN 5 times quickly
Expected: SMS received with "Location unavailable. Find me urgently." instead of Maps link
```
- [ ] PASS  /  [ ] FAIL
- Message text received: ________________________________________________

---

## Phase 6 — Hardening

### Test 6.1: Fresh Install — No Audio Recorded
```
Clear app data (Settings → Apps → System Cache Manager → Clear Data)
1. Grant permissions, set PIN
2. Trigger fake call (4× Volume UP) WITHOUT recording any audio
Expected: Call screen appears. After accepting:
  - No crash
  - Silent (silence.wav plays as fallback)
  - STT listens (if offline pack installed)
  - App does NOT freeze
```
- [ ] PASS  /  [ ] FAIL

### Test 6.2: SETUP Tab Status
```
Admin → SETUP tab
Expected: All 6 rows show ✅ (if all permissions granted)
Tap "Open Settings →" on any non-granted row
Expected: Correct Settings screen opens
Return to app → row updates to ✅ automatically
```
- [ ] All rows ✅: [ ] YES  [ ] NO
- [ ] Settings redirect works: [ ] YES  [ ] NO
- [ ] Auto-refresh on return: [ ] YES  [ ] NO

---

## Regression Checklist (Run after any code change)

| Feature                          | Expected       | Result |
|----------------------------------|----------------|--------|
| Fake Cache Cleaner UI visible    | No app hints   |        |
| Long-press opens PIN dialog      | Yes            |        |
| Wrong PIN shows nothing          | Silent         |        |
| Corner trigger fires call        | Yes            |        |
| Vol 4× UP fires call             | Yes            |        |
| Vol 5× DOWN fires SOS            | Yes            |        |
| Call appears over lockscreen     | Yes            |        |
| Back button during call          | Does nothing   |        |
| 3-finger cancel works            | Silent close   |        |
| Proximity dims screen            | Yes            |        |
| STT timeout fires filler at 3s   | Yes            |        |
| Service alive after Force Stop   | Yes (WorkMgr)  |        |
| Service alive after reboot       | Yes            |        |
| SOS vibrates after send          | 3 pulses       |        |
| SMS arrives without data         | Yes            |        |
| Admin PIN change works           | Yes            |        |
| Audio delete cleans DB path      | Yes            |        |

---

## Known ROM-Specific Issues

| ROM       | Issue                                           | Fix                                        |
|-----------|-------------------------------------------------|--------------------------------------------|
| MIUI      | Service killed after ~5 min despite exemption   | Enable Autostart in Settings → Apps        |
| ColorOS   | STT offline pack not installable via Settings   | Download via Google Play → Speech Services |
| OneUI     | Overlay permission resets on app update         | Re-grant after each update                 |
| EMUI      | FusedLocation returns null on first boot        | SOS falls back to NETWORK_PROVIDER — OK    |

---
*Last updated: Phase 6 complete*
