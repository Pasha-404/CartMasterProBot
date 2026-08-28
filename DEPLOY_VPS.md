# Автодеплой на VPS

Каждый push в `master` запускает [.github/workflows/deploy-vps.yml](.github/workflows/deploy-vps.yml). Workflow сначала запускает `mvn clean package` в GitHub Actions, затем по SSH выполняет на VPS:

```bash
git pull --ff-only
mvn clean package
sudo -n /usr/bin/systemctl restart cartmasterprobot.service
/usr/bin/systemctl is-active --quiet cartmasterprobot.service
```

Последняя команда не требует sudo. Если сервис не запустится, workflow завершится с ошибкой.

Webhook, Nginx и файл переменных окружения приложения при обычном деплое не меняются.

## Подтверждённая конфигурация VPS

| Параметр | Значение |
| --- | --- |
| Каталог приложения | `/opt/apps/cartmasterprobot` |
| SSH-пользователь GitHub Actions | `cartmaster-deploy` |
| Группа доступа к каталогу | `cartmaster` |
| Пользователь systemd-сервиса | `pavel` |
| Имя сервиса | `cartmasterprobot.service` |
| Запускаемый JAR | `/opt/apps/cartmasterprobot/target/CartMasterProBot-1.0.0.jar` |

Пользователи `pavel` и `cartmaster-deploy` входят в группу `cartmaster`; каталог приложения доступен им на запись. Для Git на сервере задано:

```bash
git config --global --add safe.directory /opt/apps/cartmasterprobot
```

От имени `cartmaster-deploy` проверены `git pull --ff-only` и `mvn clean package`.

## GitHub Actions: секрет и переменные

В репозитории открыть `Settings` → `Secrets and variables` → `Actions`.

Secret:

| Имя | Содержимое |
| --- | --- |
| `VPS_SSH_KEY` | Приватный SSH-ключ пользователя `cartmaster-deploy`. |

Variables:

| Имя | Значение |
| --- | --- |
| `VPS_HOST` | IP-адрес или DNS-имя VPS. |
| `VPS_PORT` | SSH-порт VPS. |
| `VPS_USER` | `cartmaster-deploy` |
| `APP_DIR` | `/opt/apps/cartmasterprobot` |
| `SYSTEMD_SERVICE` | `cartmasterprobot.service` |
| `VPS_KNOWN_HOSTS` | Проверенная запись VPS в формате `known_hosts`; для нестандартного порта — `[host]:port ключ`. |

Workflow использует `StrictHostKeyChecking=yes` и не вызывает `ssh-keyscan`. Значение `VPS_KNOWN_HOSTS` нужно получить из доверенного источника и сверить с отпечатком VPS.

Не добавлять в GitHub, репозиторий или логи `BOT_TOKEN`, `WEBHOOK_SECRET` и содержимое файла переменных окружения приложения. Эти секреты остаются только на VPS.

## Ограниченные права sudo

У `cartmaster-deploy` нет обычного sudo. Ему разрешён только перезапуск конкретного сервиса без пароля:

```sudoers
cartmaster-deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart cartmasterprobot.service
```

Проверка `systemctl is-active` выполняется без sudo, поэтому расширять это разрешение не нужно.

## Обычный деплой

1. Выполнить изменения и проверить их локально.
2. Закоммитить и отправить их в `master`.
3. Открыть вкладку `Actions` и дождаться успешного workflow `Deploy to VPS`.

Успешный workflow означает, что тесты прошли, новый JAR собран на VPS, сервис перезапущен и находится в состоянии `active`.

## Диагностика

На VPS:

```bash
sudo systemctl status cartmasterprobot.service --no-pager
sudo journalctl -u cartmasterprobot.service -n 100 --no-pager
git -C /opt/apps/cartmasterprobot log -1 --oneline
```

В GitHub Actions раскрыть шаг `Update source code and restart the service`: его последние строки показывают точную команду, на которой остановился деплой.

## Откат

Автоматического отката нет. Для ручного отката на VPS нужно переключить рабочую копию на предыдущий проверенный коммит, собрать JAR и перезапустить сервис. После ручного отката не выполнять новый push в `master`, пока причина проблемы не устранена: иначе workflow снова применит текущую версию.
