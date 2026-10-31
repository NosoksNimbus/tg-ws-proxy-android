# TG WS Proxy для Android

Android-порт [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy): то же ядро
`proxy/`, но в виде приложения. Прокси работает как foreground-сервис на
`127.0.0.1:1443`, Telegram подключается к нему как к обычному MTProto-прокси.

Python-ядро запускается внутри приложения через [Chaquopy](https://chaquo.com/chaquopy/)
(Python 3.12), отдельный интерпретатор или Termux не нужен.

## Установка

Скачайте APK из раздела [Releases](../../releases/latest) и установите на телефон.

Требования: Android 7.0+ (minSdk 24), **arm64-v8a** (все телефоны с 2016 года).
32-битные устройства не поддерживаются — Python 3.12 в Chaquopy существует только
для 64-битных ABI.

APK подписан debug-ключом. Для установки вручную этого достаточно, для Play Store
нужен свой keystore.

## Использование

1. Открыть приложение и нажать **«Запустить»** — Android покажет уведомление о
   работе foreground-сервиса.
2. Нажать **«Добавить в Telegram»** — прокси подхватится автоматически.
3. Нажать **«Отключить оптимизацию батареи»**, иначе система будет выгружать сервис
   из памяти.

Настройки (порт, secret, IP дата-центров, Cloudflare-резерв, домены Cloudflare
Worker, автозапуск, подробный лог) применяются при следующем запуске. Приложение
интерфейсно на русском и английском.

### Домены Cloudflare Worker

Поле **«Домены Cloudflare Worker (через запятую)»** — это резервный путь, а не
ускорение. Ядро использует его только если прямое подключение к DC Telegram
сорвалось (порядок попыток: `cf_worker → cf → tcp`). Пока прямой канал жив, Worker
не участвует.

Домен берётся из своего Cloudflare Worker, инструкция по его созданию —
[docs/EN/CfWorker.md](docs/EN/CfWorker.md). Несколько доменов перечисляются через
запятую или с новой строки, пустое поле выключает резерв.

## Сборка

Нужны JDK 17, Android SDK (platform 34, build-tools 34) и Python 3.12 в `PATH` —
Chaquopy использует его для `pip`.

```bash
cd android
./gradlew assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

`android/local.properties` с путём к SDK создаётся локально и в репозиторий не
попадает. Ядро `proxy/` при сборке копируется задачей `syncProxyCore` в
`android/app/src/main/python/proxy` — эта папка генерируется и тоже не коммитится.

Готовый APK также собирается в GitHub Actions (workflow
[Build Android APK](.github/workflows/android.yml), запуск вручную через
*Run workflow*).

## Отличия от исходного порта

Проект собирался локально, и три вещи в исходной версии не давали сборке пройти:

1. **`python.srcDir(...)` в `app/build.gradle.kts`** — Chaquopy не предоставляет
   типизированный доступ к Python source set из Kotlin DSL, скрипт не компилировался.
   Заменено на копирование ядра напрямую в `src/main/python/proxy`.
2. **`armeabi-v7a` в `abiFilters`** — Python 3.12 для этого ABI не существует,
   сборка падала с `Python 3.12 is not available for the ABI 'armeabi-v7a'`.
   ABI сокращены до `arm64-v8a` и `x86_64`.
3. **Неявная зависимость задачи** — Gradle ругался, что `mergeDebugPythonSources`
   читает выход `syncProxyCore` без объявленной зависимости. Добавлен `dependsOn`.

Дополнительно добавлено поле для доменов Cloudflare Worker, которого в порте не
было, хотя ядро его поддерживает.

## Проверено

Собрано и запущено на Redmi 22101320G (Android 14, arm64-v8a): сервис поднимается,
порт `127.0.0.1:1443` слушается, Telegram держит через него соединения, ошибок в
logcat нет.

## Лицензия

MIT — как у оригинального проекта. Ядро `proxy/` взято из
[tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) без изменений.
