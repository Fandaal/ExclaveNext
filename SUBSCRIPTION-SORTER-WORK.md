# Exclave Next — Subscription Sorter: отчёт о проделанной работе

**Дата:** 2026-10-01/02
**Репозиторий:** `~/workspace/exclave`
**Ветка:** `feature/subscription-sorter` (ответвлена от `exclavenext` @ `6c523f41`)
**Коммит:** `881de570`
**Remote:** `github` = `git@github.com:Fandaal/ExclaveNext.git` → запушено как `github/feature/subscription-sorter`
**Пакет APK:** `com.exclavenext.app.debug`
**APK:** `app/build/outputs/apk/oss/debug/Exclave-0.17.57-next.1-arm64-v8a-debug.apk` (37.4 MB)

---

## Контекст задачи

Пользователь (Алексей) имеет зрелый Python CLI-скрипт `WhiteListVPN.py` (~3500 строк) —
комбайн обработки VPN-подписок: парсинг 10+ протоколов, URL-тест через sing-box,
GeoIP-аннотация, DNS, дедупликация, speed-тест. Хотел перенести функционал на Android.

**Принятое решение (после анализа 3 вариантов):** НЕ отдельное приложение и НЕ порт с нуля,
а **добавление недостающих функций в уже существующий Exclave Next** (форк SagerNet/NekoBox).
Причина: ~1500 строк Python (вся декодирующая часть) в Exclave уже реализованы и
переиспользуются бесплатно (`RawUpdater.parseRaw`), Go-ядро `libexclavecore` уже даёт
`urlTest`, есть БД/группы/UI/SAF.

**Планы (сохранены):**
- `.hermes/plans/2026-10-01_000000-whitelistvpn-kotlin-port.md` — вариант полного порта (отвергнут)
- `.hermes/plans/2026-10-01_010000-exclave-sorter-features.md` — ПРИНЯТЫЙ план (с разделом СТАТУС/РЕШЕНИЯ)

**Решения пользователя (зафиксированы):**
1. Гео/скорость хранить ТОЛЬКО в теге имени профиля (в `bean.name`), новых колонок БД НЕ создавать → Room-миграция не нужна.
2. Аннотация пишется в имя (см. п.1).
3. GeoIP API-fallback НУЖЕН (локальная MMDB покрывает не всё).

---

## Что реализовано (5 функций)

### A. Импорт всех .txt из выбранной папки
- **Файл:** `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt`
- `importFolder` — `ActivityResultContracts.OpenDocumentTree()` + `takePersistableUriPermission` +
  `DocumentFile.fromTreeUri(...).listFiles()` → фильтр `.txt` → читает каждый →
  `RawUpdater.parseRaw(text)` → собирает в один список → `import(proxies)`.
- Пункт меню `action_import_folder` в `add_profile_menu.xml`, обработчик в `onMenuItemClick`.
- Строки: `action_import_folder`, `no_txt_in_folder`.
- Зависимость: `androidx.documentfile:1.0.1`.

### B. Единая группа тестирования из разных источников + ФИКС импорта подписки
- Конфиги из URL/файла/буфера/папки складываются в **текущую выбранную группу** через существующий `import()`.
- **КРИТИЧЕСКИЙ ФИКС (жалоба пользователя):** раньше вставка **ссылки на подписку** из буфера/файла
  вызывала `MainActivity.importSubscription()`, который ВСЕГДА создавал новую группу типа SUBSCRIPTION.
- Новый метод `fetchSubscriptionProxies(url)` в ConfigurationFragment: качает подписку через
  `Libexclavecore.newHttpClient()` (как `RawUpdater`), парсит `parseRaw`, импортит в текущую группу.
- Заменены оба вызова `importSubscription(...)` (в `action_import_clipboard` и `importFile`).

### C. N прогонов URL-теста
- **Файл:** `ConfigurationFragment.kt :: urlTest()` — переписан на многораундовую логику.
- Ведётся очередь `pending`; после раунда профили со `status==1` (ok) выбывают, остальные (`status==3`)
  идут в следующий раунд. Профиль «мёртв» ТОЛЬКО если провалил ВСЕ раунды (порт логики из Python `process_file`).
- `PluginNotFoundException` (status -1) не ретраится.
- Настройка: `CONNECTION_TEST_ROUNDS` (Constants.kt, дефолт 1), `connectionTestRounds` (DataStore.kt),
  preference `connectionTestRounds` в `global_preferences.xml`, строка `connection_test_rounds`.

### D. MaxMind GeoIP + DNS + country/ISP + API-fallback
- **Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/GeoIpAnnotator.kt`
- `object GeoIpAnnotator`:
  - MMDB `com.maxmind.geoip2.DatabaseReader` — Country + ASN, файлы в `externalAssets`
    (`GeoLite2-Country.mmdb`, `GeoLite2-ASN.mmdb`).
  - `resolveToIp(host)` — DNS через `InetAddress.getAllByName` (IPv4 приоритет), кэш.
  - `lookup(ip)` — локальная MMDB → при промахе `apiLookup`.
  - `apiLookup` — freeipapi.com + i.pn, per-API circuit-breaker (CIRCUIT_THRESHOLD=10, timeout=60с),
    экспоненциальный backoff с jitter (порт `get_country_from_api`).
  - `asnProvider` — первое слово ASN-org, пропуск артиклей (the/llc/inc/ltd/ooo/jsc).
  - `codeToFlag` — Regional Indicators (🇩🇪).
  - `annotateName` / `stripGeoTag` — пишет/заменяет тег `🇩🇪 Germany (Hetzner)` в `bean.name`
    (не плодит хвосты при ре-аннотации, regex `[\x{1F1E6}-\x{1F1FF}]{2}...(...)$`).
- UI: `ConfigurationFragment.annotateGeoip()` + пункт меню `action_annotate_geoip`,
  строки `action_annotate_geoip`, `geoip_db_missing`, `annotate_done`.
- Зависимость: `com.maxmind.geoip2:geoip2:4.2.1` (тянет Jackson — уложилось в multidex, конфликтов нет).

### E. Тест скорости
- **Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/test/SpeedTestInstance.kt`
- `class SpeedTestInstance : V2RayInstance` — поднимает профиль как ЛОКАЛЬНЫЙ SOCKS inbound:
  `buildConfig()` берёт `buildV2RayConfig(profile, forTest=true)` и ИНЪЕЦИРУЕТ socks-inbound на
  свободный порт в JSON (forTest-конфиг сам по себе локальных inbound не имеет).
  Затем ping + download через `HttpURLConnection` с `Proxy(SOCKS, 127.0.0.1:port)`.
- UI: `ConfigurationFragment.speedTest()` + меню `action_speed_test`, строка `speed_test_done`.
  Тестирует профили со `status==1` (прошедшие URL-тест), иначе всю группу. Воркеров=1 (чтобы не делить канал).
  Маркеры скорости (✨/⭐️/🏁/🏳️/🏴) + Mbps пишутся в начало `bean.name` ЗАМЕНОЙ старого значения
  (`GeoIpAnnotator.stripSpeedMarker()`; накопленные legacy-значения "🏁 24.7 23.9 24.0" схлопываются).
  Пороги: ≥50 ✨, ≥25 ⭐️, ≥10 🏁, >0 🏳️. Особые случаи (2026-10-02, проверено на устройстве):
  - пинг прошёл, но загрузка дала 0.0 Мбит/с → `🏴 0.0 <имя>`;
  - пинг не прошёл (`!alive`) → `🏴 <имя>` БЕЗ числа (раньше имя не трогалось вообще);
  - `SPEED_MARKER_REGEX` матчит и `🏴 0.0`, и голый `🏴` (числовая группа `*` = ноль повторов).
  ВАЖНО про установку: debug-сборка имеет пакет `com.exclavenext.app.debug` (applicationIdSuffix)
  и НЕ заменяет основной `com.exclavenext.app` — тестировать нужно debug-приложение либо ставить
  release-APK (подпись release.keystore совпадает, обновление проходит без потери данных).

### Финальная логика аннотирования (2026-10-02)
- `GeoIpAnnotator.annotateName()` ПОЛНОСТЬЮ ЗАМЕНЯЕТ `bean.name` на тег `🇸🇪 Sweden (Alexhost)` —
  базовое имя подписки НЕ сохраняется (осознанное требование). Если GeoIP не определился —
  имя чистится от старых флагов (`stripGeoTag`).
- `stripGeoTag`/`geoTagOf` — императивный поиск первого флага страны через `codePointAt`
  (U+1F1E6..U+1F1FF) + substring. Regex-классы `[...]` в JVM ломаются на surrogate pairs —
  использовать их для флагов нельзя (проверено: `\x{...}` вне класса работает, в классе — нет).
- `SPEED_MARKER_REGEX` — строго 5 разрешённых эмодзи (✨ ⭐️ 🏁 🏳️ 🏴) как альтернация НЕ класс,
  числа опциональны (мёртвый прокси "🏴 name" тоже затирается), повтор чисел `(...)*` лечит
  legacy-накопления. ⚡ и прочие эмодзи из имён подписок не трогаются.
- Симуляция всей логики стриппинга: `tools/test_strip_logic.py` (Python-порт, все кейсы зелёные).

---

## Изменённые / новые файлы (в коммите 881de570)

**Новые:**
- `app/src/main/java/io/nekohasekai/sagernet/bg/GeoIpAnnotator.kt`
- `app/src/main/java/io/nekohasekai/sagernet/bg/test/SpeedTestInstance.kt`

**Изменены:**
- `app/build.gradle.kts` — +documentfile, +geoip2
- `gradle/libs.versions.toml` — версии documentfile 1.0.1, geoip2 4.2.1
- `app/src/main/java/io/nekohasekai/sagernet/Constants.kt` — CONNECTION_TEST_ROUNDS + Key
- `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — connectionTestRounds
- `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt` — importFolder,
  fetchSubscriptionProxies, urlTest (многораундовый), annotateGeoip, speedTest, меню-обработчики
- `app/src/main/res/menu/add_profile_menu.xml` — 3 пункта меню
- `app/src/main/res/values/strings.xml` — строки
- `app/src/main/res/xml/global_preferences.xml` — preference connectionTestRounds
- `aboutlibraries.json` / `aboutlibraries_legacy.json` — автогенерация (geoip2)

---

## Сборка (проверенный runbook — РАБОТАЕТ)

```bash
cd ~/workspace/exclave
export JAVA_HOME="$PREFIX/lib/jvm/java-21-openjdk"   # НЕ 17 (compileOptions=VERSION_21)
export ANDROID_HOME="$HOME/android-sdk"
export TMPDIR="$PREFIX/tmp"                           # НЕ /tmp (на Android нет /tmp)
./gradlew :app:assembleOssDebug --no-daemon
# APK: app/build/outputs/apk/oss/debug/Exclave-0.17.57-next.1-arm64-v8a-debug.apk
```
- Нативные aapt2/aidl/apksigner/protobuf уже стоят (симлинки в `$PREFIX/bin`).
- `local.properties`: `sdk.dir=/data/data/com.termux/files/home/android-sdk`, keystore ExclaveNext2026.
- **Статус:** `:app:compileOssDebugKotlin` и `:app:assembleOssDebug` → BUILD SUCCESSFUL.
  Все новые ресурсы подтверждены в APK (`aapt2 dump resources`): action_import_folder,
  action_annotate_geoip, action_speed_test, connection_test_rounds, no_txt_in_folder.

---

## НЕ проверено на устройстве (требует ручного теста — следующий шаг)

1. **Импорт подписки в текущую группу** — ФИКС внесён, но пользователь ещё не подтвердил на новом APK.
2. **Speed-тест inbound** — поднимает ли `libexclavecore` одиночный инъецированный socks-inbound из
   forTest-конфига. Если ядро ругается на структуру — поправить формат инъекции в `SpeedTestInstance.buildConfig()`.
3. **GeoIP API JSON-ключи** — читаю `countryName`/`countryCode`. Если формат freeipapi/i.pn другой — поправить ключи в `GeoIpAnnotator.apiLookup`.
4. **MMDB-базы** — автозагрузчик НЕ сделан. Нужно вручную класть `GeoLite2-Country.mmdb` +
   `GeoLite2-ASN.mmdb` в `externalAssets` (getExternalFilesDir). Без них аннотация идёт только через API.
   Если нужно — добавить пункт меню «скачать GeoLite2» (URL из Python: P3TERX mirror,
   константы COUNTRY_DB_URL/ASN_DB_URL уже в GeoIpAnnotator).

---

## Возможные доработки (не делались)

- Автозагрузчик MMDB (кнопка в UI, по образцу AssetsActivity.updateCustomAsset).
- Сортировка группы по стране/IP (пункт меню) — в плане был Task C.5, не реализован.
- Таблица результатов speed-теста с сортировкой по DL (сейчас только маркер в имени + ping-поле).
- Настройка `speedTestConcurrency` (сейчас воркеров жёстко 1).

---

## Как продолжить в новой сессии

```bash
cd ~/workspace/exclave
git checkout feature/subscription-sorter   # вся работа здесь
git log --oneline -3
```
План с деталями: `.hermes/plans/2026-10-01_010000-exclave-sorter-features.md`.
Исходный Python-референс: вложение `WhiteListVPN.py` (функции-прототипы указаны в комментариях Kotlin-файлов).

---

## Продолжение (2026-10-02): мультиисточниковые группы

Поверх сортировщика добавлена отдельная крупная фича — **группа как контейнер
с несколькими обновляемыми подписками + ручными конфигами**. Полный отчёт по
реализации (фазы 1–4, затронутые файлы, решения) — в `MULTI-SOURCE-GROUPS-DESIGN.md`,
раздел «РЕАЛИЗАЦИЯ» в конце.

Суть: новая сущность `SubscriptionSource` (таблица `subscription_sources`, БД v38,
AutoMigration 37→38), колонка `ProxyEntity.sourceId` (0 = ручной профиль), апдейтеры
реконсилируют строго в пределах источника по стабильному ключу `matchKey()`
(адрес+порт+тип), гео/скорость-аннотация переживает обновление
(`GeoIpAnnotator.transferAnnotations`). UI: `SubscriptionSourcesActivity` +
`SubscriptionSourceEditActivity`, пункт «Источники подписок» в меню и настройках группы,
диалог «подписка/разово» при вставке URL. Группа теперь всегда `type=BASIC`.

Также в этой сессии дофикшен GeoIP-баг «ISP всегда Unknown»: API дёргается, если
страна ИЛИ provider не определены; список API → ipwho.is → i.pn → freeipapi (ISP
из connection.isp/isp/asName/asnOrganization), freeipapi с instanceFollowRedirects.

Собрано нативно, APK проверен на устройстве. Остаток: share/бэкап группы с
несколькими источниками (сейчас share-формат одноподписочный), автозагрузчик MMDB.
