# دفتر الكريدي

تطبيق أندرويد بالدارجة باش تسجّل الكريدي ديال الكليان فالحانوت، بلاصة الكناش ديال الورق.
كيخدم بلا أنترنيت، وجميع المعلومات كيبقاو فالتيليفون ديالك.

## شنو كيدير
- **جوج دفاتر:** أونغلية **الكليان** (اللي عليهم ليك) وأونغلية **الفورنيسورات** (اللي عليك ليهم). كل وحدة فيها المجموع ديالها.
- **الفورنيسورات: فواتير ودفوعات**
  - «+ فاتورة»: المبلغ، **رقم الفاتورة**، **تاريخ الفاتورة**، و**تصويرة الفاتورة** (📷 بالكاميرا ولا 🖼 من الگاليري).
  - «− دفعة»: شحال عطيتيه ونهار.
  - كل فاتورة كتبان فيها «خالصة ✅» ولا «خلّصت X · باقي Y» (الدفوعات كيتحسبو على الفواتير القديمة هي اللولة).
  - فوق كيبان: الباقي، مجموع الفواتير ومجموع الدفوعات. والكشف فالواتساب فيه حتى هو المجاميع.
  - ضغط على فاتورة باش تبدلها، ولا على التصويرة الصغيرة باش تشوفها كبيرة.
- **الكليان:** زيد كليان بالسمية، التيليفون وملاحظة (بحال العنوان). تقدر تقلّب عليه بالسمية ولا بالرقم.
- **كريدي وخلاص:** فكل كليان عندك زر «+ كريدي» (شنو خدا وبشحال) وزر «− خلّص». كاين حتى زر «خلّص كلشي». وتقدر تختار تاريخ قديم إلا كنتي كتنقل من الكناش.
- **الحساب:** التطبيق كيحسب بوحدو شحال باقي على كل كليان، والمجموع ديال الكريدي اللي برّا. والكليان اللي عندهم الكريدي كيبانو هوما اللولين.
- **التاريخ:** كل عملية كتبان بالتاريخ ديالها وشحال بقا من بعدها. ضغطة وحدة باش تبدلها، وضغطة طويلة باش تمسحها.
- **واتساب:** «💬 فكّرو» كيصيفط للكليان رسالة فيها شحال باقي عليه، و«🧾 الكشف» كيصيفط ليه الحساب كامل. و«📞 عيّط» كيعيّط ليه.
- **نسخة احتياطية:** من القائمة (⋮) تقدر تحفظ الدفتر كامل فملف zip (فيه حتى تصاور الفواتير) وتخبيه فـ Drive ولا فالواتساب. ومن بعد تقدر ترجعو فتيليفون جديد.

## التحميل
GitHub Actions ← Build APK ← آخر تشغيل ← Artifacts ← **Daftar-Kredi-apk**،
ولا من صفحة Releases (الملف `Daftar-Kredi.apk`).

## Technical notes (English)
- Module `:kredi`, package `com.daftar.kredi`, plain Views + Material 3, no network permission.
- Storage: `KrediDb` (SQLite, two tables `customers` / `entries`; `customers.kind` is CUSTOMER or SUPPLIER since DB version 2). Amounts are whole centimes (`Long`).
- For a supplier, a CREDIT entry is goods the shop took on credit and a PAYMENT is the shop paying; `Labels` holds the wording for each kind. Invoices (supplier credits) carry `ref` and `photo` (DB version 3); photos are downscaled JPEGs in `filesDir/invoices` (`PhotoStore`), taken through a `FileProvider` URI, so no camera permission. `Ledger.paidPerCredit` settles payments against the oldest invoices first. Backups are a zip (`BackupArchive`: `data.json`, JSON format version 3, plus `invoices/*.jpg`); older bare-JSON backups (v1, v2) still restore.
- `Money`, `Ledger`, `Backup` are plain Kotlin and covered by `LedgerTest` (`./gradlew :kredi:testDebugUnitTest`).
- The UI is forced to Arabic (Morocco) with `AppCompatDelegate.setApplicationLocales("ar-MA")`.
