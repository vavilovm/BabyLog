# BabyLog

Android-приложение для совместного учёта сна малыша: начало и конец сна одним нажатием, графики по дням и персональный прогноз следующего сна. При первом запуске приложение спрашивает дату рождения. История кормлений сохранена в базе и экспорте, но скрыта из интерфейса. Room обеспечивает мгновенную офлайн-работу, Firebase синхронизирует общий семейный журнал.

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
# Быстрые поведенческие тесты; скриншоты сюда не входят.
./gradlew testDebugUnitTest

# Локальный визуальный отчёт и сравнение с эталонами.
./gradlew verifyPaparazziDebug

./gradlew assembleDebug
```

Чтобы намеренно обновить эталонные скриншоты после изменения UI:

```bash
./gradlew recordPaparazziDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Установка на подключённый телефон: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Публикация обновлений

Для изменений без выпуска APK добавьте `[skip release]` в сообщение коммита:
проверки CI выполнятся, а публикация релиза будет пропущена.

Workflow `.github/workflows/android.yml` проверяет тесты и собирает APK после каждого push. APK можно скачать на странице запуска во вкладке **Actions** как artifact `BabyLog-debug`.

Скриншотные тесты намеренно не запускаются в CI: обычные изменения быстро
проверяются поведенческими тестами и Android Lint, а визуальный отчёт создаётся
локально командами Paparazzi выше.

После каждого merge/push в `main` workflow автоматически публикует подписанный GitHub Release — вручную создавать его не нужно. Сначала должны пройти форматирование, тесты Android и backend и Android Lint. Затем workflow резервирует следующий patch-тег (например, `v0.11.2` → `v0.11.3`), собирает подписанный `app-release.apk` и публикует его вместе с автоматически созданными release notes.

Версия APK передаётся через `releaseVersionName` и `releaseVersionCode`: код версии вычисляется как `major × 1 000 000 + minor × 1 000 + patch`, чтобы обновления устанавливались поверх предыдущих сборок. Поля в Gradle остаются базовой версией для локальной разработки. При одновременных merge каждый commit получает отдельный запуск и атомарно резервирует уникальный тег. Неудачный release build может оставить тег без опубликованного релиза; повторный запуск использует тот же тег для того же commit.

Ручная публикация GitHub Release со стабильным тегом `vX.Y.Z` также поддерживается. Сборка использует версию этого тега. Ключ подписи и Firebase-конфигурация восстанавливаются из GitHub Secrets. Ключ подписи необходимо сохранять для совместимости обновлений. Debug APK предназначен только для разработки.

Подробности прогнозов, ограничения и порядок обновления сервера: [SLEEP_PREDICTION.md](SLEEP_PREDICTION.md).

### In-app GitHub updates

Tap **Обновить / Update app** in the toolbar to download the APK from the latest
stable GitHub release. The app checks the package name, signing certificate and
increasing Android version code before opening Android's installer. Allow
installation from BabyLog when prompted. The existing private release signing
key is preserved, so installing the update retains app data.
