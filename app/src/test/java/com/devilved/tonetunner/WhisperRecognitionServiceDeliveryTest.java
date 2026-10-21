package com.devilved.tonetunner;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;

import androidx.preference.PreferenceManager;

import com.devilved.tonetunner.asr.Recorder;
import com.devilved.tonetunner.asr.WhisperResult;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The framework result callback is the acceptance boundary for a recognition-service delivery. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = WhisperRecognitionServiceDeliveryTest.RecorderShadow.class)
public class WhisperRecognitionServiceDeliveryTest {

    @Test public void readyForSpeechWaitsUntilRecorderConfirmsMicrophoneIsReady() throws Exception {
        RecorderShadow.reset();
        RecognitionFixture fixture = startListening(new ListenerState(false));
        try {
            assertEquals("No speech UI should start before AudioRecord reports it is ready",
                    0, fixture.listener.readyForSpeechCalls);

            RecorderShadow.emit(Recorder.MSG_RECORDING);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals(1, fixture.listener.readyForSpeechCalls);
            assertEquals("Microphone readiness must not be reported as detected speech",
                    0, fixture.listener.beginningOfSpeechCalls);
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void beginningOfSpeechWaitsForRecorderSpeechDetection() throws Exception {
        RecorderShadow.reset();
        RecognitionFixture fixture = startListening(new ListenerState(false));
        try {
            assertEquals("Starting microphone capture is not speech detection",
                    0, fixture.listener.beginningOfSpeechCalls);
            RecorderShadow.emit(Recorder.MSG_RECORDING);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, fixture.listener.beginningOfSpeechCalls);

            // The literal matches Recorder.MSG_SPEECH_STARTED, which lands with the recorder fix.
            RecorderShadow.emit("Speech started...");
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals(1, fixture.listener.beginningOfSpeechCalls);
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void disconnectedClientRejectsDeliveryAndStillReleasesTheRequest() throws Exception {
        RecorderShadow.reset();
        ServiceController<WhisperRecognitionService> controller =
                Robolectric.buildService(WhisperRecognitionService.class).create();
        WhisperRecognitionService service = controller.get();
        ListenerState listener = new ListenerState(true);
        Object request = installRequest(service, listener);

        try {
            Object delivered = deliver(service, request);

            assertEquals("a RemoteException from the framework callback must reject delivery",
                    Boolean.FALSE, delivered);
            assertEquals(1, listener.endOfSpeechCalls);
            assertEquals(1, listener.resultCalls);
            assertEquals(0, listener.errorCalls);
            assertNull("a terminal callback must clear the active request",
                    field(WhisperRecognitionService.class, "activeRequest").get(service));
            assertTrue("disconnected requests must release their recorder", RecorderShadow.released);
        } finally {
            controller.destroy();
        }
    }

    @Test public void acceptedClientConfirmsDeliveryAndReleasesTheRequest() throws Exception {
        RecorderShadow.reset();
        ServiceController<WhisperRecognitionService> controller =
                Robolectric.buildService(WhisperRecognitionService.class).create();
        WhisperRecognitionService service = controller.get();
        ListenerState listener = new ListenerState(false);
        Object request = installRequest(service, listener);

        try {
            Object delivered = deliver(service, request);

            assertEquals(Boolean.TRUE, delivered);
            assertEquals(1, listener.endOfSpeechCalls);
            assertEquals(1, listener.resultCalls);
            assertEquals(0, listener.errorCalls);
            assertNull("a terminal callback must clear the active request",
                    field(WhisperRecognitionService.class, "activeRequest").get(service));
            assertTrue("completed requests must release their recorder", RecorderShadow.released);
        } finally {
            controller.destroy();
        }
    }

    private static Object installRequest(WhisperRecognitionService service, ListenerState state)
            throws Exception {
        RecognitionService.Callback callback = callback(service, state);
        Class<?> requestClass = Class.forName(
                "com.devilved.tonetunner.WhisperRecognitionService$RecognitionRequest");
        Constructor<?> constructor = requestClass.getDeclaredConstructor(long.class,
                RecognitionService.Callback.class, String.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object request = constructor.newInstance(7L, callback, "en", false, false);

        field(requestClass, "recorder").set(request, new Recorder(service));
        field(WhisperRecognitionService.class, "requestSequence").setLong(service, 7L);
        field(WhisperRecognitionService.class, "activeRequest").set(service, request);
        return request;
    }

    private static RecognitionFixture startListening(ListenerState state) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear()
                .putString("transcription.provider", "LOCAL")
                .putBoolean("enhancement.enabled", false)
                .putBoolean("cloud.consent", false).commit();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(WhisperRecognitionService.PREF_AUTO_STOP_AFTER_SILENCE, true).commit();

        ServiceController<WhisperRecognitionService> controller =
                Robolectric.buildService(WhisperRecognitionService.class).create();
        WhisperRecognitionService service = controller.get();
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.RECORD_AUDIO);
        RecognitionService.Callback callback = callback(service, state);
        Method startListening = WhisperRecognitionService.class.getDeclaredMethod(
                "onStartListening", Intent.class, RecognitionService.Callback.class);
        startListening.setAccessible(true);
        startListening.invoke(service, new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), callback);
        return new RecognitionFixture(controller, service, state);
    }

    private static RecognitionService.Callback callback(WhisperRecognitionService service,
            ListenerState state) throws Exception {
        // Select the API 28 constructor by its AOSP signature. Reflection also exposes an access
        // bridge and Robolectric's empty instrumentation constructor; neither initializes mListener.
        Class<?> listenerType = Class.forName("android.speech.IRecognitionListener");
        Constructor<?> constructor = RecognitionService.Callback.class.getDeclaredConstructor(
                RecognitionService.class, listenerType, int.class);
        constructor.setAccessible(true);
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> parameterType = parameterTypes[index];
            if (parameterType.isInstance(service)) {
                arguments[index] = service;
            } else if (parameterType == int.class) {
                arguments[index] = 0;
            } else if (parameterType.isInterface()) {
                arguments[index] = Proxy.newProxyInstance(parameterType.getClassLoader(),
                        new Class<?>[]{parameterType}, state);
            } else {
                throw new IllegalStateException("Unexpected RecognitionService.Callback constructor parameter: "
                        + parameterType.getName());
            }
        }
        return (RecognitionService.Callback) constructor.newInstance(arguments);
    }

    private static Object deliver(WhisperRecognitionService service, Object request) throws Exception {
        Class<?> requestClass = request.getClass();
        Method method = WhisperRecognitionService.class.getDeclaredMethod("handleResult",
                requestClass, WhisperResult.class);
        method.setAccessible(true);
        return method.invoke(service, request,
                new WhisperResult("recognized words", "en", Recognizer.ACTION_TRANSCRIBE));
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class ListenerState implements InvocationHandler {
        final boolean disconnectedOnResults;
        int endOfSpeechCalls;
        int resultCalls;
        int errorCalls;
        int readyForSpeechCalls;
        int beginningOfSpeechCalls;

        ListenerState(boolean disconnectedOnResults) {
            this.disconnectedOnResults = disconnectedOnResults;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] arguments)
                throws Throwable {
            if ("onReadyForSpeech".equals(method.getName())) readyForSpeechCalls++;
            if ("onBeginningOfSpeech".equals(method.getName())) beginningOfSpeechCalls++;
            if ("onEndOfSpeech".equals(method.getName())) endOfSpeechCalls++;
            if ("onResults".equals(method.getName())) {
                resultCalls++;
                if (disconnectedOnResults) throw new RemoteException("client disconnected");
            }
            if ("onError".equals(method.getName())) errorCalls++;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == float.class) return 0f;
            if (method.getReturnType() == double.class) return 0d;
            return null;
        }
    }

    @Implements(value = Recorder.class, isInAndroidSdk = false)
    public static class RecorderShadow {
        static boolean released;
        static boolean started;
        static Recorder.RecorderListener listener;

        static void reset() {
            released = false;
            started = false;
            listener = null;
        }

        static void emit(String message) {
            Recorder.RecorderListener current = listener;
            if (current != null) current.onUpdateReceived(message);
        }

        @Implementation protected void __constructor__(Context context) { }
        @Implementation protected void setListener(Recorder.RecorderListener value) { listener = value; }
        @Implementation protected void initVad() { }
        @Implementation protected void start() { started = true; }
        @Implementation protected boolean isInProgress() { return started; }
        @Implementation protected void stop() { started = false; }
        @Implementation protected void release() { released = true; started = false; }
    }

    private static final class RecognitionFixture {
        final ServiceController<WhisperRecognitionService> controller;
        final WhisperRecognitionService service;
        final ListenerState listener;

        RecognitionFixture(ServiceController<WhisperRecognitionService> controller,
                WhisperRecognitionService service, ListenerState listener) {
            this.controller = controller;
            this.service = service;
            this.listener = listener;
        }
    }
}
