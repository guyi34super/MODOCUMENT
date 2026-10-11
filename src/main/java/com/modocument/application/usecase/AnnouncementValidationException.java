package com.modocument.application.usecase;

/**
 * A rejected announcement (ws24): 400 with {@code code = ANNOUNCEMENT_INVALID}
 * and a message fit for the platform console. An IllegalArgumentException,
 * so any handler that does not know the type still answers 400.
 */
public class AnnouncementValidationException extends IllegalArgumentException {

    /** The stable machine code of the 400 body. */
    public static final String CODE = "ANNOUNCEMENT_INVALID";

    public AnnouncementValidationException(String message) {
        super(message);
    }
}
