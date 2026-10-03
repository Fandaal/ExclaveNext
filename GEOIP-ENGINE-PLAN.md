# Exclave Next — движок GeoIP: настраиваемая цепочка провайдеров + локальные базы MaxMind

**Статус:** Фазы 0–6 реализованы (ветка `feature/geoip-settings`)
**Фаза 7 (автообновление):** не начата — сознательно отложена
**Дата:** 2026-10-02

Коммиты: `8b086f14` (тестовая инфраструктура) → `f0147cef` (цепочка резолверов) →
`4e3d4b33` (менеджер баз + регресс-тесты имён) → `c45e88ce` (UI).

**Найденное ограничение биндинга (проверено по исходникам ядра).**
`Libexclavecore.newHttpClient()` непригоден для условных запросов: `Execute()`
возвращает ошибку на любой статус ≠ 200, а `HTTPResponse` не отдаёт ни код
ответа, ни длину. 304 Not Modified там не представим — весь смысл ETag-кэша
теряется. Поэтому загрузка баз переведена на `HttpURLConnection`, как уже
сделано в `ApiResolver`. Цена: обновление базы идёт мимо туннеля при включённом
VPN. Поверхность биндинга проверяется так:
`unzip -q library/core/libexclavecore-sources.jar`.

**Не внесено осознанно:** автообновление (Фаза 7) — на экране настроек нет
переключателя, который никто не читает.

---

## 1. Задача

Сейчас GeoIP-аннотация работает на захардкоженной логике: две локальные MMDB в
`externalAssets` + фиксированный список из трёх публичных API. Настройки нет вообще —
в `DataStore` ни одного geo-ключа, в UI только пункт меню «Annotate country / ISP»
(`ConfigurationFragment.kt:785`), который при отсутствии баз показывает snackbar.

Нужно:

1. **Настройки с отображением в UI** — отдельный экран, а не диалог.
2. **Настраиваемая цепочка провайдеров с fallback-семантикой**: если провайдер не
   определил страну **и/или** провайдера — пробуем следующий, и так далее.
3. **Скачивание локальных баз MaxMind, их обновление** (и импорт из файла).
4. **Локальная база — полноправное звено цепочки**: её можно поставить **первым**
   звеном либо **одним из fallback**, в любой позиции, вместе с API.

---

## 2. Что уже есть (проверено по коду)

| Что | Где | Состояние |
|---|---|---|
| Движок | `bg/GeoIpAnnotator.kt` (427 строк) | монолит: DNS + MMDB + API + regex-хелперы имён |
| Локальные базы | `GeoLite2-Country.mmdb`, `GeoLite2-ASN.mmdb` в `SagerNet.externalAssets` (`SagerNet.kt:84`) | открываются лениво, `hasLocalDatabases()` проверяет **только** country-базу (стр. 105) |
| URL зеркал | `COUNTRY_DB_URL` / `ASN_DB_URL` (стр. 67–70) | объявлены, **нигде не используются** |
| Список API | `API_SERVICES` (стр. 74–78): ipwho.is, i.pn, freeipapi | захардкожен, порядок фиксирован |
| Fallback | `lookup()` стр. 236: API дёргается, если страница неизвестна **или** пуст провайдер | работает, но только для фиксированного набора |
| Circuit breaker | `ApiState` + `backoffDelay` (стр. 95–99, 256–260) | на API, не переносится на пользовательские URL |
| Перезагрузка ридеров | `reload()` (стр. 131–145) | есть, **ни один вызывающий** |
| Имена профилей | `stripGeoTag`, `geoTagOf`, `speedMarkerOf`, `stripSpeedMarker`, `transferAnnotations`, `annotateName` | рабочие, покрыты юнит-тестами вручную на устройстве; трогать нельзя |
| Перенос аннотаций | `RawUpdater.kt:238`, `SIP008Updater.kt:153`, `AgeUpdater.kt:223` | вызывают `transferAnnotations` |
| Действие в UI | `ConfigurationFragment.annotateGeoip()` стр. 1073–1150 | пул воркеров, прогресс-диалог, запись в `bean.name` |
| Загрузка ассетов | `AssetsActivity.updateCustomAsset()` стр. 390–412 | **образец**: `Libexclavecore.newHttpClient()` + UDS через живое ядро, `.tmp` → rename |
| Импорт ассетов | `AssetsActivity.importFile` стр. 133–164 | **жёстко отсекает всё, что не `.dat`** (стр. 142) → MMDB сюда не попадут |
| Метаданные базы | `geoip2:4.2.1` + `maxmind-db:3.1.1` в кэше Gradle | `DatabaseReader.getMetadata()` → `com.maxmind.db.Metadata.getBuildDate()` — дата сборки базы доступна |
| Настройки | `Constants.kt` (вложенный `object Key`) + `DataStore.kt:132–195` | geo-ключей **нет** |
| Экран-образец | `SubscriptionSourceEditActivity` (ThemedActivity + `layout_config_settings` + dirty/callback) | образец экрана настроек |
| Список-образец | `SubscriptionSourcesActivity.SourceAdapter` (RecyclerView) | образец редактора списка |
| Тесты | `app/src/` содержит только `main` и `oss` | **`testImplementation` нет, юнит-тестов нет** |

**Проверенные факты окружения:**

- Сборка: `./gradlew :app:assembleOssDebug` c `JAVA_HOME=$PREFIX/lib/jvm/java-21`,
  `ANDROID_HOME=$HOME/android-sdk`, `TMPDIR=$PREFIX/tmp`. Flavor `oss`, сплиты по ABI.
- Зеркала P3TERX живые: Country — 8 423 789 Б, ASN — 12 069 758 Б, оба отдают `ETag`
  (проверено `curl -sIL`, 302 → 200).
- `download.maxmind.com` без ключа → **401**. Значит официальный источник требует
  лицензионный ключ; зеркала — нет. Официальный путь делаем опциональным.
- GitCode-зеркало отдаёт HTML, а не MMDB — **не использовать**.
- JUnit 4.13.2 доступен из Maven Central (сеть есть), но в `libs.versions.toml` не прописан.
- `feature/subscription-sorter` **не запушен** (ahead 1, удалённой ветки нет).

---

## 3. Ключевые решения (зафиксированы, возражения — до старта)

1. **Цепочка — упорядоченный JSON-список в одном ключе** (`geoIpConfig`), а не плоские
   настройки: порядок звеньев и «кто что умеет» — это структура, а не набор флагов.
2. **Merge по полям, first-hit-wins.** Звено дописывает только те поля, которые ещё
   пусты; уже найденное не перезаписывается. Цикл идёт, пока не заполнены **оба** поля
   (страна + провайдер) либо цепочка не кончилась. Это буквально формулировка задачи.
3. **Каждое звено само объявляет, что умеет**: `mmdb-country` (страна), `mmdb-asn`
   (провайдер), `api` (страна, флаг, провайдер — частично). Это обобщение текущего кода:
   локальная ASN-база почти всегда отсутствует, поэтому провайдер почти всегда тянется
   из API.
4. **Дефолтная цепочка = текущее поведение** (`Country MMDB → ASN MMDB → ipwho.is →
   i.pn → freeipapi`). Миграция не должна ничего сломать.
5. **Отдельный экран `GeoIpSettingsActivity`**, вход из `SettingsPreferenceFragment`.
   Не в `AssetsActivity` — там импорт ограничен `.dat` и это семантика правил маршрутизации.
6. **Официальный MaxMind с лицензионным ключом — опция**, зеркало —default. Ключ
   хранится в настройке, но поле по умолчанию пустое.
7. **Юнит-тесты добавляем** (`junit:junit:4.13.2`) — fallback-алгоритм это ядро задачи,
   а он чистый Kotlin и тестируется без Android.

---

## 4. Архитектура

### 4.1 Модель

Новый файл `app/src/main/java/io/nekohasekai/sagernet/bg/GeoIpConfig.kt`:

```kotlin
object GeoIpEntryType {
    const val MMDB_COUNTRY = "mmdb-country"
    const val MMDB_ASN = "mmdb-asn"
    const val API = "api"
}

data class GeoIpEntry(
    val type: String = GeoIpEntryType.API,
    val enabled: Boolean = true,
    val url: String = "",       // API: шаблон с {ip}; MMDB: URL автообновления (пусто = вручную)
    val file: String = "",      // MMDB: имя файла в externalAssets
) {
    val providesCountry: Boolean get() = type != GeoIpEntryType.MMDB_ASN
    val providesProvider: Boolean get() = type != GeoIpEntryType.MMDB_COUNTRY
}

data class GeoIpChain(
    val entries: List<GeoIpEntry> = defaultEntries(),
    val autoUpdate: Boolean = false,
    val autoUpdateIntervalDays: Int = 7,
    val maxMindLicenseKey: String = "",
) {
    val active: List<GeoIpEntry> get() = entries.filter { it.enabled }
}
```

Сериализация — `parseJson` + `toJson` из `ktx/Json.kt` (Gson уже в зависимостях),
всё в один `DataStore` ключ. `defaultEntries()` возвращает текущий захардкоженный набор.

### 4.2 Движок

`GeoIpAnnotator.kt` разносится на три файла:

- **`GeoIpAnnotator.kt`** (остаётся) — публичный API, используемый UI и апдейтерами:
  `resolveToIp`, `lookup`, `annotateName`, `stripGeoTag`, `geoTagOf`, `speedMarkerOf`,
  `stripSpeedMarker`, `transferAnnotations`, `ensureInit`, `reload`.
  Regex-хелперы имён **не трогаем** — на них уже держится вся аннотация.
- **`GeoIpResolvers.kt`** (новый) — интерфейс и реализации:

```kotlin
interface GeoIpResolver {
    val type: String
    fun resolve(ip: String): GeoInfoFragment   // country/flag/provider — что смог
}

data class GeoInfoFragment(val flag: String = "", val country: String = "",
                          val provider: String = "", val failed: Boolean = false)
```

Реализации: `MmdbCountryResolver`, `MmdbAsnResolver`, `ApiResolver` (breaker/backoff
из старого кода, но состояние — в `ConcurrentHashMap<url, ApiState>`, чтобы работали
пользовательские URL).

- **`GeoIpDatabaseManager.kt`** (новый) — установленные базы, скачивание, обновление,
  импорт, удаление, валидация, `reload()`.

### 4.3 Алгоритм `lookup`

```kotlin
fun lookup(ip: String): GeoInfo {
    geoCache[ip]?.let { return it }
    ensureInit()
    var flag = ""; var country = "Unknown"; var provider = ""
    for (entry in DataStore.geoIpChain().active) {
        val resolver = resolverFor(entry) ?: continue
        val frag = resolver.resolve(ip)
        if (frag.failed) continue
        if (country == "Unknown" && frag.country.isNotEmpty()) {
            country = frag.country
            flag = frag.flag
        }
        if (provider.isEmpty() && frag.provider.isNotEmpty()) provider = frag.provider
        if (country != "Unknown" && provider.isNotEmpty()) break
    }
    val result = GeoInfo(flag, country, provider)
    geoCache[ip] = result
    return result
}
```

Ключевые отличия от текущего кода:

- `notFound` (карта «нет в локальной базе») **удаляется** — свою роль теперь играет
  цепочка: звено, не нашедшее IP, просто даёт пустой фрагмент, идём дальше.
- `hasLocalDatabases()` заменяется на `isChainUsable()` — предупреждение в UI должно
  говорить «не настроен ни один провайдер», а не «нет country-базы».
- `ApiState` больше не `val apiState = API_SERVICES.associateWith{...}` (список константный),
  а `ConcurrentHashMap` с ленивым созданием по URL.

### 4.4 Управление базами

`GeoIpDatabaseManager`:

```kotlin
data class InstalledDatabase(
    val file: String, val exists: Boolean, val sizeBytes: Long,
    val buildDate: Date?, val etag: String, val error: String?,
)
fun list(): List<InstalledDatabase>              // читает метаданные через DatabaseReader
fun download(entry: GeoIpEntry, onProgress: (Long, Long) -> Unit)
fun updateAll(onProgress: ...)
fun import(uri: Uri, fileName: String): InstalledDatabase
fun delete(fileName: String)
fun reload()                                     // -> GeoIpAnnotator.reload()
```

Правила, которые нельзя нарушить:

- **Валидация до замены.** Скачанное пишем в `<name>.tmp`, открываем
  `DatabaseReader.Builder(tmp).build()` — если исключение, `.tmp` удаляется, рабочая база
  не тронута. Это спасает от битой загрузки и от импорта не-mmdb.
- **Условный запрос.** ETag лежит рядом в `<name>.etag`; при `update` шлём `If-None-Match`,
  `304` → «уже актуально», не перезаписываем файл.
- **Файлы MMDB не попадают в `AssetsActivity`**: там `internalFiles = ["geoip.dat",
  "geosite.dat"]` и фильтр по `.dat`; наш импорт живёт в своём экране.
- Дефолтные имена файлов **не меняем** (`GeoLite2-Country.mmdb`, `GeoLite2-ASN.mmdb`
  прямо в `externalAssets`) — чтобы у кого-то уже скачанные базы продолжили работать.

### 4.5 UI

Вход: `SettingsPreferenceFragment` → новая `PreferenceCategory` «GeoIP» с тремя строками.

**`GeoIpSettingsActivity`** (`ThemedActivity`, шелл `R.layout.layout_config_settings`),
пункты:

1. **Provider chain** → `GeoIpProviderListActivity`
   - `RecyclerView` (`SourceAdapter` из `SubscriptionSourcesActivity` как образец)
   - строка: иконка типа, название (`GeoLite2-Country (local)` / `ipwho.is`), тумблер
     `enabled`, кнопка удаления
   - `ItemTouchHelper` для drag&drop = порядок в цепочке
   - FAB → диалог добавления: тип MMDB / API, для API — URL с подсказкой
     `https://ipwho.is/{ip}`
   - summary: «3 активных из 4»
2. **Local databases** → `GeoIpDatabaseActivity`
   - список установленных баз: имя, размер, **дата сборки базы** (из
     `Metadata.getBuildDate()`), ETag-дата загрузки
   - кнопки: обновить всё, обновить по строке, импорт из файла, удалить
   - progressbar на строку при скачивании
3. **License key** — `EditTextPreference`-строка (пусто = зеркала), только если выбран
   официальный источник.
4. **Test lookup** → диалог: ввод IP, кнопка «проверить» → **trace**: список звеньев
   с тем, что каждое вернуло (`ipwho.is → country=SE, provider=`, `i.pn → provider=Alexhost`),
   итоговый результат. Это и есть «отображение в UI» работы fallback-цепочки.
5. Переключатель автообновления + интервал (дней) — см. Фазу 6 (опционально).

Строки: `values/strings.xml` **и** `values-ru/strings.xml` (Алексей читает по-русски).

---

## 5. Фазы работ

Каждая фаза — коммит. TDD обязателен для Фаз 1–2 (чистая логика, тестируется).

### Фаза 0. Подготовка (5 мин)

**Задача 0.1** — запушить текущее состояние, чтобы не потерять работу.

```bash
cd ~/workspace/exclave
git push github feature/subscription-sorter
git checkout -b feature/geoip-settings
```
Проверка: `git branch -v | grep geoip` показывает новую ветку.

### Фаза 1. Тестовая инфраструктура (10 мин)

**Задача 1.1** — прописать JUnit.

- Modify: `gradle/libs.versions.toml` → `junit = { module = "junit:junit", version = "4.13.2" }`
- Modify: `app/build.gradle.kts` → `testImplementation(libs.junit)`

**Задача 1.2** — создать `app/src/test/java/io/nekohasekai/sagernet/bg/GeoIpChainTest.kt`
с одним тестом-заглушкой `chainPreservesOrder()`.

```bash
./gradlew :app:testOssDebugUnitTest --no-daemon
```
Ожидание: `BUILD SUCCESSFUL`, 1 тест. Это доказывает, что оффлайн-сборка тестов живая —
**иначе Фаза 2 невозможна, остановиться здесь**.

Коммит: `test: add JUnit 4 test infrastructure for GeoIP chain logic`

### Фаза 2. Модель цепочки (40 мин)

**Задача 2.1** — RED: тесты на merge-семантику в `GeoIpChainTest.kt`:

- `firstHitWinsPerField()` — звено A дал страну, звено B даёт другую → страна от A
- `providerFilledFromLaterEntry()` — страна от MMDB, провайдер пуст → идём к API
- `stopsWhenBothKnown()` — после заполнения обоих полей звено 3 **не вызывается**
- `disabledEntrySkipped()` — `enabled=false` не вызывается
- `orderIsSignificant()` — цепочка [ASN, Country] против [Country, ASN] даёт одинаковый
  итог, но разный порядок вызовов (фиксируем через `List<String> callLog`)

Запустить — ожидание FAIL (класс не существует).

**Задача 2.2** — GREEN: создать `bg/GeoIpConfig.kt` (модель из §4.1) +
`bg/GeoIpChainResolver.kt`:

```kotlin
object GeoIpChainResolver {
    fun resolve(ip: String, chain: List<GeoIpEntry>,
                factory: (GeoIpEntry) -> GeoIpResolver?): GeoInfo
}
```

Прогнать тесты — ожидание PASS.

Коммит: `feat(geoip): chain model + field-wise first-hit-wins resolver`

### Фаза 3. Ключи настроек (20 мин)

**Задача 3.1** — новые ключи в `Constants.kt` (`object Key`, рядом с
`GRPC_SERVICE_NAME_COMPAT`):

```kotlin
const val GEOIP_CONFIG = "geoIpConfig"
```

**Задача 3.2** — в `DataStore.kt` (рядом с `rulesGeoipUrl`, строка 176):

```kotlin
var geoIpConfigJson by configurationStore.string(Key.GEOIP_CONFIG)
val geoIpChain: GeoIpChain
    get() = parseGeoIpConfig(geoIpConfigJson)      // null/битый JSON -> GeoIpChain() (дефолты)
fun setGeoIpChain(chain: GeoIpChain) { geoIpConfigJson = chain.toJson() }
```

**Задача 3.3** — тест: `emptyConfigFallsBackToDefaults()` — битый JSON не роняет
приложение, отдаёт дефолтную цепочку.

Коммит: `feat(geoip): chain config in DataStore with safe defaults`

### Фаза 4. Перевод движка на цепочку (60 мин)

**Задача 4.1** — создать `bg/GeoIpResolvers.kt`: `GeoIpResolver`, `GeoInfoFragment`,
`MmdbCountryResolver`, `MmdbAsnResolver`, `ApiResolver` (перенести `providerFromJson`,
`shortenProvider`, `backoffDelay`, `codeToFlag` из `GeoIpAnnotator`).

**Задача 4.2** — `ApiResolver` со состоянием в `ConcurrentHashMap`:

```kotlin
private val states = ConcurrentHashMap<String, ApiState>()
private fun stateFor(url: String) = states.computeIfAbsent(url) { ApiState() }
```

**Задача 4.3** — переписать `GeoIpAnnotator.lookup()` на §4.3, удалить `notFound`,
`API_SERVICES`, `apiLookup`, `hasLocalDatabases()`; добавить
`isChainUsable(): Boolean = DataStore.geoIpChain.active.isNotEmpty()`.

**Задача 4.4** — **регрессия-тест на regex-хелперы** (зафиксировать текущее поведение
до правок, см. `SPEED_MARKER_REGEX`/`stripGeoTag`): 6 тестов на `stripSpeedMarker`,
`speedMarkerOf`, `stripGeoTag`, `transferAnnotations`. Это страховка от тихой поломки
аннотации — прошлый дебаг именно так и выглядел.

**Задача 4.5** — `ConfigurationFragment.kt:1078` заменить проверку
`hasLocalDatabases()` на `isChainUsable()` со строкой `R.string.geoip_no_provider`.

Коммит: `refactor(geoip): resolver chain replaces hardcoded MMDB+API sequence`

### Фаза 5. Менеджер баз (60 мин)

**Задача 5.1** — `bg/GeoIpDatabaseManager.kt`, часть «чтение состояния»:
`list()` читает `DatabaseReader.getMetadata()` → `buildDate`, размер, ETag из
`<name>.etag`; повреждённая база отдаётся с `error`, а не роняет список.

**Задача 5.2** — `download()` по образцу `AssetsActivity.updateCustomAsset` (стр. 390–412):
`Libexclavecore.newHttpClient()` + `useUDS(.../ipc.sock)` при запущенном ядре,
`.tmp` → `DatabaseReader.Builder` → rename.

**Задача 5.3** — `updateAll()` с `If-None-Match`. **Первым делом проверить**,
умеет ли `Libexclavecore` клиент задавать заголовки на GET (иначе — сравнивать ETag
через отдельный HEAD-запрос). Результат проверки зафиксировать комментарием в коде.

**Задача 5.4** — `import(uri, fileName)` через `contentResolver.openInputStream`
(образец — `AssetsActivity.importFile`, стр. 133–164), с той же валидацией `.tmp`.

**Задача 5.5** — `delete()` + `reload()` → `GeoIpAnnotator.reload()` (текущий метод
стр. 131–145, сейчас не вызывается ниоткуда — наконец понадобится).

Коммит: `feat(geoip): local MaxMind DB manager — download, update, import, delete`

### Фаза 6. UI (90 мин)

**Задача 6.1** — строки в `values/strings.xml` + `values-ru/strings.xml`:
`geoip_settings`, `geoip_provider_chain`, `geoip_local_databases`, `geoip_license_key`,
`geoip_add_provider`, `geoip_url_hint`, `geoip_active_n_of_m`, `geoip_db_build_date`,
`geoip_update_all`, `geoip_test_lookup`, `geoip_trace`, `geoip_no_provider`,
`geoip_already_up_to_date`, `geoip_invalid_db`.

**Задача 6.2** — `ui/GeoIpSettingsActivity.kt` + `res/xml/geoip_preferences.xml`:
три `Preference` на существующем шелле `layout_config_settings`.

**Задача 6.3** — `ui/GeoIpProviderListActivity.kt` + `layout_geoip_provider_item.xml`:
RecyclerView, тумблер, удаление, `ItemTouchHelper` для порядка, FAB на добавление.

**Задача 6.4** — `ui/GeoIpDatabaseActivity.kt` + `layout_geoip_database_item.xml`:
список баз с датой сборки, обновление по строке и всё, импорт, удаление, прогресс.

**Задача 6.5** — диалог test lookup с построчным trace по звеньям цепочки
(использует тот же `GeoIpChainResolver`, но с `verbose=true` — см. Фазу 7).

**Задача 6.6** — вход из настроек: `PreferenceCategory` в `res/xml/global_preferences.xml`
+ клики в `SettingsPreferenceFragment.kt` (блок «misc settings», стр. 632–642).

**Задача 6.7** — `AndroidManifest.xml`: регистрация трёх Activity (рядом со
`SubscriptionSourceEditActivity`, стр. 214–215).

Коммит: `feat(geoip): settings screen — provider chain editor, DB manager, lookup trace`

### Фаза 7. Автообновление (30 мин, опционально — делать после проверки Фаз 1–6)

**Задача 7.1** — `GeoIpScheduler` на `WorkManager` (`libs.work.runtime.ktx` уже есть),
период `autoUpdateIntervalDays`, запуск только когда `autoUpdate == true` и цепочка
содержит MMDB-звено с непустым `url`.

Коммит: `feat(geoip): optional periodic MMDB auto-update`

---

## 6. Файлы

**Новые:**

- `app/src/main/java/io/nekohasekai/sagernet/bg/GeoIpConfig.kt`
- `app/src/main/java/io/nekohasekai/sagernet/bg/GeoIpResolvers.kt`
- `app/src/main/java/io/nekohasekai/sagernet/bg/GeoIpDatabaseManager.kt`
- `app/src/main/java/io/nekohasekai/sagernet/bg/GeoIpScheduler.kt` (Фаза 7)
- `app/src/main/java/io/nekohasekai/sagernet/ui/GeoIpSettingsActivity.kt`
- `app/src/main/java/io/nekohasekai/sagernet/ui/GeoIpProviderListActivity.kt`
- `app/src/main/java/io/nekohasekai/sagernet/ui/GeoIpDatabaseActivity.kt`
- `app/src/main/res/xml/geoip_preferences.xml`
- `app/src/main/res/layout/layout_geoip_provider_item.xml`
- `app/src/main/res/layout/layout_geoip_database_item.xml`
- `app/src/test/java/io/nekohasekai/sagernet/bg/GeoIpChainTest.kt`
- `app/src/test/java/io/nekohasekai/sagernet/bg/GeoIpNameTagTest.kt` (Фаза 4.4)

**Изменяемые:**

- `app/src/main/java/io/nekohasekai/sagernet/bg/GeoIpAnnotator.kt` — `lookup()`,
  удаление `API_SERVICES`/`apiLookup`/`notFound`, regex-хелперы **не трогаем**
- `app/src/main/java/io/nekohasekai/sagernet/Constants.kt` — ключ `GEOIP_CONFIG`
- `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `geoIpChain`
- `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt` — строка 1078
- `app/src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt` — вход
- `app/src/main/java/io/nekohasekai/sagernet/res/xml/global_preferences.xml` — категория
- `app/src/main/AndroidManifest.xml` — регистрация Activity
- `app/src/main/res/values/strings.xml`, `values-ru/strings.xml`
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — JUnit
- `GeoIpAnnotator.kt` (только Фаза 4.4 — тесты на текущее поведение)

---

## 7. Верификация

**Сборка (после каждой фазы):**

```bash
cd ~/workspace/exclave
export JAVA_HOME="$PREFIX/lib/jvm/java-21"
export ANDROID_HOME="$HOME/android-sdk"
export TMPDIR="$PREFIX/tmp"
./gradlew :app:testOssDebugUnitTest :app:assembleOssDebug --no-daemon
# APK: app/build/outputs/apk/oss/debug/Exclave-0.17.57-next.1-arm64-v8a-debug.apk
```

**Обязательные тесты (все должны PASS):**

- `GeoIpChainTest` — 6 тестов на merge/fallback/order (§5 Фаза 2.1)
- `GeoIpNameTagTest` — 6 тестов на regex-хелперы, **фиксируют текущее поведение**
- `emptyConfigFallsBackToDefaults()`

**Ручной тест на устройстве (обязателен, сборка без него не считается готовой):**

1. Установить APK **и проверить package**: debug-сборка имеет `applicationIdSuffix "debug"`
   (`buildSrc/Helpers.kt:133`) → пакет `com.exclavenext.app.debug`. Установка поверх
   релизного приложения НЕ обновляет его — это уже стоило раунда дебага.
2. Настройки → GeoIP: открыть оба экрана, создать цепочку из 4 звеньев, перетащить
   порядок, выключить звено — порядок и флаги сохраняются после перезапуска приложения.
3. Test lookup на `5.9.255.1` и на чужом IP: trace показывает, какое звено что вернуло.
4. Скачать Country + ASN, убедиться, что в списке видна дата сборки базы; обновить
   повторно → «уже актуально» (ETag сработал).
5. Импортировать сломанный файл (например, `geoip.dat`) → отклонён, рабочая база цела.
6. Удалить базу, запустить аннотацию группы → переход к API-звеньям, имена аннотируются.
7. **Аннотация не должна сломаться:** прогнать аннотацию и speed test на реальной
   группе, убедиться что формат имён `🏴 0.0 🇸🇪 Sweden (Alexhost)` сохранён.

---

## 8. Риски

| Риск | Митигация |
|---|---|
| Регресс аннотации имён (регекс/семантика) | Фаза 4.4 — тесты, фиксирующие текущее поведение **до** правок; формат имён не трогаем |
| Цепочка длинная, аннотация группы тормозит | Уже есть кэш `geoCache` + пул воркеров; breaker не даёт битым URL блокировать. При 5+ API худший случай — последовательные таймауты. Лечится настройкой порядка звеньев |
| `Libexclavecore` не умеет кастомные заголовки → ETag-запрос | Фаза 5.3: проверить первым делом, при отказе — сравнение по HEAD или по размеру |
| `junit` не резолвится оффлайн | Фаза 1.2 — проверить сразу; при отказе откатить Фазу 2 на ручной smoke-тест и не блокировать остальное |
| Публичные API реально отдают 429/403 при массовой аннотации | Breaker (порог 10, 60 с) переносится на пользовательские URL; в UI показывать, какое звено в брейкере |
| Официальный MaxMind требует ключ, зеркала могут исчезнуть | URL каждого звена редактируется; зеркало — только default, не единственный путь |

---

## 9. Порядок и оценка

| Фаза | Что | ~время |
|---|---|---|
| 0 | пуш + ветка | 5 мин |
| 1 | JUnit-инфраструктура | 10 мин |
| 2 | модель цепочки + тесты | 40 мин |
| 3 | ключи + DataStore | 20 мин |
| 4 | движок на резолверах | 60 мин |
| 5 | менеджер баз | 60 мин |
| 6 | UI (3 экрана + строки) | 90 мин |
| 7 | автообновление (опц.) | 30 мин |

**Критический путь до первого работающего результата:** Фазы 0–4 (≈2 ч 15 мин) — после
них аннотация уже ходит по настраиваемой цепочке, но базы ещё нельзя скачать из UI.
**До полного объёма без Фазы 7:** ≈5 ч.

Точка принятия решения: если после Фазы 4 сборка на устройстве показывает, что цепочка
работает не хуже старого кода, — продолжать. Если нет — откат на `feature/subscription-sorter`
без потерь (ветка содержит только новые файлы до Фазы 4.3).
