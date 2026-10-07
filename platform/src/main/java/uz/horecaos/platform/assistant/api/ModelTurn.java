package uz.horecaos.platform.assistant.api;

import java.util.Objects;

/** One earlier or current turn of the conversation, as the model is shown it. */
public record ModelTurn(Role role, String text) {

    /** Who spoke. A staff member's replies never reach the model: once one has written, the assistant is gone. */
    public enum Role {
        CUSTOMER,
        ASSISTANT
    }

    public ModelTurn {
        Objects.requireNonNull(role, "A turn needs a speaker");
        Objects.requireNonNull(text, "A turn needs text");
    }
}
