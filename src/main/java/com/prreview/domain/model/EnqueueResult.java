package com.prreview.domain.model;

import java.util.UUID;

public record EnqueueResult(UUID jobId, boolean duplicate, String outcome) {}
