---
date: 2026-05-13
status: open
---

# applyPreferencePolicy не работает в multi-server конфиге

## Проблема

`VpnHealthCheck.applyPreferencePolicy` (§6.4 wiki) ориентирована на
legacy single-server топологию с тэгом `hysteria2-out`. В актуальной
multi-server конфигурации сервер отдаёт `proxy-auto.outbounds =
[hysteria2-de, hysteria2-primary, naive-de, naive-primary]` — тэга
`hysteria2-out` не существует ни в одном outbound.

В `GroupAwareHandler.writeGroups` (`VpnHealthCheck.kt:660`):
```kotlin
if (item.tag == HYSTERIA_TAG) hysteriaDelay = d  // HYSTERIA_TAG="hysteria2-out"
else naiveDelay = d
```

Поскольку `hysteria2-out` нет:
- `hysteriaDelay` всегда 0 (ни один item не матчится)
- `naiveDelay` перезаписывается каждым из 4 outbound'ов по очереди,
  итоговое значение = delay последнего (`naive-primary`)

В логах probe это видно как `hysteria=0ms naive=<N>ms` — даже когда
hysteria-de реально живой и используется.

Сам `applyPreferencePolicy` тоже не работает:
- Условие `hysteriaDelay > 0 && selected != HYSTERIA_TAG` никогда не
  истинно (hysteriaDelay = 0)
- Условие `hysteriaDelay == 0 && naiveDelay > 0 && selected == HYSTERIA_TAG`
  никогда не истинно (selected = `server-de`, не `hysteria2-out`)

## Ожидаемое поведение

В multi-server probe должен распознавать каждый hysteria-N как hysteria
(по префиксу `hysteria2-` или флагу outbound type), а policy должна
форсить hysteria-протокол на текущем выбранном per-server selector'е
(`server-<tag>`), а не на `proxy-select`.

## Затронутые места

- `app/src/main/java/com/sbcfg/manager/vpn/VpnHealthCheck.kt`:
  - `HYSTERIA_TAG`, `PROXY_GROUP_TAG` константы
  - `GroupAwareHandler.writeGroups` парсинг
  - `applyPreferencePolicy` логика
  - `probe()` — `Proxy outbounds` лог сейчас бесполезен

## Связано с

- `wiki/vpn-health-check.md` §6.4 — описывает устаревшее поведение
- ProtocolSelectionRepository (2026-05-13) — обходит проблему сверху,
  позволяет пользователю вручную задать протокол. Но автоматический
  hysteria-preference recovery после деградации Hysteria2 (исходная
  цель policy) — не работает.
