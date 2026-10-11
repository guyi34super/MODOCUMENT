package com.modocument.infrastructure.transform.code;

import java.util.List;

/** One way of reading a table out of a document; proposes zero or more candidates. */
interface ExtractionStrategy {

    CodeStrategy name();

    /** Candidates for this document (empty when the strategy does not apply). */
    List<Candidate> propose(ExtractionContext context);
}
