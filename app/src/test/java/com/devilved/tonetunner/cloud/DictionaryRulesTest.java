package com.devilved.tonetunner.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.Test;

public class DictionaryRulesTest {

    @Test
    public void replacementsUseLongestMatchFirstAndNeverCascade() throws Exception {
        String text = "New York and New. Teh, then the.";
        String rules = "New => N\n"
                + "New York => NYC\n"
                + "teh => the\n"
                + "the => THE";

        assertEquals("NYC and N. the, then THE.", apply(text, "", rules));
    }

    @Test
    public void combiningMarksAreWordCharactersAtReplacementBoundaries() throws Exception {
        String text = "Ann\u0301ette met ANN.";

        assertEquals("Ann\u0301ette met Dr. Ann.", apply(text, "", "Ann => Dr. Ann"));
    }

    @Test
    public void dollarSignsAndBackslashesAreLiteralText() throws Exception {
        String text = "The key is $x and path C:\\tmp.";
        String rules = "$x => $cash\nC:\\tmp => C:\\safe";

        assertEquals("The key is $cash and path C:\\safe.", apply(text, "", rules));
    }

    @Test
    public void vocabularyCorrectsOnlyWholeNamesAndUsesCanonicalCasing() throws Exception {
        String text = "Please ask DEVILVED and tonetunner, not tonetunnerish.";
        String vocabulary = " DevilVed, ToneTunner\nDEVILVED, ToneTunner ";

        assertEquals("Please ask DevilVed and ToneTunner, not tonetunnerish.",
                apply(text, vocabulary, ""));
    }

    @Test
    public void validateAcceptsEmptyRulesAndRejectsMalformedRuleLines() throws Exception {
        validate("", "");
        validate("ToneTunner, DevilVed", " tone tuner => ToneTunner \n");

        String[] malformed = {
                "heard to preferred",
                "=> preferred",
                "heard =>",
                "heard => preferred => extra"
        };
        for (String rule : malformed) {
            assertThrows(IllegalArgumentException.class, () -> validate("", rule));
        }
    }

    private String apply(String text, String vocabulary, String replacements) throws Exception {
        Method method = dictionaryMethod("apply", String.class, String.class, String.class);
        return (String) invoke(method, null, text, vocabulary, replacements);
    }

    private void validate(String vocabulary, String replacements) throws Exception {
        Method method = dictionaryMethod("validate", String.class, String.class);
        invoke(method, null, vocabulary, replacements);
    }

    private Method dictionaryMethod(String name, Class<?>... parameterTypes) {
        try {
            return Class.forName("com.devilved.tonetunner.cloud.DictionaryRules")
                    .getMethod(name, parameterTypes);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new AssertionError("DictionaryRules must expose public " + name + " API.", e);
        }
    }

    private Object invoke(Method method, Object receiver, Object... arguments) throws Exception {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new AssertionError("DictionaryRules invocation failed.", cause);
        }
    }
}
