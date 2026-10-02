# Exclave Next — комбинированные группы: несколько подписок + ручные конфиги

**Статус:** РЕАЛИЗОВАНО (фазы 1–4), собрано и проверено на устройстве 2026-10-02.
Итоговый отчёт — в конце файла (раздел «РЕАЛИЗАЦИЯ»).
**Ветка:** `feature/subscription-sorter`
**Дата:** 2026-10-02

---

## 1. Задача

Сейчас группа жёстко одного из двух типов (`GroupType.BASIC` / `SUBSCRIPTION`), и у
подписочной группы ровно **одна** ссылка (`ProxyGroup.subscription.link`). Паста ссылки
из буфера в группу тащит конфиги **разово** (через `fetchSubscriptionProxies` → `import`),
URL нигде не сохраняется, обновить нельзя.

Нужно:
1. При добавлении подписки в группу её URL **сохраняется** и подписку можно **обновлять**.
2. В одной группе — **несколько** подписочных URL, каждый обновляется индивидуально.
3. В той же группе — **ручные** конфиги (не из подписки), которые апдейт подписок не трогает.
4. Обновление одной подписки **не затирает** профили других подписок и ручные конфиги.

Фактически — стираем жёсткое деление BASIC/SUBSCRIPTION: группа становится контейнером,
в котором сосуществуют 0..N подписочных источников и 0..M ручных профилей.

---

## 2. Текущая модель (что ломает мультиисточник)

- `ProxyGroup` (таблица `proxy_groups`): `type: Int`, `subscription: SubscriptionBean?`
  (один BLOB-столбец, сериализация Kryo). Один источник на группу.
- `SubscriptionBean` (BLOB): `type, link, deduplication, nameFilter(1), customUserAgent,
  httpHeaders, autoUpdate, autoUpdateDelay, lastUpdated, bytesUsed/Remaining, expiryDate, …`.
- `ProxyEntity` (таблица `proxy_entities`): `groupId`, `type`, `userOrder`, `status`, `ping`,
  `uuid`(не используется осмысленно), beans…. **Нет** привязки к источнику.
- `GroupUpdater.executeUpdate(group)` → берёт `group.subscription!!` → `RawUpdater.doUpdate`.
- **Ключевая проблема `RawUpdater.doUpdate`:** реконсиляция идёт по **всей группе** —
  `exists = proxyDao.getByGroup(groupId)`; всё, что не совпало по `displayName()` с новым
  списком, **удаляется** (`toDelete`). Значит две подписки в одной группе будут стирать
  профили друг друга, а ручные конфиги исчезнут при первом же апдейте.
- **Вторичная проблема (совпадение по имени):** матчинг `entity.displayName()` ↔
  `bean.displayName()`. Гео/скорость-аннотация меняет `bean.name` → при апдейте профиль
  считается новым (старый — удалённым). Аннотация не переживает обновление.
- БД: `@Database(version = 37, entities=[ProxyGroup, ProxyEntity, Rule, Stats, Asset])`,
  только AutoMigration-цепочка.

---

## 3. Целевая модель данных

### 3.1 Новая сущность `SubscriptionSource` (таблица `subscription_sources`)

Отдельная строка на каждую подписку. Переиспользуем `SubscriptionBean` как вложенный
BLOB (как уже сделано в `ProxyGroup.subscription`) — вся метадата источника (link, type,
фильтры, UA, headers, bytesUsed, expiryDate, lastUpdated, autoUpdate, autoUpdateDelay)
достаётся бесплатно.

```kotlin
@Entity(
    tableName = "subscription_sources",
    indices = [Index("groupId", name = "ss_groupId")]
)
data class SubscriptionSource(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var groupId: Long = 0L,
    var userOrder: Long = 0L,
    var name: String = "",                      // ярлык источника (для UI)
    var subscription: SubscriptionBean? = null, // BLOB: link/type/фильтры/bytes/lastUpdated…
) : Serializable()  // serializeToBuffer/deserializeFromBuffer по образцу ProxyGroup
```

Dao:
```kotlin
@Query("SELECT * FROM subscription_sources WHERE groupId = :g ORDER BY userOrder")
fun byGroup(g: Long): List<SubscriptionSource>
@Query("SELECT * FROM subscription_sources WHERE id = :id") fun getById(id: Long): SubscriptionSource?
@Query("SELECT MAX(userOrder)+1 FROM subscription_sources WHERE groupId = :g") fun nextOrder(g: Long): Long?
@Insert fun create(s: SubscriptionSource): Long
@Update fun update(s: SubscriptionSource)
@Query("DELETE FROM subscription_sources WHERE id = :id") fun deleteById(id: Long)
@Query("DELETE FROM subscription_sources WHERE groupId = :g") fun deleteByGroup(g: Long)
@Query("SELECT * FROM subscription_sources WHERE id IN (SELECT id ...) ") // + autoUpdate выборка
```

### 3.2 `ProxyEntity` — привязка к источнику (решение пользователя: отдельная колонка)

```kotlin
@ColumnInfo(defaultValue = "0") var sourceId: Long = 0L
```
- `sourceId == 0` → **ручной** конфиг (вставлен/отсканирован/импортирован руками).
- `sourceId == N` → профиль принадлежит `SubscriptionSource` c id=N.

Сериализация `ProxyEntity.serializeToBuffer/deserializeFromBuffer` — добавить
`output.writeLong(sourceId)` / чтение с version-гардом (бамп локального `version` в этом
Kryo-формате, т.к. сейчас пишется `writeInt(0)`).

Новые Dao-запросы:
```kotlin
@Query("SELECT * FROM proxy_entities WHERE groupId=:g AND sourceId=:s ORDER BY userOrder")
fun getByGroupAndSource(g: Long, s: Long): List<ProxyEntity>
@Query("DELETE FROM proxy_entities WHERE sourceId = :s") fun deleteBySource(s: Long)
```

### 3.3 `ProxyGroup.type`

Оставляем столбец ради совместимости и дефолтов UI, но **снимаем** с него функциональную
нагрузку: апдейт/иконки/меню теперь зависят от «есть ли у группы хотя бы один источник»
(`subscription_sources` непусто), а не от `type == SUBSCRIPTION`. Поле `ProxyGroup.subscription`
становится legacy (используется только для разовой миграции, см. §5), после миграции не читается.

---

## 4. Изменения в апдейтерах (устранение взаимного затирания)

`GroupUpdater.doUpdate` сейчас принимает `(proxyGroup, subscription)`. Меняем контракт на
работу **по источнику**:

```kotlin
abstract suspend fun doUpdate(group, source: SubscriptionSource, ui, byUser)
```

Внутри `RawUpdater/SIP008Updater/AgeUpdater`:
- `val exists = proxyDao.getByGroupAndSource(group.id, source.id)` — реконсиляция **только**
  в пределах источника. Чужие источники и ручные (`sourceId=0`) не трогаются.
- Новые/обновлённые профили: `ProxyEntity(groupId=group.id, sourceId=source.id, …)`.
- `source.subscription.lastUpdated/bytesUsed/...` обновляются в строке `subscription_sources`.

`GroupUpdater` новые входные точки:
- `updateSource(source, byUser)` — обновить одну подписку.
- `updateGroup(group, byUser)` — обновить **все** источники группы последовательно
  (`sources.byGroup(group.id).forEach { updateSource(it, byUser) }`).

`updating`/`progress` сейчас кейятся по `group.id`. Перекейить по `source.id` (прогресс
индивидуального источника), а состояние группы в UI = «обновляется любой из её источников».

`SubscriptionUpdater` (авто-WorkManager): перебор `subscription_sources` где
`subscription.autoUpdate`, интервалы считаются от `source.subscription.lastUpdated`/`autoUpdateDelay`.

### 4.1 Фикс матчинга по стабильному ключу (решение пользователя)

Заменить ключ реконсиляции с `displayName()` на устойчивый к переименованию/аннотации:

```kotlin
fun ProxyEntity.matchKey(): String {
    val b = requireBean()
    return "${b.serverAddress}\u0000${b.serverPort}\u0000$type"  // адрес+порт+протокол
}
```
- Внутри одного источника коллизии (один host:port:type, несколько записей) разруливаем
  детерминированно по `userOrder`/порядку в выдаче (как сейчас делается суффикс `(n)` для имён).
- Между источниками коллизии невозможны — реконсиляция ограничена `sourceId`.

**Сохранение аннотации при апдейте.** Сейчас на совпавшем профиле делается
`entity.putBean(bean)` — имя из подписки затирает гео/скорость-тег. Поскольку теперь
матчим по host/port/type, профиль корректно опознаётся как «тот же», и нужно **перенести
аннотацию**: взять свежий bean из подписки, но имя собрать как
`GeoIpAnnotator.annotateName(stripSpeedMarker(freshName), savedGeoInfoOrReparseFromOldName)`.
Проще и достаточно: если старое имя было аннотировано (regex гео-тега/маркеры скорости
совпадают), перенести суффикс-тег старого имени на новое базовое имя подписки. Это
отдельная строка в блоке `toReplace` апдейтера. Без этого фикса гео/скорость теряются при
каждом обновлении подписки.

---

## 5. Миграция БД (version 37 → 38)

1. **Схема (AutoMigration 37→38):**
   - новая таблица `subscription_sources` (Room создаёт из новой `@Entity`);
   - новая колонка `proxy_entities.sourceId INTEGER NOT NULL DEFAULT 0`
     (`@ColumnInfo(defaultValue = "0")`).
   - Добавить `SubscriptionSource::class` в `entities=[…]`, `version = 38`,
     `AutoMigration(from = 37, to = 38)`.
   - AutoMigration достаточно: только добавление таблицы и NOT NULL-колонки с дефолтом.

2. **Бэкфилл данных (в коде, лениво — Kryo-BLOB нельзя разобрать в SQL):**
   одноразовый проход при старте/первом обращении (например в `GroupManager` или в
   `SagerDatabase` post-open callback), идемпотентный:
   ```
   for (g in groupDao.allGroups()) {
     if (g.type == SUBSCRIPTION && g.subscription?.link.orEmpty().isNotEmpty()
         && sources.byGroup(g.id).isEmpty()) {
         val sid = sources.create(SubscriptionSource(groupId=g.id, name=g.displayName(),
                                                      subscription=g.subscription))
         // все профили старой подписочной группы → этому источнику
         proxyDao.getByGroup(g.id).forEach { it.sourceId = sid; proxyDao.updateProxy(it) }
     }
   }
   ```
   BASIC-группы: все профили остаются `sourceId=0` (ручные) — ничего делать не нужно
   (дефолт колонки уже 0).

   Флаг «миграция источников выполнена» — в `DataStore` (bool), чтобы проход был один раз.

---

## 6. Изменения UI

### 6.1 Экран источников группы (новый)
`SubscriptionSourcesActivity` (или фрагмент), открывается из меню группы
(`group_action_menu.xml` → пункт «Источники подписок»):
- список `subscription_sources` группы: имя/URL, последнее обновление, трафик, статус;
- на строке: **обновить** (один источник), **изменить** (редактор `SubscriptionBean`:
  фильтры/UA/headers/автообновление — переиспользуем существующие preference из
  `group_preferences.xml`), **удалить**;
- кнопка **+**: добавить источник — варианты: «Вставить ссылку из буфера», «Сканировать QR»,
  «Из файла». URL валидируется (`isHTTPorHTTPSURL`), создаётся `SubscriptionSource`,
  сразу запускается первое обновление.

### 6.2 Группа (`GroupFragment`)
- Кнопка «обновить» на строке группы видна, если у группы **есть источники**
  (не по `type==SUBSCRIPTION`), и обновляет **все** источники группы.
- Прогресс-бар — если обновляется любой источник группы.
- Трафик/срок — агрегат по источникам (сумма bytesUsed/оставшегося; ближайший expiry) —
  либо показывать на экране §6.1 детально, в строке группы — суммарно.

### 6.3 Паста ссылки в группу (`ConfigurationFragment`, фикс поведения)
Сейчас: вставка URL → `fetchSubscriptionProxies` → `import` (разовый, `sourceId=0`).
Новое: при распознавании URL — диалог:
- **«Добавить как обновляемую подписку»** → создать `SubscriptionSource` в текущей группе,
  запустить обновление (профили получат `sourceId` источника);
- **«Импортировать разово»** → как сейчас, профили `sourceId=0` (ручные).
Простые конфиги (не URL) импортируются как ручные (`sourceId=0`) без изменений.

### 6.4 Создание группы (`GroupSettingsActivity`)
`groupType` остаётся как «тип по умолчанию» при создании. Секция одиночной подписки
(`groupSubscription`/`subscriptionUpdate`) переезжает в экран источников §6.1 (или
становится «добавить первый источник»). Front/landing proxy, groupOrder, name — без изменений.

---

## 7. Удаление источника (решение пользователя: удалять профили)

`GroupManager.deleteSource(sourceId)`:
```
proxyDao.deleteBySource(sourceId)      // профили источника — удалить
sources.deleteById(sourceId)
// пересчёт автообновления, уведомить listeners
```
Удаление группы: `sources.deleteByGroup(g.id)` + существующее `proxyDao.deleteByGroup`.

---

## 8. Затронутые файлы

**Новые:**
- `database/SubscriptionSource.kt` (+ Dao)
- `ui/SubscriptionSourcesActivity.kt` (+ layout, адаптер строки)
- (возм.) `database/Migrations.kt` — не нужен, AutoMigration хватает; бэкфилл в коде.

**Изменяются:**
- `database/SagerDatabase.kt` — entities += SubscriptionSource, version 38, AutoMigration 37→38, sourceDao.
- `database/ProxyEntity.kt` — поле `sourceId` + Kryo ser/de (бамп версии формата) + Dao-запросы `getByGroupAndSource`, `deleteBySource`, `matchKey()`.
- `database/ProxyGroup.kt` — `subscriptions()`/логика «есть источники»; `subscription` → legacy.
- `database/GroupManager.kt` — createSource/updateSource/deleteSource; хуки listeners.
- `group/GroupUpdater.kt` — контракт `doUpdate(group, source, …)`, `updateSource`, `updateGroup(all)`, прогресс по source.id.
- `group/RawUpdater.kt`, `SIP008Updater.kt`, `AgeUpdater.kt` — реконсиляция по `(group, source)`, матчинг по `matchKey()`, перенос аннотации.
- `bg/SubscriptionUpdater.kt` — перебор `subscription_sources` с autoUpdate.
- `ui/GroupFragment.kt` — кнопка обновления по наличию источников; «обновить все»; меню «Источники»; агрегатный трафик.
- `ui/GroupSettingsActivity.kt` — тип=дефолт; перенос секции подписки в экран источников.
- `ui/ConfigurationFragment.kt` — диалог «подписка/разово» при пасте URL.
- `res/menu/group_action_menu.xml`, `res/values/strings.xml`, (возм.) `res/xml/*` — пункты/строки.

---

## 9. Порядок реализации (чтобы на каждом шаге собиралось)

1. **Данные:** `SubscriptionSource` + Dao, `ProxyEntity.sourceId` + Kryo, БД v38 + AutoMigration, бэкфилл-проход. Собрать, прогнать запуск на старой БД (проверить миграцию существующих подписок).
2. **Апдейтеры:** переключить `doUpdate` на `(group, source)` + матчинг по `matchKey()` + перенос аннотации + `updateGroup(all)`. Проверить, что ручные `sourceId=0` не трогаются.
3. **UI источников:** `SubscriptionSourcesActivity` (список, add/update/edit/delete), пункт в меню группы.
4. **GroupFragment/Settings:** кнопка «обновить все», видимость по наличию источников, агрегатный трафик, тип=дефолт.
5. **Паста URL:** диалог «подписка/разово» в ConfigurationFragment.
6. **Авто-обновление:** `SubscriptionUpdater` по источникам.
7. Строки/локализация, финальная сборка `:app:assembleOssDebug`, ручной тест на устройстве.

---

## 10. Открытые вопросы / риски

- **Kryo-формат `ProxyEntity`.** Добавление `sourceId` в сериализацию требует version-гарда,
  иначе бэкапы/share старого формата не читаются. Чтение: `if (version >= 1) sourceId = readLong() else 0`.
- **Коллизии matchKey** при нескольких записях с одинаковым host:port:type внутри одной
  подписки — разрулить по порядку; задокументировать как редкий край.
- **Агрегатный трафик** в строке группы — суммирование по источникам может путать, если
  провайдеры дают несопоставимые единицы; возможно показывать детально только в §6.1.
- **Front/landing proxy** группы — остаются на уровне группы, к источникам не относятся.
- **Экспорт/бэкап группы** (`ProxyGroup.export`, `serializeForShare`) сейчас пишет одну
  `subscription`. Для мультиисточника формат share надо расширить (список источников) —
  либо на этом этапе экспортировать только ручные профили + предупреждение.

---

## РЕАЛИЗАЦИЯ (итог, 2026-10-02)

Реализовано полностью, собрано нативно в Termux, APK проверен на устройстве
(миграция, несколько источников, ручные+подписка, диалог вставки, сохранение
аннотаций при апдейте). APK: `app/build/outputs/apk/oss/debug/Exclave-0.17.57-next.1-arm64-v8a-debug.apk`.

### Фаза 1 — данные + миграция
- **`database/SubscriptionSource.kt`** (новый): `@Entity(subscription_sources, index ss_groupId)`,
  поля `id/groupId/userOrder/name/subscription(BLOB SubscriptionBean)`, Kryo ser/de,
  полный Dao (all/byGroup/getById/countByGroup/nextOrder/create/update/deleteById/deleteByGroup).
- **`ProxyEntity`**: колонка `@ColumnInfo(defaultValue="0") var sourceId`. Kryo-версия
  формата `writeInt(0)→1` + version-гард чтения `if (version>=1) sourceId=readLong()`
  (старые бэкапы/share читаются). Dao: `getByGroupAndSource`, `deleteBySource`.
  Метод `matchKey()` = `serverAddress\u0000serverPort\u0000type`.
- **`SagerDatabase`**: entities += SubscriptionSource, version 37→38, `AutoMigration(37,38)`,
  `sourceDao()`. Схема `app/schemas/.../38.json` сгенерирована.
- **`GroupManager`**: `createSource/updateSource/deleteSource` (удаление → `deleteBySource`),
  `deleteGroup(list)` чистит `subscription_sources`, идемпотентный
  `migrateSubscriptionSources()` (флаг `DataStore.subscriptionSourcesMigrated`):
  каждая legacy SUBSCRIPTION-группа → один SubscriptionSource, её профили получают sourceId.
- **`SagerNet.onCreate`**: вызов миграции в главном процессе.

### Фаза 2 — апдейтеры по источникам
- **`GroupUpdater`**: контракт `doUpdate(group, source, ui, byUser)`. `updating/progress`
  кейятся по `source.id`. `isGroupUpdating(groupId)`, `startUpdate(source)`,
  `startUpdateAll(groupId)`, `executeUpdate(source)` сам достаёт группу и перс-ит
  метадату в `finishUpdate(source)` (`sourceDao.update`).
- **Raw/Age/SIP008Updater**: `exists = getByGroupAndSource(group.id, source.id)` →
  реконсиляция строго в пределах источника, чужие источники и ручные (sourceId=0) не трогаются.
  Матчинг по `matchKey()` (Raw/Age; SIP008 — по profileId). Новые профили с `sourceId=source.id`.
- **Сохранение аннотаций**: `GeoIpAnnotator.transferAnnotations(oldName, freshName)` переносит
  гео-тег + маркер скорости со старого имени на свежее (хелперы speedMarkerOf/stripSpeedMarker/geoTagOf).
- **`SubscriptionUpdater`**: авто-обновление по `sourceDao.all().filter{autoUpdate}`;
  `reconfigureUpdater` тоже по источникам.

### Фаза 3 — UI источников
- **`SubscriptionSourcesActivity`** (новый): список источников группы (имя/URL/статус),
  на строке — обновить/изменить/прогресс; меню «+»: вручную / из буфера; «обновить все».
  Слушает GroupManager. Layout `layout_subscription_sources(.xml/_item.xml)`, меню
  `subscription_sources_menu.xml`.
- **`SubscriptionSourceEditActivity`** (новый): редактор одного источника,
  preference `xml/subscription_source_preferences.xml` (имя + тип/ссылка/фильтры/UA/headers/
  age/автообновление). Создание → createSource + первое обновление; удаление → deleteSource.
- **`GroupFragment`**: пункт меню «Источники подписок»; кнопка обновления по наличию источников
  (`sourceDao.countByGroup>0`), «обновить все»; агрегатный трафик/срок по источникам;
  статус группы считает профили + последнее обновление среди источников; прогресс индетерминантный.
- Строки, Manifest (обе Activity), ключи DataStore (`subscriptionSourceName/editingSourceId/editingSourceGroupId`).

### Фаза 4 — вставка URL + настройки группы
- **`ConfigurationFragment`**: `offerSubscriptionOrImport(url)` — диалог «Добавить подписку /
  Импортировать разово / Отмена». Подключён в action_import_clipboard и importFile.
  `DataStore.selectedGroupForImport()` упрощён (импорт в текущую группу, не в «первую BASIC»).
- **`GroupSettingsActivity` + `xml/group_preferences.xml`**: убран одиночный подписочный блок
  (groupType/тип подписки/ссылка/фильтры/…), вместо него пункт «Источники подписок» (дизейблен
  при создании новой группы — нужен сохранённый groupId). `serialize()` всегда пишет
  `type=BASIC`, `subscription=null` — группа стала чистым контейнером.

### Решение по экспорту/share (принято)
Формат share НЕ ломается на этом этапе: `ProxyGroup.serializeForShare` остаётся
одноподписочным (version-гарды уже есть), расширение на список источников — отложено
до реальной надобности, обратносовместимо через version-бамп. Экспорт профилей не затронут.

### Открытые хвосты (не делалось)
- Share/бэкап группы с несколькими источниками (сейчас только одна subscription в share-формате).
- Автозагрузчик MMDB (из прошлой итерации, см. SUBSCRIPTION-SORTER-WORK.md).
- Коллизии matchKey (несколько записей host:port:type в одной подписке) разруливаются суффиксом #dupN по порядку — редкий край.
