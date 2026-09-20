package org.cartmaster.telegram;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.concurrent.CompletableFuture;

public interface TelegramApiClient {

    CompletableFuture<Message> send(SendMessage message);

    CompletableFuture<Message> edit(EditMessageText message);

    CompletableFuture<Boolean> delete(DeleteMessage message);
}
