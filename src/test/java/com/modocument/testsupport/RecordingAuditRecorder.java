package com.modocument.testsupport;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;

import java.util.ArrayList;
import java.util.List;

/** Keeps every spec it is handed; for controller-level audit tests. */
public class RecordingAuditRecorder implements AuditRecorder {

    private final List<AuditEventSpec> recorded = new ArrayList<>();

    @Override
    public void record(AuditEventSpec spec) {
        recorded.add(spec);
    }

    public List<AuditEventSpec> recorded() {
        return recorded;
    }

    public List<AuditEventSpec> of(String action) {
        return recorded.stream().filter(spec -> spec.action().equals(action)).toList();
    }
}
