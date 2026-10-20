package com.devilved.tonetunner.asr;

import org.junit.Test;
import static org.junit.Assert.*;

public class OperationGateTest {
    @Test public void secondStartIsRejectedUntilCompletion() {
        OperationGate gate = new OperationGate();
        long first = gate.begin();
        assertTrue(first > 0);
        assertEquals(-1, gate.begin());
        assertTrue(gate.complete(first));
        assertTrue(gate.begin() > first);
    }
    @Test public void cancelledResultCannotCompleteNewOperation() {
        OperationGate gate = new OperationGate();
        long old = gate.begin();
        gate.cancel();
        long current = gate.begin();
        assertFalse(gate.isCurrent(old));
        assertFalse(gate.complete(old));
        assertTrue(gate.isBusy());
        assertTrue(gate.complete(current));
    }
    @Test public void cancellationInvalidatesAlreadyQueuedDelivery() {
        OperationGate gate = new OperationGate();
        long token = gate.begin();
        gate.cancel();
        assertFalse(gate.isCurrent(token));
        assertFalse(gate.complete(token));
        assertFalse(gate.isBusy());
    }
    @Test public void terminalCallbackCanOnlyBeDeliveredOnce() {
        OperationGate gate = new OperationGate();
        long token = gate.begin();
        assertTrue(gate.complete(token));
        assertFalse(gate.complete(token));
    }
}
