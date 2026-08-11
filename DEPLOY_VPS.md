# Автодеплой на VPS через GitHub Actions

Каждый push в `master` запускает workflow [deploy-vps.yml](.github/workflows/deploy-vps.yml):

1. GitHub Actions выполняет `mvn clean package` с Java 17.
2. После успешной сборки подключается к VPS по SSH.
3. На VPS выполняет `git pull --ff-only`, затем `mvn clean package`.
4. Перезапускает systemd-сервис и проверяет его состояние `active`.

Webhook, Nginx и env-файл с секретами во время обычного деплоя не меняются.

## Настройки GitHub

Открыть в репозитории `Settings` → `Secrets and variables` → `Actions`.

Создать secret:

| Имя | Содержимое |
| --- | --- |
| `VPS_SSH_KEY` | Приватный SSH-ключ пользователя, который выполняет деплой. |

Создать variables:

| Имя | Содержимое |
| --- | --- |
| `VPS_HOST` | IP-адрес или DNS-имя VPS. |
| `VPS_PORT` | SSH-порт, обычно `22`. |
| `VPS_USER` | SSH-пользователь для деплоя. |
| `APP_DIR` | Абсолютный путь к Git-репозиторию бота на VPS. |
| `SYSTEMD_SERVICE` | Имя systemd-сервиса, например `cartmasterprobot`. |
| `VPS_KNOWN_HOSTS` | Host key VPS в формате `known_hosts`; для нестандартного порта — `[host]:port ключ`. |

Не добавлять в GitHub `BOT_TOKEN`, `WEBHOOK_SECRET` или содержимое server env-файла. Они остаются только на VPS.

`VPS_KNOWN_HOSTS` нужно получить из доверенного источника и сверить с отпечатком сервера. Workflow использует `StrictHostKeyChecking=yes` и не вызывает `ssh-keyscan`.

## Требования к VPS

На сервере уже должен быть клон этого репозитория в `APP_DIR`. SSH-пользователь должен иметь права выполнять в нём `git pull --ff-only` и `mvn clean package`.

Для `git pull` серверу нужен собственный доступ на чтение GitHub: deploy key, GitHub App или сохранённая авторизация. Этот доступ настраивается на VPS, а не в GitHub Actions.

Systemd-сервис должен запускать JAR из рабочей копии репозитория, например:

```ini
[Service]
WorkingDirectory=/opt/apps/cartmasterprobot
EnvironmentFile=/etc/cartmasterprobot/cartmasterprobot.env
ExecStart=/usr/bin/java -jar /opt/apps/cartmasterprobot/target/CartMasterProBot-1.0.0.jar
Restart=on-failure
```

SSH-пользователю нужны ограниченные права sudo только на этот сервис:

```sudoers
deploybot ALL=(root) NOPASSWD: /usr/bin/systemctl restart cartmasterprobot, /usr/bin/systemctl is-active cartmasterprobot
```

Подставить фактические имя пользователя и сервиса.

На VPS должны быть доступны Java 17 и Maven в `PATH` неинтерактивного SSH-сеанса. Команда проверки:

```bash
java -version
mvn -version
```

## Проверка и откат

После первого push проверить результат во вкладке `Actions`, затем на VPS:

```bash
sudo systemctl status cartmasterprobot
sudo journalctl -u cartmasterprobot -n 100 --no-pager
git -C /opt/apps/cartmasterprobot log -1 --oneline
```

`git pull --ff-only` специально не создаёт merge-коммиты. Если нужен откат, на VPS нужно переключить рабочую копию на предыдущий проверенный коммит, собрать JAR и перезапустить сервис. Автоматический откат workflow не делает.
