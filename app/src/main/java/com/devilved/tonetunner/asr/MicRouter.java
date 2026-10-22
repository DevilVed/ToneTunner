package com.devilved.tonetunner.asr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chooses the microphone for a recording. Same resolution as the reference desktop app: the
 * devices preferred by the selected mode first, then every built-in microphone, then the rest;
 * the first connected one wins. Bluetooth is only ever chosen in Bluetooth mode, so the phone
 * microphone default never starts a Bluetooth SCO link.
 */
public final class MicRouter {
    private MicRouter() {}

    public enum Mode {
        BUILTIN("builtin"), BLUETOOTH("bluetooth"), WIRED("wired"), AUTOMATIC("automatic");

        public final String key;

        Mode(String key) { this.key = key; }

        public static Mode fromKey(String key) {
            for (Mode mode : values()) if (mode.key.equals(key)) return mode;
            return BUILTIN;
        }
    }

    public enum Kind { BUILTIN, BLUETOOTH, WIRED, OTHER }

    /** A connected input, reduced to what the choice needs. */
    public static final class Input {
        public final int id;
        public final Kind kind;
        public final String address;

        public Input(int id, Kind kind, String address) {
            this.id = id;
            this.kind = kind;
            this.address = address == null ? "" : address;
        }
    }

    public static final class Resolution {
        /** The microphone to request, or null to leave routing to Android. */
        public final Input input;
        /** True when the mode's own kind of microphone was not connected. */
        public final boolean fellBack;
        /** True only when a Bluetooth headset microphone was chosen in Bluetooth mode. */
        public final boolean useBluetoothSco;

        Resolution(Input input, boolean fellBack) {
            this.input = input;
            this.fellBack = fellBack;
            this.useBluetoothSco = input != null && input.kind == Kind.BLUETOOTH;
        }
    }

    public static Resolution resolve(Mode mode, List<Input> connected) {
        if (mode == Mode.AUTOMATIC || connected == null) return new Resolution(null, false);
        Set<Input> unique = new LinkedHashSet<>();
        for (Input input : connected) if (input != null) unique.add(input);

        List<Input> preferred = ofKind(unique, preferredKind(mode));
        if (!preferred.isEmpty()) return new Resolution(preferred.get(0), false);

        List<Input> fallback = ofKind(unique, Kind.BUILTIN);
        for (Input input : unique) {
            // Bluetooth needs an explicit SCO link; it is never a silent fallback.
            if (input.kind != Kind.BUILTIN && input.kind != Kind.BLUETOOTH) fallback.add(input);
        }
        return fallback.isEmpty() ? new Resolution(null, false) : new Resolution(fallback.get(0), true);
    }

    private static Kind preferredKind(Mode mode) {
        switch (mode) {
            case BLUETOOTH: return Kind.BLUETOOTH;
            case WIRED: return Kind.WIRED;
            default: return Kind.BUILTIN;
        }
    }

    /** Inputs of one kind; for built-in microphones the bottom (voice) microphone comes first. */
    private static List<Input> ofKind(Set<Input> inputs, Kind kind) {
        List<Input> result = new ArrayList<>();
        for (Input input : inputs) {
            if (input.kind != kind) continue;
            if (kind == Kind.BUILTIN && input.address.toLowerCase(Locale.ROOT).contains("bottom")) {
                result.add(0, input);
            } else {
                result.add(input);
            }
        }
        return result;
    }
}
