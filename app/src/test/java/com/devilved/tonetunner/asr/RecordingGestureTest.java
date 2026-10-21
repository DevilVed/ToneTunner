package com.devilved.tonetunner.asr;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingGestureTest {
    @Test public void shortTapStartsAndStaysRecording() {
        RecordingGesture gesture = new RecordingGesture();
        assertEquals(RecordingGesture.Action.START, gesture.down(false, 1000));
        assertEquals(RecordingGesture.Action.NONE, gesture.up(1100));
    }
    @Test public void nextTapStopsWithoutRestartOnRelease() {
        RecordingGesture gesture = new RecordingGesture();
        assertEquals(RecordingGesture.Action.STOP, gesture.down(true, 1000));
        assertEquals(RecordingGesture.Action.NONE, gesture.up(1200));
    }
    @Test public void holdingForMoreThanThirtySecondsStillOnlyStopsOnRelease() {
        RecordingGesture gesture = new RecordingGesture();
        assertEquals(RecordingGesture.Action.START, gesture.down(false, 0));
        assertEquals(RecordingGesture.Action.STOP, gesture.up(120000));
    }
    @Test public void cancelledTouchCannotStopALaterRecording() {
        RecordingGesture gesture = new RecordingGesture();
        gesture.down(false, 1000);
        gesture.cancel();
        assertEquals(RecordingGesture.Action.NONE, gesture.up(2000));
    }
}
