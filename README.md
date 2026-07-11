# BabyLog

Простое полностью офлайн Android-приложение для учёта кормлений и сна.

## Быстрый просмотр

Откройте проект в Android Studio, дождитесь Sync и запустите конфигурацию `app` на эмуляторе/телефоне. Экран `BabyScreen` также доступен из Compose Preview.

## Сборка и тесты

```bash
./gradlew testDebugUnitTest
./gradlew verifyPaparazziDebug
./gradlew assembleDebug
```

Чтобы намеренно обновить эталонные скриншоты после изменения UI:

```bash
./gradlew recordPaparazziDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Установка на подключённый телефон: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Публикация обновлений

Workflow `.github/workflows/android.yml` проверяет тесты и собирает APK после каждого push. APK можно скачать на странице запуска во вкладке **Actions** как artifact `BabyLog-debug`.

Для удобных постоянных ссылок создавайте GitHub Release:

```bash
gh release create v1.0.0 --generate-notes
```

Workflow автоматически приложит `app-debug.apk` к опубликованному Release. Для публичного распространения следует позднее добавить release keystore и собирать подписанный release APK/AAB через GitHub Secrets; debug APK предназначен для личной установки и тестирования.
