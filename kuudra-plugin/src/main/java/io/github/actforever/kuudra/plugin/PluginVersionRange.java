package io.github.actforever.kuudra.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Maven/Forge-style single version interval, for example {@code [0.1.0,0.3.5)}.
 * Bounds use numeric dot-separated plugin versions with optional {@code -prerelease} and
 * {@code +build} suffixes; a leading {@code v} is invalid.
 */
public record PluginVersionRange(String lower, boolean includeLower, String upper, boolean includeUpper) {
    public static PluginVersionRange parse(String expression) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("versionRange must not be blank");
        String value = expression.trim();
        if (value.length() < 3 || (value.charAt(0) != '[' && value.charAt(0) != '(')
                || (value.charAt(value.length() - 1) != ']' && value.charAt(value.length() - 1) != ')'))
            throw new IllegalArgumentException("Invalid versionRange: " + expression);
        String body = value.substring(1, value.length() - 1).trim();
        String lower; String upper;
        int comma = body.indexOf(',');
        if (comma < 0) {
            if (value.charAt(0) != '[' || value.charAt(value.length() - 1) != ']' || body.isBlank())
                throw new IllegalArgumentException("Exact versionRange must use [version]: " + expression);
            lower = body; upper = body;
        } else {
            if (body.indexOf(',', comma + 1) >= 0) throw new IllegalArgumentException("Only one version interval is supported: " + expression);
            lower = body.substring(0, comma).trim(); upper = body.substring(comma + 1).trim();
            if (lower.isBlank() && upper.isBlank()) throw new IllegalArgumentException("Unbounded versionRange is not allowed: " + expression);
        }
        if (!lower.isBlank()) validateVersion(lower);
        if (!upper.isBlank()) validateVersion(upper);
        if (!lower.isBlank() && !upper.isBlank() && compare(lower, upper) > 0)
            throw new IllegalArgumentException("versionRange lower bound exceeds upper bound: " + expression);
        return new PluginVersionRange(lower, value.charAt(0) == '[', upper, value.charAt(value.length() - 1) == ']');
    }

    public boolean contains(String version) {
        validateVersion(version);
        if (!lower.isBlank()) { int compared = compare(version, lower); if (compared < 0 || (compared == 0 && !includeLower)) return false; }
        if (!upper.isBlank()) { int compared = compare(version, upper); if (compared > 0 || (compared == 0 && !includeUpper)) return false; }
        return true;
    }

    private static void validateVersion(String version) {
        if (version == null || !version.matches("[0-9]+(?:\\.[0-9]+)*(?:[-+][0-9A-Za-z.-]+)?"))
            throw new IllegalArgumentException("Invalid plugin version: " + version);
    }

    private static int compare(String left, String right) {
        ParsedVersion a = ParsedVersion.parse(left); ParsedVersion b = ParsedVersion.parse(right);
        int length = Math.max(a.numbers.size(), b.numbers.size());
        for (int i = 0; i < length; i++) {
            int compared = Integer.compare(i < a.numbers.size() ? a.numbers.get(i) : 0,
                    i < b.numbers.size() ? b.numbers.get(i) : 0);
            if (compared != 0) return compared;
        }
        if (a.qualifier.isEmpty() != b.qualifier.isEmpty()) return a.qualifier.isEmpty() ? 1 : -1;
        String[] leftParts = a.qualifier.split("[.-]"); String[] rightParts = b.qualifier.split("[.-]");
        int qualifierLength = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < qualifierLength; i++) {
            if (i >= leftParts.length) return -1;
            if (i >= rightParts.length) return 1;
            boolean leftNumeric = leftParts[i].matches("[0-9]+"); boolean rightNumeric = rightParts[i].matches("[0-9]+");
            int compared = leftNumeric && rightNumeric ? Integer.compare(Integer.parseInt(leftParts[i]), Integer.parseInt(rightParts[i]))
                    : leftNumeric != rightNumeric ? (leftNumeric ? -1 : 1) : leftParts[i].compareToIgnoreCase(rightParts[i]);
            if (compared != 0) return compared;
        }
        return 0;
    }

    private record ParsedVersion(List<Integer> numbers, String qualifier) {
        static ParsedVersion parse(String version) {
            validateVersion(version); int separator = Math.min(indexOrEnd(version, '-'), indexOrEnd(version, '+'));
            String[] segments = version.substring(0, separator).split("\\."); List<Integer> numbers = new ArrayList<>();
            for (String segment : segments) numbers.add(Integer.parseInt(segment));
            int prerelease = version.indexOf('-'); int build = version.indexOf('+');
            String qualifier = prerelease < 0 ? "" : version.substring(prerelease + 1, build < 0 ? version.length() : build);
            return new ParsedVersion(List.copyOf(numbers), qualifier);
        }
        private static int indexOrEnd(String value, char character) { int index = value.indexOf(character); return index < 0 ? value.length() : index; }
    }
}
