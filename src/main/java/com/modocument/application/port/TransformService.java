package com.modocument.application.port;

import com.modocument.application.dto.TransformRequest;
import com.modocument.application.dto.TransformResult;

/** Use case for transforming a file (PDF/Excel/CSV) into a JSON payload. */
public interface TransformService {

    TransformResult transform(TransformRequest request, String model);

    int getTemplateId(TransformRequest request);
}
