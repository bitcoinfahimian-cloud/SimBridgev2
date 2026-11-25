# SimBridge Suite v4.1 (Audited — Xiaomi Mi 10T Pro + iPhone 15 Pro)

پروژه مستقل، محلی و بدون سرور برای انتقال تماس‌های سیم‌کارت اندروید به آیفون روی دو دستگاه هدف:
- **Android:** Xiaomi Mi 10T Pro (Android 13 / Snapdragon 865 / SIM فعال)
- **iOS:** iPhone 15 Pro (iOS 17+ / بدون سیم‌کارت)

> **وضعیت تست سخت‌افزاری (مهم):** تمام تغییرات این نسخه به‌صورت استاتیک و از نظر تطابق پروتکل ممیزی شده‌اند، اما **هیچ قابلیت صوتی، CallKit، یا Wireless Debugging تا پیش از اجرای چک‌لیست تست روی گوشی‌های واقعی (Mi 10T Pro و iPhone 15 Pro) «تاییدشده یا موفق» تلقی نمی‌شود.**

## ساختار واقعی فایل‌ها
- `android/` — پروژه اندروید (Kotlin + Jetpack Compose + AIDL)
  - `bridge/CallAudioStreamServer.kt` — سرور صدای تماس روی UDP پورت `8119` با پنجره ضد-Replay (64 فریم) و Jitter Buffer
  - `bridge/BleFraming.kt` و `bridge/BleCrypto.kt` — فریم‌بندی ۴ بایتی BLE GATT و رمزنگاری AES-256-GCM
  - `service/BridgeService.kt` — سرویس پس‌زمینه، کنترل چرخه تماس و صف‌بندی سریالی BLE
  - `call/CompanionInCallService.kt` — دریافت رویدادهای Telecom همراه با شمارنده و گزارش واقعی ثبت/اتصال
  - `shell/ShellMain.kt` و `shell/ShellBinderProvider.kt` — شل دسترسی `VOICE_DOWNLINK`، ۴ روش تزریق صدا و `probeCallAudio`
  - `adb/AdbWirelessManager.kt`, `AdbTlsClient.kt`, `Spake25519.kt`, `AdbKeyStore.kt` — جفت‌سازی Wireless Debugging با TLS 1.3 + SPAKE2 + TOFU Pinning
  - `net/OpenInternetServer.kt` — پروکسی محلی احراز هویت‌شده پورت `8118`
- `ios/` — پروژه iOS (Swift 5.9 + SwiftUI + CallKit + CoreBluetooth + Network.framework)
  - `Sources/Audio/UdpCallAudioClient.swift` — کلاینت UDP صوتی با بازیابی خودکار اتصال (`NWPathMonitor` + Watchdog)، احراز هویت مجدد، پنجره ضد-Replay و Voice Processing سخت‌افزاری
  - `Sources/Call/CallKitManager.swift` — مدیریت تفکیک‌شده تماس ورودی و خروجی CallKit
  - `Sources/BLE/BleBridgeClient.swift` و `Sources/BLE/BleFraming.swift` — کلاینت CoreBluetooth با State Restoration و صف‌بندی سریالی Write
  - `Sources/Crypto/BridgeCrypto.swift` — رمزنگاری AES-256-GCM (CryptoKit) و Keychain
- `PROTOCOL.md` — مشخصات دقیق و هماهنگ‌شده پروتکل Android ↔ iOS
- `SETUP_AND_TEST_GUIDE.md` — راهنمای بیلد و چک‌لیست ۷مرحله‌ای تست واقعی روی سخت‌افزار
- `.github/workflows/build-android-apk.yml` و `.github/workflows/build-ios-ipa.yml` — بیلد خودکار در GitHub Actions

## وضعیت وابستگی‌ها
- در `android/app/build.gradle.kts` نسخه `org.conscrypt:conscrypt-android:2.5.2` تنظیم شده است (برای سازگاری با Android 10–12). روی دستگاه هدف شما (Mi 10T Pro با Android 13)، کد `AdbTlsClient.kt` مستقیماً از API بومی `android.net.ssl.SSLSockets.exportKeyingMaterial` استفاده می‌کند.
