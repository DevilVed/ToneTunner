package com.devilved.tonetunner.asr;

/** Tracks ownership of asynchronous work; cancellation also invalidates queued UI callbacks. */
public final class OperationGate {
    private long generation;
    private boolean busy;

    public synchronized long begin() {
        if (busy) return -1;
        busy = true;
        return ++generation;
    }

    public synchronized void cancel() {
        ++generation;
        busy = false;
    }

    public synchronized boolean isCurrent(long token) {
        return token == generation && busy;
    }

    public synchronized boolean complete(long token) {
        if (!isCurrent(token)) return false;
        busy = false;
        return true;
    }

    public synchronized boolean isBusy() { return busy; }
}
