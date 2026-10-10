package com.modocument.infrastructure.transform.code;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes delimited-text bytes the way spreadsheet exports arrive: a BOM
 * decides (UTF-8 or UTF-16), otherwise strict UTF-8, and on malformed input
 * windows-1252 (the Excel "CSV" default on Western Windows).
 */
public final class TextDecoding {

    /** windows-1252, always present in the JDK. */
    public static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /** Decoded text (BOM removed) and the charset that read it. */
    public record Decoded(String text, Charset charset, boolean hadBom) {
    }

    private TextDecoding() {
    }

    public static Decoded decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8, true);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE),
                    StandardCharsets.UTF_16LE, true);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE),
                    StandardCharsets.UTF_16BE, true);
        }
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return new Decoded(utf8.decode(ByteBuffer.wrap(bytes)).toString(),
                    StandardCharsets.UTF_8, false);
        } catch (CharacterCodingException e) {
            return new Decoded(new String(bytes, WINDOWS_1252), WINDOWS_1252, false);
        }
    }

    /** Removes a leading byte-order mark left in already-decoded text. */
    public static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '﻿') {
            return text.substring(1);
        }
        return text == null ? "" : text;
    }
}
