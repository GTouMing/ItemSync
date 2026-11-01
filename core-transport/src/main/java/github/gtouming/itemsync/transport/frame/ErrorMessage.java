package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.error.ErrorCode;

import java.util.Objects;

public record ErrorMessage(ErrorCode code, String message) {

    public ErrorMessage {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
