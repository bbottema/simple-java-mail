package org.simplejavamail.internal.configresolution;

/** Test-only reference over finite choice categories; it does not call production selection or comparison. */
public final class ConfigurationContractModel {
    public static final int INHERIT = 0;
    public static final int VALUE_A = 1;
    public static final int VALUE_B = 2;
    public static final int REMOVE = 3;

    private ConfigurationContractModel() { }

    public static int selectedIndex(final int[] choices, final int[] priority) {
        for (int candidate : priority) {
            if (choices[candidate] != INHERIT) {
                return candidate;
            }
        }
        return -1;
    }

    public static boolean conflicts(final int[] declarations, final int fallbackIndex) {
        int established = INHERIT;
        for (int index = 0; index < declarations.length; index++) {
            if (index == fallbackIndex || declarations[index] == INHERIT) {
                continue;
            }
            if (established != INHERIT && established != declarations[index]) {
                return true;
            }
            established = declarations[index];
        }
        return false;
    }

    public static int[] digits(final int number, final int radix, final int length) {
        final int[] result = new int[length];
        int remaining = number;
        for (int index = 0; index < length; index++) {
            result[index] = remaining % radix;
            remaining /= radix;
        }
        return result;
    }
}
