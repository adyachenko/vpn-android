---
date: 2026-04-20
status: open
---

# Встроенный DNS-сервер libbox (172.19.0.2:53) тихо умирает, health-check не ловит

**v1.2.17 пробовал — откатили в v1.2.18.** Первый заход на решение убирал
`addDisallowedApplication(packageName)` чтобы probe-сокет шёл через tun.
На проде это вызвало traffic-storm: наш собственный процесс (WorkManager,
Hilt, DNS-lookups) начинал лить ~4MB/s в тоннель сразу после старта, пока
outbound ещё не готов, → rx-watcher зажигал DEAD на 3-м тике и отправлял в
бесконечный restart-loop. Подтверждено в логах `dtx=3855296B drx=0B state=DEAD`
после `TUN established`. В v1.2.18 `addDisallowedApplication(packageName)`
возвращён, `probeDns()` заглушен (возвращает true всегда), ветка
`handleProbeResult.!dnsAlive` и счётчик `dnsFailures` остаются в коде как
каркас для будущего настоящего детектора.

`ProtectedSocketFactory` оставлен как no-op safety-net (процесс excluded →
protect() избыточен, но и не вредит).

## Проблема

На проде v1.2.16 пойман новый класс сбоя: внутренний DNS-сервер sing-box
перестаёт отвечать на UDP-запросы к `172.19.0.2:53`, при этом все
существующие сигналы health-check рапортуют здоровый тоннель:

- Probe через CommandClient: `hysteria2-out=295ms, naive-out=305ms`, selector=hysteria2-out
- TX/RX watcher: `state=HEALTHY`, sumTx/sumRx сбалансированы
- Outbound urltest: все outbound'ы живые
- ICMP ping к `172.19.0.2` через tun1 отвечает за 3-4ms (endpoint жив)

Но apps не резолвят имена — получают `UnknownHostException: No address
associated with hostname` (подтверждено в логах Instagram, PID 27026 @ 08:23:54).

Проверка из adb shell через tun1:
- `nc -u -s 172.19.0.1 172.19.0.2 53` с валидным DNS-запросом — **нет ответа**
- `nc -u -s 172.19.0.1 8.8.8.8 53` с тем же запросом — валидный ответ, google IP
- `nc -s 172.19.0.1 google.com 80` HTTP GET — 301 Moved Permanently

То есть **tun-engine жив, outbound жив, DNS-handler мёртв выборочно**.

Лечится полным рестартом VPN из приложения.

## Ожидаемое поведение

Health-check должен детектить этот сбой и автоматически триггерить
`onConnectivityLost()` → полный restart. Пользователь не должен
руками перезапускать VPN.

## Затронутые места

- `vpn/VpnHealthCheck.kt` — вся логика watchdog'а, нужен новый сигнал
- `wiki/vpn-health-check.md` — §1 (что детектим и зачем) нужен новый подраздел 1.4
- Предположительно `domain/ConfigGenerator.kt` или серверный
  `client-template.json` — возможно root cause в некорректной DNS-секции
  после миграций sing-box 1.13 (`action: route`, `domain_resolver`), но
  это отдельный вопрос

## Гипотезы корня

1. Race в sing-box DNS-server goroutine при wake from Doze / network change
   (handler зомби — принимает пакеты, но не обрабатывает). Похоже на §1.1
   смерть CommandServer, только применительно к DNS.
2. Какая-то ошибка в DNS-правилах конфига, из-за которой при определённых
   условиях запросы падают в чёрную дыру без ответа (без timeout'а на клиенте).

## Предлагаемый подход к детектору

**Вариант A (отложен, v1.2.17 попытка откачена):** открыть DatagramSocket
из собственного процесса и отправить DNS-запрос в tun. Для этого процесс
должен быть внутри тоннеля, т.е. убрать `addDisallowedApplication(packageName)`
→ на проде это вызвало traffic-storm (~4MB/s в пустой tunnel от наших
же WorkManager/Hilt/etc), rx-watcher ложно триггерил DEAD. Возможный
фикс — убрать WorkManager вообще / отложить periodic jobs до момента
когда tunnel установлен, и дать rx-watcher startup grace-period, но
это большой комплексный рефактор; решение не оправдано только ради DNS-
пробы.

**Вариант B (наиболее перспективный):** bind probe-сокета к VPN Network
через `ConnectivityManager.getNetworkForType(TYPE_VPN)` →
`Network.bindSocket(socket)`. Android разрешает это даже для процесса,
исключённого через addDisallowedApplication, если сокет явно bind'ится к
VPN network. Нужно проверить поведение на Android 14+ (NetworkCapabilities
API mогло измениться).

**Вариант C:** расширить libbox (sing-box Go binding) собственной
DNS-health-командой. Sing-box DNS имеет internal resolve API; достаточно
exposнуть `Resolve("google.com") -> bool` через CommandClient. Требует
пересборки `libbox.aar` из исходников. Меньше Android-специфичной магии,
но больше Go-работы.

**Вариант D (кросс-сверка счётчиков):** тун-интерфейс имеет `RX/TX bytes`
в `/proc/net/dev`. libbox StatusMessage имеет `uplinkTotal/downlinkTotal`.
Если за окно tun1.TX растёт, а libbox.uplinkTotal не растёт — engine не
обрабатывает пакеты. Но DNS-only failure этим не покрывается (libbox
видит пакеты, передает outbound, считает в uplink, а DNS-handler при
этом может быть мёртв).

Рекомендованный порядок инвестигации: B → C → D. A закрыт.

## Связано с

- `wiki/vpn-health-check.md` §1.1 (смерть CommandServer — аналогичный
  паттерн, только для другого unix-socket сервиса)
- v1.2.15 TX/RX watcher — комплементарный сигнал, но на DNS-failure
  не срабатывает (TX через tun не попадает в libbox uplink если
  connection не установился, а browser ждёт DNS и не делает retransmit)
