package com.modocument.domain.model.settings;

/**
 * A refused settings change (ws20) with the stable machine code of
 * docs/phase2/contracts.md §3. The API answers
 * {@code {error, code, message, key}} with {@link Code#httpStatus()}.
 */
public class SettingException extends RuntimeException {

    /** The contract's error codes, with their HTTP status. */
    public enum Code {
        /** The value lies outside the effective limits (or allowed values). */
        SETTING_OUT_OF_RANGE(400),
        /** The setting has exactly one allowed value; nobody can change it. */
        SETTING_LOCKED(400),
        /** No such setting at this layer. */
        SETTING_UNKNOWN(400),
        /** The stored row moved since the caller (or the maker) read it. */
        SETTING_VERSION_CONFLICT(409),
        /** An undecided maker/checker change already covers this setting. */
        SETTING_CHANGE_PENDING(409),
        /** The caller belongs to the platform bank: use /api/platform/settings. */
        USE_PLATFORM_SETTINGS(409);

        private final int httpStatus;

        Code(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    private final Code code;
    private final String key;

    public SettingException(Code code, String key, String message) {
        super(message);
        this.code = code;
        this.key = key;
    }

    public Code code() {
        return code;
    }

    /** The setting the refusal is about (null when not key-specific). */
    public String key() {
        return key;
    }
}
