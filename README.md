# Exclave Next

**Exclave Next** is a feature fork of [Exclave](https://github.com/ExclaveNetwork/Exclave) — an Android proxy client — built for people who live on subscriptions: many sources per group, annotated configs, and bulk tools to keep a large list under control.

Own application id (`com.exclavenext.app`) and signing key, so it installs alongside the original and gets its own releases.

<details>
<summary>All the upstream features are here</summary>

- Various proxy protocols, groups, routing, proxy chains
- Shadowsocks (with SIP003 plugin support), Shadowsocks 2022, Trojan, Hysteria 2, AnyTLS, mieru, NaïveProxy (as a standalone plugin), TUIC, Juicity, VMess, VLESS (with various optional sub-protocols), WireGuard (TCP and UDP only), TrustTunnel, Snell v4 and v6, ShadowQUIC, SSH proxy, HTTP CONNECT tunnel (HTTP/1.1, HTTP/1.1 with TLS, HTTP/2 and HTTP/3), SOCKS4, SOCKS4A and SOCKS5

</details>

## What's different from upstream

- **Multi-source groups** — a group is a container: several updatable subscriptions plus manually added configs, instead of one subscription link per group
- **GeoIP annotation** — a configurable resolver chain (online APIs and/or local MaxMind `.mmdb` databases) renames configs to `🇸🇪 Sweden (Alexhost)`; local databases are managed in-app: download, update, import from a file, ETag caching
- **Speed-test markers** — results go straight into config names (`🚩 dead`, `🏴 0.0`, `🏁`, `⭐️`, `✨`), with configurable rounds and timeout
- **Multi-select with bulk actions** — check configs, then URL-test, speed-test, GeoIP-annotate, resolve domains or export all of them at once
- **URL test rework** — retry rounds walk the list again, a three-part counter, follow-scroll that pauses only on user drag
- **Snackbar queue for subscription updates** — several subscriptions update in one run and each result is reported, nothing is lost under dialogs
- **Entry-hop balancer in proxy chains** — a balancer as the first element of a chain, with a sub-chain per member
- QoL: search keeps its filter across reloads, long-press a group tab for the group menu, active profile shown as a thin card outline without flicker

## Download

[Releases on GitHub](https://github.com/Fandaal/ExclaveNext/releases)

- `Exclave-<version>-arm64-v8a.apk` — modern 64-bit devices, Android 7.0+
- `Exclave-<version>-legacy-armeabi-v7a.apk` — 32-bit devices, Android 5.0+ (best-effort, old devices only)

SHA-256 of the signing certificate: `c77093fee0d3314b86eab2f2826fe284522e7c720c81fb522bc5a5d1c4b06d23`

In-app: **Settings → About → Check for updates** compares the installed version with the latest release.

Starting in ~~September 2026~~ 2027, Google will [block apps from "sideloading"](https://developer.android.com/developer-verification) on [certified Android devices](https://www.android.com/certified/partners/). If you are a user who values digital freedom, we need your voice to [express opposition](https://keepandroidopen.org/).

## Relationship with upstream

This fork tracks upstream releases: each Next release is the corresponding upstream version plus the Next feature set. Upstream bugs belong to the [Exclave issue tracker](https://github.com/ExclaveNetwork/Exclave/issues); Next-specific bugs and ideas are welcome in [this repo's issues](https://github.com/Fandaal/ExclaveNext/issues). The core, protocol work, wiki and translations belong to the upstream project — see the [Exclave wiki](https://github.com/ExclaveNetwork/Exclave/wiki) for term explanations and [Hosted Weblate](https://hosted.weblate.org/projects/exclave/) for translations.

## Build from source

- Install and configure JDK 21, Go 1.27 and Go Mobile.
- Install and configure Android SDK Platform 37.2, Android SDK Build-Tools 37.0.0, Android SDK Platform-Tools and Android NDK r30 through Android Studio or Android SDK Command-line Tools.
- Replace `release.keystore` with your own. It can be generated with Java `keytool`.
- Create a new `local.properties` file if it does not exist. Append the following lines to `local.properties`.
```
    KEYSTORE_PASS=your_keystore_pass
    ALIAS_NAME=your_alias_name
    ALIAS_PASS=your_alias_pass
```

- Build libexclavecore: `./run lib core` or `./library/core/build.sh`
- Download assets: `./gradlew :app:downloadAssets`, or update assets to the latest version: `./gradlew :app:updateAssets`
- Build Exclave Next: `./gradlew :app:assembleOssRelease` (default flavor) or `./gradlew :app:assembleLegacyRelease` (legacy flavor)

APK files are located in `app/build/outputs/apk/oss/release` (default flavor) or `app/build/outputs/apk/legacy/release` (legacy flavor).

## License

```
    Copyright (C) 2026  Exclave Next contributors
    Copyright (C) 2023  dyhkwong
    Copyright (C) 2021  nekohasekai <contact-sagernet@sekai.icu>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

Exclave is licensed under the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. However, Exclave optionally incorporates code covered by the GNU General Public License as published by the Free Software Foundation, version 3. If `github.com/exclavenetwork/libexclavecore` is compiled with `with_clash` tag, the GNU General Public License as published by the Free Software Foundation, version 3, applies to all of Exclave.

## Acknowledgment

- [Exclave](https://github.com/ExclaveNetwork/Exclave) and [husi](https://github.com/xchacha20-poly1305/husi)
- [Shadowsocks](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet](https://github.com/SagerNet/SagerNet)
- Other forks of SagerNet

---

## Кратко по-русски

**Exclave Next** — форк Exclave для тех, кто живёт на подписках: в одной группе несколько обновляемых подписок плюс ручные конфиги; GeoIP-аннотация переименовывает конфиги в `🇸🇪 Sweden (Alexhost)` (локальные базы MaxMind или онлайн-API, настраивается цепочкой); результаты теста скорости — маркерами в именах; групповое выделение конфигов с bulk-действиями (URL-тест, скорость, GeoIP, резолв, экспорт); переработанный URL-тест; обновление нескольких подписок с честной очередью снэкбаров. Свой package id и подпись — ставится рядом с оригиналом, не заменяя его. Остальное — апстрим: протоколы, маршруты, Go-ядро.

**Скачать:** [Releases](https://github.com/Fandaal/ExclaveNext/releases) — `arm64-v8a` для современных 64-битных телефонов (Android 7.0+), `legacy-armeabi-v7a` для 32-битных (Android 5.0+). Проверка обновлений внутри приложения: Настройки → О программе → «Проверить обновления».

Ошибки фич Next — в [issues форка](https://github.com/Fandaal/ExclaveNext/issues); баги ядра и протоколов — в апстрим.
