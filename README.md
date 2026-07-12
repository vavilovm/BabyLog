# BabyLog

Android-приложение для совместного учёта кормлений и сна. Room обеспечивает мгновенную офлайн-работу, Firebase синхронизирует общий семейный журнал.

## Настройка семейной синхронизации

1. Создайте Firebase project на Blaze-плане и Android app с package `com.mark.babylog`.
2. Включите Anonymous Authentication, Firestore и Cloud Messaging.
3. Скачайте `google-services.json` в `app/google-services.json` (файл исключён из Git).
4. Установите Firebase CLI, выполните `firebase use <project-id>`, затем из корня проекта:

```bash
cd functions && npm install && npm run build && cd ..
firebase deploy --only functions,firestore
```

Без `google-services.json` приложение продолжает собираться и работать локально; семейный экран покажет инструкцию по подключению. Для CI содержимое файла можно восстановить из GitHub Secret перед Gradle build.

Первый родитель нажимает иконку семьи и создаёт приглашение. Второй вводит восьмизначный код или сканирует QR. Код одноразовый и действует 24 часа.

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
