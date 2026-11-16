# TG WS Proxy для Android

Android-версия запускает прокси внутри приложения. Ядро написано на Rust (папка `rust-core/`); Python в проекте не используется. Прокси работает как foreground-сервис на `127.0.0.1:1443`, Telegram подключается к нему как к MTProto-прокси.

## Установка

Скачайте `TgWsProxy_android` из артефактов workflow [Build Android APK](../../.github/workflows/android.yml) (вкладка Actions → Run workflow) и установите APK (потребуется разрешить установку из неизвестных источников).

## Использование

1. Откройте приложение и нажмите **Запустить** (разрешите уведомления — Android требует их для фонового сервиса).
2. Нажмите **Добавить в Telegram** и подтвердите добавление прокси.
3. Чтобы Android не убивал сервис в фоне, нажмите **Отключить оптимизацию батареи**.

Настройки (порт, secret, IP дата-центров, Cloudflare-резерв, автозапуск) применяются при следующем запуске. Внизу экрана показывается лог.

## Сборка из исходников

Нужны JDK 17, Android SDK, Android NDK 26, Rust и `cargo-ndk` (`cargo install cargo-ndk`, `rustup target add aarch64-linux-android`):

```bash
cd android
./gradlew assembleRelease
# APK: android/app/build/outputs/apk/release/
```

Ядро `rust-core/` собирается Gradle автоматически, версия берётся из `rust-core/Cargo.toml`. APK подписывается debug-ключом — для публикации в магазинах подставьте свой keystore в `android/app/build.gradle.kts`.

## Альтернатива без APK: Termux

```bash
pkg install rust
cd rust-core
cargo run --release --bin tgws -- --port 1443
```
