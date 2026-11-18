package com.prreview.domain.model;

import java.util.Map;

public record RejectedFinding(String file, String reason, Map<String, Object> candidate) {}
