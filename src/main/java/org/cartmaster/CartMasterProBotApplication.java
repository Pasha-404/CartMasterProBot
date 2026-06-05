package org.cartmaster;

import org.cartmaster.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class CartMasterProBotApplication {
    private static final Logger logger = LoggerFactory.getLogger(CartMasterProBotApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(CartMasterProBotApplication.class, args);
    }

    @Bean
    public ApplicationRunner startupLogger(BotConfig botConfig, Environment environment) {
        return args -> logger.info(
                "CartMasterProBot started: botUsername={}, botPath={}, serverPort={}, webhookSecretConfigured={}",
                botConfig.getBotUsername(),
                botConfig.getBotPath(),
                environment.getProperty("local.server.port", environment.getProperty("server.port")),
                hasText(botConfig.getWebhookSecret())
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
