package com.modocument.application.usecase;

/** How much table data to send to the LLM for a chat edit. */
public enum ChatEditScope {
    CELL,
    ROW,
    COLUMN,
    ALL_ERRORS
}
