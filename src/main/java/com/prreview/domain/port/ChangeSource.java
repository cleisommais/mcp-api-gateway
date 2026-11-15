package com.prreview.domain.port;

import com.prreview.domain.model.ChangeSnapshot;

public interface ChangeSource extends AutoCloseable {
    ChangeSnapshot snapshot() throws Exception;

    String fileAtCommit(String sha, String path) throws Exception;

    boolean isCurrent(ChangeSnapshot snapshot) throws Exception;

    @Override
    void close();
}
