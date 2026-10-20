package com.devilved.tonetunner.asr;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** A lease is released only after native sessions actually close, including after cancellation. */
final class LocalModelGate {
    private final Semaphore available = new Semaphore(1, true);
    Lease acquire() throws IOException, InterruptedException {
        if (!available.tryAcquire(90, TimeUnit.SECONDS))
            throw new IOException("A previous local transcription is still finishing. Please try again.");
        return new Lease();
    }
    final class Lease implements AutoCloseable {
        private final AtomicBoolean returned = new AtomicBoolean();
        @Override public void close() {
            if (returned.compareAndSet(false, true)) available.release();
        }
    }
}
