package com.modocument.application.usecase;

import java.util.List;

/** Resolved chat-edit scope plus the row/column targets to include in the LLM payload. */
public record ChatEditScopeResolution(
        ChatEditScope scope,
        Integer row,
        String column,
        List<String> columns) {

    public ChatEditScopeResolution {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
