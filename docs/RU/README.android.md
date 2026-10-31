# TG WS Proxy для Android

Android-версия запускает то же ядро (`proxy/`), что и десктопные сборки, внутри приложения (через [Chaquopy](https://chaquo.com/chaquopy/)). Прокси работает как foreground-сервис на `127.0.0.1:1443`, Telegram подключается к нему как к MTProto-прокси.

## Установка

Скачайте `TgWsProxy_android` из артефактов workflow [Build Android APK](../../.github/workflows/android.yml) (вкладка Actions → Run workflow) и установите APK (потребуется разрешить установку из неизвестных источников).

## Использование

1. Откройте приложение и нажмите **Запустить** (разрешите уведомления — Android требует их для фонового сервиса).
2. Нажмите **Добавить в Telegram** и подтвердите добавление прокси.
3. Чтобы Android не убивал сервис в фоне, нажмите **Отключить оптимизацию батареи**.

Настройки (порт, secret, IP дата-центров, Cloudflare-резерв, автозапуск) применяются при следующем запуске. Внизу экрана показывается лог.

## Сборка из исходников

Нужны JDK 17, Android SDK и Python 3.12 (для pip внутри Chaquopy):

```bash
cd android
./gradlew assembleRelease
# APK: android/app/build/outputs/apk/release/
```

Ядро `proxy/` копируется в сборку автоматически, версия берётся из `proxy/__init__.py`. APK подписывается debug-ключом — для публикации в магазинах подставьте свой keystore в `android/app/build.gradle.kts`.

## Альтернатива без APK: Termux

```bash
pkg install python openssl
pip install httpx[http2] cryptography certifi
python -m proxy.tg_ws_proxy --port 1443
```

(запускать из корня репозитория)
