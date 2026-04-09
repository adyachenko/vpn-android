Создай новый релиз приложения. Аргумент: тип бампа версии — `$ARGUMENTS` (major, minor, patch). По умолчанию patch.

Шаги:

1. Прочитай `version.properties` и определи текущую версию.
2. Увеличь соответствующую часть:
   - `patch` → VERSION_PATCH + 1
   - `minor` → VERSION_MINOR + 1, VERSION_PATCH = 0
   - `major` → VERSION_MAJOR + 1, VERSION_MINOR = 0, VERSION_PATCH = 0
3. Запиши обновлённый `version.properties`.
4. Покажи пользователю: `Версия: X.Y.Z → X'.Y'.Z'`
5. Собери release APK с подписью:
   ```
   JAVA_HOME="/opt/homebrew/opt/openjdk@17" \
   KEYSTORE_FILE=release.keystore \
   KEYSTORE_PASSWORD=vpnandroid2026 \
   KEY_ALIAS=vpn-android \
   KEY_PASSWORD=vpnandroid2026 \
   ./gradlew assembleRelease
   ```
6. Если сборка успешна, установи на устройство: `adb install -r app/build/outputs/apk/release/app-release.apk`
7. Создай git commit: `chore: bump version to X'.Y'.Z'`
8. Создай git tag: `vX'.Y'.Z'`
9. Запуши commit и tag: `git push && git push origin vX'.Y'.Z'`
10. Сообщи: GitHub Actions соберёт APK и выложит в Releases.
