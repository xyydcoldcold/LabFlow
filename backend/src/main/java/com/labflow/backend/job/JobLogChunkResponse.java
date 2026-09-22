package com.labflow.backend.job;

import java.time.Instant;

public record JobLogChunkResponse(
        long id,
        long attemptId,
        long seqNo,
        String stream,
        Instant emittedAt,
        String content
) {
}
