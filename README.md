# ريموت التلفزيون — Android TV و Samsung

تطبيق أندرويد للجوال يتحكم عبر الواي فاي في:
- تلفزيونات **Android TV / Google TV** (سوني، TCL، شاومي، فيليبس، …) بنفس بروتوكول ريموت Google الرسمي.
- تلفزيونات **Samsung** الذكية (Tizen، موديلات 2016 وأحدث).

ما يحتاج وضع المطوّر ولا ADB.

## المميزات
- يلقى التلفزيونات على نفس الشبكة تلقائياً (mDNS)، أو تكتب عنوان IP يدوياً.
- الاقتران مرة وحدة برمز من 6 خانات يظهر على شاشة التلفزيون، وبعدها يتصل التطبيق تلقائياً.
- الأزرار: تشغيل/إطفاء، الأسهم و OK، رجوع، الرئيسية، القائمة، الصوت وكتم الصوت، القنوات، المصدر، الإعدادات، تشغيل/إيقاف مؤقت، ترجيع وتقديم.
- الضغط المطوّل على أي زر يبقيه مضغوط على التلفزيون (للتمرير أو التقديم السريع).
- أزرار الصوت في الجوال تتحكم في صوت التلفزيون وأنت فاتح الريموت.
- اختصارات لفتح YouTube و Netflix.
- الواجهة بالعربي.
- **تنظيف وتغيير الواجهة (Android TV فقط):** زر «🧹 تنظيف وتغيير الواجهة» في الريموت:
  - **تنظيف:** يمسح الكاش لكل التطبيقات ويقفل التطبيقات اللي في الخلفية، ويقول لك كم فضّى.
  - **تغيير الواجهة (Launcher):** يعرض الواجهات المثبّتة على التلفزيون وتختار وحدة تصير الرئيسية (مثل Projectivy أو FLauncher). خيار لتعطيل واجهة Google TV الأصلية إذا كانت ترجع، وزر «استرجاع الواجهة الأصلية» يرجّع كل شي.
  - هذي بس تحتاج ADB: في التلفزيون فعّل خيارات المطوّر (اضغط 7 مرات على «البنية» في الإعدادات › النظام › حول) وبعدين «تصحيح أخطاء USB». أول مرة بيطلع على التلفزيون «السماح بتصحيح الأخطاء؟» علّم «السماح دائماً» واضغط موافق.

## طريقة الاستخدام
1. خلّ الجوال والتلفزيون على نفس شبكة الواي فاي.
2. افتح التطبيق واختر التلفزيون من القائمة (أو اكتب IP التلفزيون).
3. أول مرة:
   - **Android TV:** بيظهر رمز على التلفزيون، اكتبه في التطبيق واضغط «اقتران».
   - **Samsung:** بيطلع طلب على شاشة التلفزيون، اضغط «سماح» بالريموت الأصلي.
4. خلاص، الريموت جاهز.

## تحميل الـ APK
كل push للمستودع يبني APK تلقائياً عن طريق GitHub Actions:
**Actions ← Build APK ← آخر تشغيل ← Artifacts ← AndroidTvRemote-apk**.
فك الضغط وثبّت `app-debug.apk` على الجوال (لازم تسمح بالتثبيت من مصادر غير معروفة).

## البناء محلياً
```
./gradlew assembleDebug        # APK في app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # اختبارات البروتوكول ضد تلفزيون وهمي
```
يحتاج JDK 17 و Android SDK (أو افتح المشروع في Android Studio).

## How it works (English)
- `protocol/PairingClient.kt` — pairing on TCP 6467 over TLS with a self-signed client certificate; the TV shows a 6-hex-digit code and the secret is `SHA-256(client modulus, client exponent, server modulus, server exponent, code[2:])`.
- `protocol/RemoteClient.kt` — remote channel on TCP 6466: answers the TV's configure / set-active / ping messages and sends key presses and app links.
- `protocol/Proto.kt` — tiny hand-written protobuf encoder/decoder (no protobuf toolchain needed).
- `protocol/CertificateManager.kt` — generates the client certificate once (BouncyCastle) and keeps it in app storage, so the TV stays paired.
- `protocol/SamsungClient.kt` — Samsung Tizen WebSocket API (wss 8002, falls back to ws 8001); the TV asks the user to allow the app once and returns a token that is saved for later. Samsung TVs are discovered with SSDP.
- `protocol/AdbClient.kt`, `AdbKey.kt` — minimal ADB-over-TCP client (port 5555): CNXN/AUTH with an RSA key kept in app storage (signature + Android public-key format), then `shell:` streams. Works on TVs that accept classic `adb connect <ip>:5555` once USB debugging is on; TVs that only offer Android 11+ "wireless debugging" with a pairing code are not supported yet.
- `protocol/TvTools.kt` — cleaning (`pm trim-caches`, `am kill-all`) and launcher changes (`cmd package query-activities` / `set-home-activity`, optional `pm disable-user` of the stock launcher, undone by `pm enable`).
- `app/src/test/.../FakeTvTest.kt`, `FakeSamsungTest.kt`, `FakeAdbTest.kt` — run pairing and remote sessions against simulated TVs.
