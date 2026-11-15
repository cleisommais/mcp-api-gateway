package com.prreview.domain.model;

public record Finding(
        String file, int line, String severity, String explanation, String evidence) {}
