package com.prreview.domain.port;

import com.prreview.domain.model.ModelGeneration;

import java.util.Map;

public interface ReviewModel {
    ModelGeneration generate(String system, String context, Map<String, Object> schema)
            throws Exception;

    String name();
}
