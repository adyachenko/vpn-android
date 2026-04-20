---
date: 2026-04-20
status: closed
---

# Встроенный DNS-сервер libbox (172.19.0.2:53) тихо умирает, health-check не ловит

**Закрыто в v1.2.17.** Детектор реализован: `VpnHealthCheck.probeDns()` шлёт
UDP DNS-запрос к `172.19.0.2:53` через tun каждый тик основного цикла (после
удаления `addDisallowedApplication(packageName)` стало возможно — см. ниже
вариант «A расширенный»). Две подряд молчащих пробы → `onConnectivityLost()`.

Unblocking-change: app-уровень HTTP теперь обходит тоннель per-socket через
`ProtectedSocketFactory` (см. `di/AppModule.kt`), а собственный UID
добавляется в allow-list в include-mode VPN (см. `VPNService.openTun`),
чтобы probe-сокет реально шёл через tun.

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

**Периодическая DNS-проба к `172.19.0.2:53` через CommandClient не подходит**
(она ходит мимо tun-engine). Нужен прямой тест DNS через tun:

**Вариант A (предпочтительный):** kotlinx `DatagramSocket` → bind к
`172.19.0.1` → send валидный DNS-запрос (A record для короткого домена,
например `_health.local`) на `172.19.0.2:53` → `withTimeout(2s)` на
`receive()`. Каденс: раз в N тиков основного цикла (не каждые 30с — это
грузит libbox DNS лишним). N=3 (≈90с). Два подряд fail'а = restart.

**Проблема:** наш собственный процесс ходит мимо VPN через
`addDisallowedApplication(packageName)` (см. wiki §3 «Почему НЕ делаем
настоящий TCP-коннект»). Bind к 172.19.0.1 из нашего процесса даст
EPERM — Android запрещает писать в VPN-интерфейс процессу, который
сам исключён из VPN.

**Вариант B:** вызывать `Libbox.newStandaloneCommandClient()` с новой
командой DNS-test, если такая есть в libbox API (нужно проверить). Либо
расширить handler — интересует есть ли в `CommandClient` что-то типа
`queryDns()`.

**Вариант C (кросс-сверка счётчиков):** тун-интерфейс имеет свои
`RX/TX bytes` в `/proc/net/dev`. libbox StatusMessage имеет
`uplinkTotal/downlinkTotal`. Если за окно tun1.TX растёт, а
libbox.uplinkTotal не растёт — engine не обрабатывает пакеты. Но
это не покрывает DNS-only failure (libbox видит пакеты, но DNS-handler
их теряет, остальной трафик мог бы работать).

Начать с инвестигации варианта B (расширение libbox API) — как минимум
проверить, есть ли в Go-коде sing-box DNS health endpoint.

## Связано с

- `wiki/vpn-health-check.md` §1.1 (смерть CommandServer — аналогичный
  паттерн, только для другого unix-socket сервиса)
- v1.2.15 TX/RX watcher — комплементарный сигнал, но на DNS-failure
  не срабатывает (TX через tun не попадает в libbox uplink если
  connection не установился, а browser ждёт DNS и не делает retransmit)
