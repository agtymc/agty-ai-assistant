package org.agty.aiassistant.changes;

import java.util.regex.Pattern;

/** Counts hunk data, including content beginning with ++ or --, without counting file headers. */
public record PatchSummary(int added, int removed) {
    private static final Pattern HEADER = Pattern.compile("@@ -\\d+(?:,(\\d+))? \\+\\d+(?:,(\\d+))? @@.*");
    public static PatchSummary of(String patch) {
        int added = 0, removed = 0, oldRemaining = 0, newRemaining = 0;
        for (String line : patch.split("\n")) {
            var header = HEADER.matcher(line.stripTrailing());
            if (header.matches()) {
                oldRemaining = header.group(1) == null ? 1 : number(header.group(1));
                newRemaining = header.group(2) == null ? 1 : number(header.group(2)); continue;
            }
            if (oldRemaining == 0 && newRemaining == 0) continue;
            if (line.startsWith("+")) { added++; newRemaining--; }
            else if (line.startsWith("-")) { removed++; oldRemaining--; }
            else if (line.startsWith(" ")) { oldRemaining--; newRemaining--; }
        }
        return new PatchSummary(added, removed);
    }
    private static int number(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 0; } }
    @Override public String toString() { return "+" + added + " −" + removed; }
}
