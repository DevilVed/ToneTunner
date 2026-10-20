package com.devilved.tonetunner.asr;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;

public class LocalModelGateTest {
    @Test public void replacementWaitsUntilNativeCleanupReturnsItsLease() throws Exception {
        LocalModelGate gate = new LocalModelGate();
        LocalModelGate.Lease first = gate.acquire();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<LocalModelGate.Lease> next = pool.submit(gate::acquire);
            assertThrows(TimeoutException.class, () -> next.get(100, TimeUnit.MILLISECONDS));
            first.close();
            next.get(2, TimeUnit.SECONDS).close();
        } finally { first.close(); pool.shutdownNow(); }
    }
    @Test public void duplicateCleanupDoesNotAllowTwoModelsInMemory() throws Exception {
        LocalModelGate gate = new LocalModelGate();
        LocalModelGate.Lease old = gate.acquire();
        old.close(); old.close();
        LocalModelGate.Lease current = gate.acquire();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<LocalModelGate.Lease> next = pool.submit(gate::acquire);
            assertThrows(TimeoutException.class, () -> next.get(100, TimeUnit.MILLISECONDS));
            current.close();
            next.get(2, TimeUnit.SECONDS).close();
        } finally { current.close(); pool.shutdownNow(); }
    }
}
