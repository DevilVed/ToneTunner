package com.devilved.tonetunner.asr;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Microphone choice: the reference app's preferred-then-built-in-first resolution, on Android kinds. */
public class MicRouterTest {
    private static final MicRouter.Input PHONE_BACK = new MicRouter.Input(1, MicRouter.Kind.BUILTIN, "back");
    private static final MicRouter.Input PHONE_BOTTOM = new MicRouter.Input(2, MicRouter.Kind.BUILTIN, "bottom");
    private static final MicRouter.Input HEADSET = new MicRouter.Input(3, MicRouter.Kind.BLUETOOTH, "00:11");
    private static final MicRouter.Input WIRED = new MicRouter.Input(4, MicRouter.Kind.WIRED, "");
    private static final MicRouter.Input OTHER = new MicRouter.Input(5, MicRouter.Kind.OTHER, "");

    @Test public void defaultModeIsThePhoneMicrophone() {
        assertEquals(MicRouter.Mode.BUILTIN, MicRouter.Mode.fromKey(null));
        assertEquals(MicRouter.Mode.BUILTIN, MicRouter.Mode.fromKey("unknown"));
        assertEquals(MicRouter.Mode.BLUETOOTH, MicRouter.Mode.fromKey("bluetooth"));
    }

    @Test public void phoneModeIgnoresAConnectedBluetoothHeadset() {
        MicRouter.Resolution r = MicRouter.resolve(MicRouter.Mode.BUILTIN,
                Arrays.asList(HEADSET, PHONE_BACK, PHONE_BOTTOM, WIRED));
        assertSame(PHONE_BOTTOM, r.input);
        assertFalse(r.fellBack);
        assertFalse("Bluetooth SCO must never start in phone mode", r.useBluetoothSco);
    }

    @Test public void bottomMicrophoneIsPreferredOverOtherBuiltInMicrophones() {
        assertSame(PHONE_BOTTOM, MicRouter.resolve(MicRouter.Mode.BUILTIN,
                Arrays.asList(PHONE_BACK, PHONE_BOTTOM)).input);
        assertSame("without a bottom mic, the first built-in one wins", PHONE_BACK,
                MicRouter.resolve(MicRouter.Mode.BUILTIN, Collections.singletonList(PHONE_BACK)).input);
    }

    @Test public void phoneModeWithoutABuiltInMicrophoneNeverFallsBackToBluetooth() {
        MicRouter.Resolution r = MicRouter.resolve(MicRouter.Mode.BUILTIN, Arrays.asList(HEADSET, WIRED));
        assertSame(WIRED, r.input);
        assertTrue(r.fellBack);
        assertFalse(r.useBluetoothSco);
        MicRouter.Resolution onlyBluetooth = MicRouter.resolve(MicRouter.Mode.BUILTIN,
                Collections.singletonList(HEADSET));
        assertNull("Android's own routing is used rather than Bluetooth", onlyBluetooth.input);
        assertFalse(onlyBluetooth.useBluetoothSco);
    }

    @Test public void bluetoothModeUsesTheHeadsetAndStartsSco() {
        MicRouter.Resolution r = MicRouter.resolve(MicRouter.Mode.BLUETOOTH,
                Arrays.asList(PHONE_BOTTOM, HEADSET));
        assertSame(HEADSET, r.input);
        assertTrue(r.useBluetoothSco);
        assertFalse(r.fellBack);
    }

    @Test public void bluetoothModeFallsBackToThePhoneMicrophoneFirst() {
        MicRouter.Resolution r = MicRouter.resolve(MicRouter.Mode.BLUETOOTH,
                Arrays.asList(WIRED, PHONE_BACK, PHONE_BOTTOM));
        assertSame(PHONE_BOTTOM, r.input);
        assertTrue(r.fellBack);
        assertFalse(r.useBluetoothSco);
    }

    @Test public void wiredModeUsesWiredThenPhone() {
        assertSame(WIRED, MicRouter.resolve(MicRouter.Mode.WIRED, Arrays.asList(PHONE_BOTTOM, WIRED)).input);
        MicRouter.Resolution r = MicRouter.resolve(MicRouter.Mode.WIRED, Arrays.asList(OTHER, PHONE_BACK));
        assertSame(PHONE_BACK, r.input);
        assertTrue(r.fellBack);
    }

    @Test public void automaticModeLeavesRoutingToAndroid() {
        MicRouter.Resolution r = MicRouter.resolve(MicRouter.Mode.AUTOMATIC, Arrays.asList(HEADSET, PHONE_BOTTOM));
        assertNull(r.input);
        assertFalse(r.fellBack);
        assertFalse(r.useBluetoothSco);
    }

    @Test public void noInputsAndNullListsAreSafe() {
        for (MicRouter.Mode mode : MicRouter.Mode.values()) {
            assertNull(MicRouter.resolve(mode, Collections.emptyList()).input);
            assertNull(MicRouter.resolve(mode, null).input);
        }
        assertNull(MicRouter.resolve(MicRouter.Mode.BUILTIN, Arrays.asList(null, null)).input);
    }

    @Test public void duplicateDevicesAreConsideredOnce() {
        MicRouter.Resolution r = MicRouter.resolve(MicRouter.Mode.WIRED,
                Arrays.asList(WIRED, WIRED, PHONE_BOTTOM));
        assertSame(WIRED, r.input);
    }
}
