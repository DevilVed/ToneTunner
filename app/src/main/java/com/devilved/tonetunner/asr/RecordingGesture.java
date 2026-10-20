package com.devilved.tonetunner.asr;

/** A short tap toggles recording; holding preserves push-to-talk without any duration cap. */
public final class RecordingGesture {
    public enum Action { START, STOP, NONE }
    private static final long HOLD_MILLIS = 350;
    private boolean startedOnDown;
    private long downTime;

    public Action down(boolean recording, long eventTime) {
        downTime = eventTime;
        startedOnDown = !recording;
        return recording ? Action.STOP : Action.START;
    }

    public Action up(long eventTime) {
        boolean held = startedOnDown && eventTime - downTime >= HOLD_MILLIS;
        startedOnDown = false;
        return held ? Action.STOP : Action.NONE;
    }

    public void cancel() { startedOnDown = false; }
}
