# Deploy to VPS

CartMasterProBot is a Java 17 Spring Boot Telegram bot built with Maven. It uses a Telegram webhook on `/webhook` and stores shopping lists in memory, so there is no database migration.

## Required environment variables

Set these variables outside Git, for example in `/etc/cartmasterprobot/cartmasterprobot.env`:

```env
BOT_TOKEN=replace-with-token-from-botfather
WEBHOOK_SECRET=replace-with-new-random-secret
SERVER_PORT=8081
```

Do not reuse the old webhook secret. If a real bot token was ever committed to an old repository history, rotate it in BotFather before deploying.

## Build

```powershell
mvn clean package
```

The expected artifact is:

```text
target/CartMasterProBot-1.0.0.jar
```

## Runtime

Run the application on localhost and let Nginx terminate HTTPS:

```text
Spring Boot: http://127.0.0.1:8081/webhook
Public URL:  https://cartmaster.pavelkuzmin.com/webhook
```

Nginx should proxy:

```text
https://cartmaster.pavelkuzmin.com/webhook -> http://127.0.0.1:8081/webhook
```

After deployment, register the Telegram webhook with the public URL and the new `secret_token`.
