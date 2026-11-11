# دفتر الكريدي

تطبيق أندرويد بالدارجة باش تسجّل الكريدي ديال الكليان فالحانوت، بلاصة الكناش ديال الورق.
كيخدم بلا أنترنيت، وجميع المعلومات كيبقاو فالتيليفون ديالك.

## شنو كيدير
- **الكليان:** زيد كليان بالسمية، التيليفون وملاحظة (بحال العنوان). تقدر تقلّب عليه بالسمية ولا بالرقم.
- **كريدي وخلاص:** فكل كليان عندك زر «+ كريدي» (شنو خدا وبشحال) وزر «− خلّص». كاين حتى زر «خلّص كلشي». وتقدر تختار تاريخ قديم إلا كنتي كتنقل من الكناش.
- **الحساب:** التطبيق كيحسب بوحدو شحال باقي على كل كليان، والمجموع ديال الكريدي اللي برّا. والكليان اللي عندهم الكريدي كيبانو هوما اللولين.
- **التاريخ:** كل عملية كتبان بالتاريخ والساعة وشحال بقا من بعدها. إلا غلطتي فشي عملية، ضغط عليها مدة باش تمسحها.
- **واتساب:** «💬 فكّرو» كيصيفط للكليان رسالة فيها شحال باقي عليه، و«🧾 الكشف» كيصيفط ليه الحساب كامل. و«📞 عيّط» كيعيّط ليه.
- **نسخة احتياطية:** من القائمة (⋮) تقدر تحفظ الدفتر كامل فملف وتخبيه فـ Drive ولا فالواتساب. ومن بعد تقدر ترجعو فتيليفون جديد.

## التحميل
GitHub Actions ← Build APK ← آخر تشغيل ← Artifacts ← **Daftar-Kredi-apk**،
ولا من صفحة Releases (الملف `Daftar-Kredi.apk`).

## Technical notes (English)
- Module `:kredi`, package `com.daftar.kredi`, plain Views + Material 3, no network permission.
- Storage: `KrediDb` (SQLite, two tables `customers` / `entries`). Amounts are whole centimes (`Long`).
- `Money`, `Ledger`, `Backup` are plain Kotlin and covered by `LedgerTest` (`./gradlew :kredi:testDebugUnitTest`).
- The UI is forced to Arabic (Morocco) with `AppCompatDelegate.setApplicationLocales("ar-MA")`.
