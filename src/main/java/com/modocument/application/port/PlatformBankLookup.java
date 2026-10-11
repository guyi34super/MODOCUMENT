package com.modocument.application.port;

import java.util.OptionalInt;

/**
 * Where the platform owner's own bank lives (phase 2, ws20/ws21): platform
 * settings are rows owned by it, and only users of it may hold
 * {@code PLATFORM_*} permissions. Empty until the platform bank exists
 * (BootstrapPlatformRunner creates it).
 */
public interface PlatformBankLookup {

    /** The platform bank's id, or empty when no platform bank exists yet. */
    OptionalInt platformBankId();

    /** Whether {@code bankId} is the platform bank. */
    default boolean isPlatformBank(int bankId) {
        OptionalInt platform = platformBankId();
        return platform.isPresent() && platform.getAsInt() == bankId;
    }
}
