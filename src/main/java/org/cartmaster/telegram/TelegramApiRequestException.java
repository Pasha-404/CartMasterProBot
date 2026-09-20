package org.cartmaster.telegram;

public final class TelegramApiRequestException extends RuntimeException {

    private final TelegramApiFailure failure;

    public TelegramApiRequestException(TelegramApiFailure failure) {
        super(failure.name());
        this.failure = failure;
    }

    public TelegramApiFailure getFailure() {
        return failure;
    }
}
