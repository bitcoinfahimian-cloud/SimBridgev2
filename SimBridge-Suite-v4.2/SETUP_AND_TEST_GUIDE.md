# راهنمای بیلد، نصب و چک‌لیست تست سخت‌افزاری (v4.1)

دستگاه‌های هدف:
- **Xiaomi Mi 10T Pro** (Android 13 / Snapdragon 865 / MIUI)
- **iPhone 15 Pro** (iOS 17+)

> هیچ مرحله‌ای تا زمانی که روی خود دو گوشی اجرا و مشاهده نشود «موفق» ثبت نمی‌شود.

---

## بخش ۱: بیلد و نصب

### Android (`android/`)
- **روش GitHub Actions:** پوشه را در مخزن Private آپلود کنید و ورک‌فلو `Build SimBridge Android APK` را اجرا کنید.
- **روش محلی:** پوشه `android/` را در Android Studio باز کرده و `Build > Build APK(s)` بزنید.
- **تنظیمات اجباری MIUI روی Mi 10T Pro:**
  1. در `Developer options`: روشن کردن `USB debugging`، **`USB debugging (Security settings)`** و `Wireless debugging`.
  2. در `App info` برنامه SimBridge: روشن کردن `Autostart`، تنظیم باتری روی `No restrictions`، قفل در Recents.
  3. خاموش کردن **Wi-Fi Calling (VoWiFi)** سیم‌کارت.
  4. جفت‌سازی Wireless Debugging از مسیر `Settings → Wireless debugging`.

### iOS (`ios/`)
- **روش Mac/Xcode:** داخل `ios/` دستور `xcodegen generate` را بزنید، پروژه را در Xcode باز کرده، Team و Bundle ID را تنظیم کنید و روی iPhone 15 Pro اجرا کنید.
- **روش GitHub Actions:** ورک‌فلو `Build SimBridge iOS IPA` را اجرا کرده و `SimBridgeiOS-Unsigned.ipa` را با SideStore/AltStore/Sideloadly روی iPhone 15 Pro نصب کنید.

---

## بخش ۲: چک‌لیست تست سخت‌افزاری (۷ آزمون اجباری)

نتیجه هر آزمون را پس از اجرا روی دو گوشی ثبت کنید:

1. **بررسی ثبت واقعی `CompanionInCallService`:**
   - پس از جفت‌سازی Wireless Debugging، وارد `Settings → Call audio` در اندروید شوید.
   - خط `CompanionInCallService: telecomReg=... | boundNow=... | events=...` را بررسی کنید.
   - یک تماس آزمایشی برقرار کنید و ببینید آیا `events` افزایش می‌یابد و `DIALING`/`ACTIVE`/`IDLE` ثبت می‌شود یا خیر.
2. **اثبات دریافت صدای مخاطب از تماس واقعی SIM (Downlink):**
   - حین تماس فعال SIM، دکمه **Run Real Call Audio Probe (2.5s)** را بزنید.
   - وقتی طرف مقابل صحبت می‌کند، خروجی `downlink_dbfs` باید بالاتر از `-45 dBFS` و `downlink_active: true` باشد.
3. **اثبات ارسال صدای میکروفون آیفون به تماس واقعی SIM (Uplink):**
   - حین همان پروب، از طرف مقابل بپرسید آیا بوق متناوب ۱ کیلوهرتز را شنید یا نه؛ در صورت نشنیدن روی متدهای `1 (Auto TX)`، `4 (Android 13 Call Redirect)` و `2 (Qualcomm incall_music)` تکرار کنید و متد موفق را ذخیره کنید.
   - سپس با اتصال iPhone 15 Pro، در تماس واقعی با میکروفون آیفون صحبت کنید و شنیده شدن شفاف توسط مخاطب را تایید کنید.
4. **بررسی صدای دوطرفه هم‌زمان (Full-Duplex):**
   - هر دو طرف هم‌زمان صحبت کنند (شمارش ۱ تا ۱۰) و مطمئن شوید قطع یک‌طرفه یا افت شدید رخ نمی‌دهد.
5. **بررسی اکو و تأخیر:**
   - در حالت گوشی (Earpiece) و سپس بلندگو (Speaker) تست کنید که مخاطب صدای خودش را با اکو نشنود و تأخیر مکالمه قابل قبول باشد.
6. **تست قطع و وصل Wi-Fi حین تماس:**
   - وسط یک تماس فعال، Wi-Fi آیفون را ۵ ثانیه خاموش و دوباره روشن کنید.
   - در تب Settings آیفون بررسی کنید مقدار `Reconnects` افزایش یابد، وضعیت دوباره به `Streaming` برگردد و صدای دوطرفه بدون قطع تماس CallKit ادامه پیدا کند.
7. **تست تماس ورودی، خروجی و صفحه قفل آیفون:**
   - صفحه iPhone 15 Pro را قفل کنید (اپ در پس‌زمینه باشد).
   - با خط سوم به سیم‌کارت Mi 10T Pro زنگ بزنید و بیدار شدن CallKit روی صفحه قفل با نام و شماره تماس‌گیرنده، پاسخ‌دهی، و سپس یک تماس خروجی از Dialer آیفون را تست کنید.
