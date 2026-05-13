# sing-box Config Manager — Android App

## ⚠️ Обязательная литература перед правками

**`wiki/vpn-health-check.md`** — архитектура и обоснование логики
health-check'а (VpnHealthCheck, BoxService.onVpnUnhealthy,
onWakeFromSleep, onNetworkChanged, параметры интервалов/порогов).

Правила:

- **Перед любыми правками** в `vpn/VpnHealthCheck.kt`,
  `vpn/BoxService.kt` (пути start/stop/restart/reload, callback'и
  onVpnUnhealthy/onConnectivityLost/onScreenOn/onNetworkChanged),
  `vpn/VPNService.kt` (screen receiver, network monitor) — **сначала
  прочитать** `wiki/vpn-health-check.md` целиком. Там есть §12 чеклист
  ревью; пройтись по нему перед коммитом.
- **После** любой такой правки или после найденной в поле проблемы —
  **обновить** `wiki/vpn-health-check.md`: добавить запись в §13
  (история правок), при необходимости поправить §4 (параметры), §11
  (ложные срабатывания), §12 (чеклист).
- Все числа в `companion object VpnHealthCheck` и все ветки
  `onWakeFromSleep` настроены методом проб и ошибок на реальных отказах
  (Hysteria2 / Doze / смена сети). Любое изменение — регрессия пока не
  доказано обратное.

## Обзор

Android-приложение для управления sing-box VPN. Встраивает libbox напрямую (без SFA), управляет VPN через Android VpnService. Скачивает конфиг с сервера, позволяет настраивать домены и приложения, генерирует финальный JSON и запускает VPN.

## Стек

- Kotlin, Jetpack Compose, MVVM
- Hilt (DI), Room (БД), Retrofit (HTTP)
- libbox (sing-box Go binding) — `app/libs/libbox.aar`
- Min SDK 28, Target SDK 34

## Структура

```
app/src/main/java/com/sbcfg/manager/
├── constant/          # Status, Alert enums
├── data/
│   ├── local/         # Room: AppDatabase, DAO, Entity
│   ├── preferences/   # DataStore: AppPreferences
│   └── remote/        # ConfigApiClient (Retrofit)
├── di/                # Hilt modules
├── domain/
│   ├── model/         # ConfigState, ServerInfo, AppMode, DomainMode
│   ├── ConfigGenerator.kt  # Миграции + генерация конфига
│   └── ConfigManager.kt    # Бизнес-логика: fetch, save, CRUD
├── ui/
│   ├── main/          # MainScreen, MainViewModel, tabs (Domains, Apps, Settings, Logs)
│   ├── navigation/    # NavGraph
│   └── setup/         # SetupScreen (ввод URL конфига)
├── util/              # AppLog (in-memory + logcat)
├── vpn/               # BoxService, VPNService, ServiceNotification, DefaultNetworkMonitor, ServiceBinder
├── App.kt             # Application class, libbox setup
└── MainActivity.kt    # VPN permission handling, navigation
```

## Сборка и установка через ADB

### Установка на тестовый телефон — ВСЕГДА release APK с GitHub

**Не использовать `installDebug`** на телефонах пользователя. Debug-сборка
подписана debug-keystore (Android Studio), release APK с GitHub Actions —
production-keystore. Подписи не совпадают → in-app обновление через
`UpdateManager` падает с `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, и юзер
застревает на старой версии до ручной переустановки.

Правильный поток после релиза (`git tag v* && git push --tags`):

```bash
# 1. Дождаться CI (~5 мин):
gh run watch --repo adyachenko/vpn-android

# 2. Скачать release APK под ABI устройства:
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')   # arm64-v8a / armeabi-v7a
VERSION=$(grep -E '^VERSION_' version.properties | awk -F= '{print $2}' | paste -sd. -)
gh release download "v$VERSION" --repo adyachenko/vpn-android \
  --pattern "*${ABI}*" --clobber --dir /tmp

# 3. Установить (первый раз — uninstall если стоит debug):
adb install -r /tmp/sing-box-config-manager-${VERSION}-${ABI}.apk
```

`installDebug` использовать только для локального дебага на эмуляторе
или отдельном dev-устройстве, где не нужны in-app обновления.

### Сборка и логи

```bash
# Java 17 нужен, на macOS:
JAVA_HOME="/opt/homebrew/opt/openjdk@17" ./gradlew assembleDebug

# Логи приложения (тег SbcfgApp):
adb shell logcat -c                          # очистить буфер
adb shell logcat -d | grep "SbcfgApp"        # прочитать логи
adb shell logcat -d --pid=$(adb shell pidof com.sbcfg.manager)  # все логи процесса
```

## Критические нюансы sing-box 1.13

ConfigGenerator.kt содержит миграции для совместимости с sing-box 1.13+:

- **TUN inbound**: `inet4_address`/`inet6_address` удалены → используем `address` (массив CIDR)
- **Sniff/domain_strategy**: убраны из inbound → route rule actions (`"action": "sniff"`, `"action": "hijack-dns"`)
- **DNS rules**: обязателен `"action": "route"` на правилах с `server`
- **domain_resolver**: обязателен на outbound'ах, чей server — домен (не IP)
- **Naive через DoH**: naive proxy не поддерживает UDP, DNS должен быть DoH (`https://...`)
- **Пустой package_name**: `{"package_name": []}` = catch-all, удаляем такие правила
- **auto_detect_interface**: убираем из route, платформа сама обрабатывает

## VPN Service — архитектура

Основано на SFA (sing-box for Android), упрощено.

### Запуск VPN
```
Button → ViewModel.onToggleVpn() → ConfigGenerator.generate()
  → SideEffect.RequestVpnPermission → Activity.requestVpnPermission()
  → VPN permission granted → BoxService.start(context, configJson)
  → startForegroundService(VPNService)
  → VPNService.onStartCommand() → BoxService.onStartCommand()
  → notification.show() IMMEDIATELY (иначе ForegroundServiceDidNotStartInTimeException)
  → CommandServer.start() → startOrReloadService(config)
  → openTun() → builder.establish() → Status.Started
```

### Остановка VPN (порядок критичен!)
```
Button → ViewModel.onToggleVpn() → SideEffect.StopVpn
  → BoxService.stop():
    1. notification.close()         — stopForeground(REMOVE)
    2. vpnService.closeTun()        — close TUN fd + network monitor
    3. commandServer.closeService() — остановить sing-box движок
    4. commandServer.close()        — закрыть command server
    5. status = Stopped
    6. service.stopSelf()
```

**Без `closeService()` VPN-иконка ключа не исчезает** — движок держит dup'd fd тоннеля.

### Важные детали VPNService
- `useProcFS() = true` — SELinux блокирует netlink_route_socket
- `basePath = filesDir` (internal storage) — external storage не поддерживает unix sockets на Android 11+
- Адреса интерфейсов ОБЯЗАТЕЛЬНО с CIDR (`addr/prefix`) — Go паникует без prefix
- Свой пакет исключаем из VPN: `addDisallowedApplication(packageName)`
- `START_NOT_STICKY` — не перезапускать сервис при убийстве
- DefaultNetworkMonitor: `registerBestMatchingNetworkCallback` (API 31+) или `requestNetwork` (API 28+)

## AppLog

Синглтон `AppLog` пишет и в in-memory StateFlow (для UI таба "Логи"), и в Android logcat с тегом `SbcfgApp`.

- `i()` → `Log.i` (не `Log.d`! debug-уровень подавляется на устройствах)
- `e()` → `Log.e`
- `w()` → `Log.w`
- `d()` → `Log.d` — **НЕ ИСПОЛЬЗОВАТЬ!** Подавляется на устройствах, логи не видны. Всегда использовать `i()` или `w()` вместо `d()`.

## libbox.aar

Собран с тегами: `with_gvisor,with_quic,with_dhcp,with_wireguard,with_utls,with_clash_api,with_naive_outbound`

Лежит в `app/libs/libbox.aar` (~73MB). Для пересборки нужен Go + gomobile.

## Известные нюансы

- `hiltViewModel()` в разных NavGraph destinations создаёт разные экземпляры ViewModel
- Channel (kotlinx.coroutines) доставляет событие только одному коллектору
- LazyColumn: использовать index-based items() если ключи могут дублироваться
- startDestination в NavGraph захардкожен на "main" (нужно доработать для first-time setup)
