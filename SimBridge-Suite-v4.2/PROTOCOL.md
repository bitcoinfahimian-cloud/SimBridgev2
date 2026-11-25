# SimBridge Independent Local Protocol Specification (v4.1 — Audited)

این مستند به‌طور مستقیم با پیاده‌سازی واقعی در `android/` و `ios/` هم‌تراز شده است.

---

## 1. جفت‌سازی QR و مدیریت کلید

فرمت URI داخل کد QR تولیدشده در `MainActivity.kt` و پارس‌شده در `BleBridgeClient.swift`:

```text
simbridge://pair?v=4&svc=8f2b0001-5b8f-4c2a-9d3e-2f6a1b0c9d01&ap=8119&k=<Base64URL_NoPad(32_bytes)>
```

- `k`: کلید تصادفی ۲۵۶ بیتی (`32 bytes`) رمزنگاری AES-256-GCM.
- ذخیره کلید: در اندروید داخل `SharedPreferences` خصوصی اپ (`allowBackup="false"`) و در iOS داخل **Keychain** (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).

---

## 2. قالب رمزنگاری (AES-256-GCM)

در `BleCrypto.kt` (Android) و `BridgeCrypto.swift` (iOS) دقیقاً یک قالب واحد استفاده می‌شود:

```text
EncryptedPacket = [ 12-byte Random Nonce (IV) ] || [ Ciphertext ] || [ 16-byte GCM Tag ]
```

---

## 3. صفحه کنترل روی BLE GATT

- **Service UUID:** `8f2b0001-5b8f-4c2a-9d3e-2f6a1b0c9d01`
- **Write Characteristic (iOS → Android):** `8f2b0002-5b8f-4c2a-9d3e-2f6a1b0c9d01`
- **Notify Characteristic (Android → iOS):** `8f2b0003-5b8f-4c2a-9d3e-2f6a1b0c9d01` (CCCD `00002902-0000-1000-8000-00805f9b34fb`)

### 3.1 فریم‌بندی ۴ بایتی (`BleFraming.kt` ↔ `BleFraming.swift`)
| Byte | فیلد | توضیح |
| :--- | :--- | :--- |
| `0` | `msgId` | شناسه چرخشی پیام (`0..255`) |
| `1` | `chunkIndex` | اندیس قطعه (`0..totalChunks-1`) |
| `2` | `totalChunks` | تعداد کل قطعات (`1..64`) — پیام‌های بزرگ‌تر از `64 * chunkLimit` به جای برش خاموش، رد می‌شوند |
| `3` | `flags` | `0x00` = متن ساده (صرفاً `auth_challenge`)، `0x01` = بسته AES-256-GCM |
| `4..N` | `payload` | داده قطعه |

- ارسال چندقطعه در اندروید با `Mutex` (`bleNotifyMutex`) و در iOS با صف سریالی مبتنی بر `didWriteValueFor` انجام می‌شود تا چانک‌های دو پیام هم‌زمان در هم قاطی نشوند.

### 3.2 احراز هویت دوطرفه BLE
1. آیفون CCCD را با `ENABLE_NOTIFICATION_VALUE` فعال می‌کند.
2. اندروید `auth_challenge` ساده (`flags=0x00`) می‌فرستد:
   ```json
   {"type":"auth_challenge","nonce":"<nonceA>","ver":4}
   ```
3. آیفون `auth_response` رمزنگاری‌شده (`flags=0x01`, `seq=1`) می‌فرستد:
   ```json
   {"type":"auth_response","nonce":"<nonceA>","client_nonce":"<nonceB>","seq":1,"device_name":"iPhone 15 Pro"}
   ```
4. اندروید پس از رمزگشایی و تایید `nonce == nonceA`، `auth_ok` رمزنگاری‌شده (`flags=0x01`, `seq=1`) می‌فرستد:
   ```json
   {
     "type":"auth_ok",
     "seq":1,
     "client_nonce":"<nonceB>",
     "audio_host":"192.168.43.1",
     "audio_port":8119,
     "call_audio_ready":true,
     "companion_registered":true,
     "companion_bound":false,
     "companion_events_count":0,
     "android_model":"M2007J3SG"
   }
   ```
5. آیفون `client_nonce == nonceB` را تایید می‌کند. از این پس همه بسته‌های کنترلی باید دارای `seq` اکیداً صعودی باشند.

### 3.3 پیام‌های کنترلی BLE
**Android → iOS:**
- `call_state`:
  ```json
  {"type":"call_state","seq":N,"state":"DIALING|RINGING|ACTIVE|IDLE","number":"+98912...","caller_name":"Ali","audio_active":true,"audio_host":"192.168.43.1","audio_port":8119}
  ```
- `sms_in`:
  ```json
  {"type":"sms_in","seq":N,"from":"+98912...","sender_name":"Ali","body":"...","ts":1730000000000}
  ```
- `audio_probe_result` (دقیقاً منطبق با کلیدهای JSON خروجی `ShellMain.kt`):
  ```json
  {
    "type":"audio_probe_result",
    "seq":N,
    "ok":true,
    "method":1,
    "telephony_tx_found":true,
    "tx_pinned":true,
    "audio_mode_before":2,
    "record_state":1,
    "track_state":1,
    "samples_read":40000,
    "non_zero_samples":38200,
    "downlink_dbfs":-24.3,
    "downlink_active":true
  }
  ```
- `pong`:
  ```json
  {"type":"pong","seq":N,"audio_host":"192.168.43.1","audio_port":8119,"call_audio_ready":true,"companion_registered":true,"companion_bound":false,"companion_events_count":0}
  ```

**iOS → Android:**
- `dial` (`number`), `answer`, `hangup`, `mute` (`muted`), `dtmf` (`digit`), `send_sms` (`to`, `body`), `probe_audio` (`method`), `ping`

---

## 4. صفحه صدای زنده روی UDP (`CallAudioStreamServer.kt` ↔ `UdpCallAudioClient.swift` — پورت 8119)

- **فرمت صوتی:** PCM 16-bit Signed Little-Endian، نرخ **16,000 Hz**، مونو (**1 channel**)، فریم‌های **20 ms** = **640 بایت** خام.
- **کنترل شماره توالی و ضد Replay:**
  - شمارنده `audioTxSeq` و `keepaliveTxSeq` در هر دو طرف **جداگانه** هستند تا بسته‌های Keepalive هرگز در شماره ترتیب فریم‌های صوتی سوراخ ایجاد نکنند.
  - هر دو طرف روی فریم‌های `0x10` از **پنجره لغزان ضد-Replay 64-بیتی** (حداکثر ۶۴ فریم عقب‌ماندگی مجاز برای بسته‌های خارج از ترتیب، رد فوری تکراری‌ها و بسته‌های قدیمی‌تر از `playoutFloor`) و روی `0x05` از مقایسه اکیداً صعودی `kaSeq > lastKeepaliveRxSeq` استفاده می‌کنند.
- **بازیابی خودکار پس از قطع Wi-Fi / تغییر IP:**
  - آیفون با `NWPathMonitor`، `viabilityUpdateHandler`، `betterPathUpdateHandler` و تایمر Timeout (`3.5s`) قطع ارتباط را تشخیص داده، `NWConnection` را بازسازی و چرخه احراز هویت (`0x01 -> 0x02 -> 0x03 -> 0x04`) را از نو اجرا می‌کند؛ اندروید پس از تایید `0x03`، آدرس/پورت جدید peer را جایگزین و پنجره‌های Replay/Jitter را تمیز ریست می‌کند.

### کلیدهای جداگانه برای هر جهت (از نسخه 4.2)
بسته‌های UDP با کلید اصلی `k` رمز **نمی‌شوند**؛ دو زیرکلید با HKDF-SHA256 (RFC 5869) ساخته می‌شود:
- `salt = "simbridge-udp-v4.2"` (UTF-8)، `ikm = k`، خروجی ۳۲ بایت (یک بلوک: `HMAC(PRK, info || 0x01)`).
- `c2s` با `info = "c2s"` — iOS → Android (بسته‌های `0x03` و هر `0x05`/`0x10` ارسالی از iOS).
- `s2c` با `info = "s2c"` — Android → iOS (بسته‌های `0x04` و هر `0x05`/`0x10` ارسالی از اندروید).
این کار از بازپخش (reflection) بسته‌ی یک جهت در جهت مقابل جلوگیری می‌کند. BLE همچنان با `k` است.

### فرمت صدا
روی سیم: PCM16LE، ۱۶ کیلوهرتز، مونو. iOS داخلی از Float32 مونو ۱۶ کیلوهرتز استفاده می‌کند و فقط هنگام ارسال/دریافت به Int16 تبدیل می‌کند. صف پخش iOS حداکثر ۸ فریم (۱۶۰ms) است.

### ساختار بسته‌های UDP (بایت اول = نوع بسته)
| Type | جهت | ساختار | طول کل |
| :--- | :--- | :--- | :--- |
| `0x01` | iOS → Android | `[0x01][16B clientNonce]` | 17 B |
| `0x02` | Android → iOS | `[0x02][16B serverNonce][16B clientNonce]` | 33 B |
| `0x03` | iOS → Android | `[0x03][AES-GCM(serverNonce \|\| clientNonce)]` | 61 B |
| `0x04` | Android → iOS | `[0x04][AES-GCM(clientNonce \|\| serverNonce)]` | 61 B |
| `0x05` | دوطرفه (هر 1s) | `[0x05][AES-GCM(uint32_be keepaliveSeq)]` | 33 B |
| `0x10` | دوطرفه (هر 20ms) | `[0x10][AES-GCM(uint32_be audioSeq \|\| uint32_be tsMs \|\| 640B PCM16LE)]` | 677 B |
