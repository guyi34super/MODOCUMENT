package com.modocument.application.port;

import com.modocument.application.dto.TransformRequest;
import com.modocument.application.dto.TransformResult;

/** Use case for transforming a file (PDF/Excel/CSV) into a JSON payload. */
public interface TransformService {

    TransformResult transform(TransformRequest request, String model);

    /**
     * ws23: transform for a known template, so its extraction mode and field
     * hints apply ({@code templateId} null behaves like
     * {@link #transform(TransformRequest, String)}).
     */
    default TransformResult transform(TransformRequest request, String model, Integer templateId) {
        return transform(request, model);
    }

    int getTemplateId(TransformRequest request);
}
