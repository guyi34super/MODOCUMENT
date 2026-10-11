package com.modocument.infrastructure.transform.code;

import java.util.List;

/**
 * A raw table one strategy proposes, before field mapping, value
 * normalisation and scoring.
 *
 * @param strategy   who proposed it
 * @param headers    unique column names
 * @param rows       cells per row, each exactly {@code headers.size()} wide, whitespace collapsed
 * @param fieldKeyed true when the headers are template field names (label/regex strategies)
 * @param uniformity share of rows that had the expected shape before padding (0..1)
 * @param notes      decisions taken, never cell values
 */
record Candidate(CodeStrategy strategy,
                 List<String> headers,
                 List<List<String>> rows,
                 boolean fieldKeyed,
                 double uniformity,
                 List<String> notes) {

    Candidate {
        headers = List.copyOf(headers);
        rows = rows.stream().map(List::copyOf).toList();
        notes = List.copyOf(notes);
    }
}
