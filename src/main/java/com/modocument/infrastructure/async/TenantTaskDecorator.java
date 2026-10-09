package com.modocument.infrastructure.async;

import com.modocument.application.port.TenantContext;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Copies the submitting thread's {@link TenantContext} (and its MDC map, for
 * log correlation) onto the thread that eventually runs the task, and
 * restores both afterwards so pooled threads never leak one task's tenant
 * into the next. Without this, work handed to an executor would run with NO
 * tenant and — under the V13 row-level-security policies — read zero rows.
 *
 * <p>Applied to the ingestion background runner, the extraction worker
 * executor and the ws18 alert / SIEM-push executors (which copy the request
 * MDC this way, so an alert's audit rows carry the triggering request id);
 * apply it to any future {@code @Async}/executor bean that touches
 * the database (see the call-site registry in {@link TenantContext}).
 */
public class TenantTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        TenantContext.Snapshot tenant = TenantContext.snapshot();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            if (mdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(mdc);
            }
            try {
                TenantContext.runWith(tenant, runnable);
            } finally {
                if (previousMdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousMdc);
                }
            }
        };
    }
}
