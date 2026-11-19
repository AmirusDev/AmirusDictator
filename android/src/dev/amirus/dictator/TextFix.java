package dev.amirus.dictator;

import android.content.SharedPreferences;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cleans up one recognized phrase. Mirrors processPhrase() in the web app's index.html. */
final class TextFix {
    private static final String EDGE_L = "(?<![\\p{L}\\p{M}\\p{N}])";
    private static final String EDGE_R = "(?![\\p{L}\\p{M}\\p{N}])";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String BN_DIGITS = "০১২৩৪৫৬৭৮৯";

    // First entry of each row is the output, the rest are the spoken forms.
    private static final String[][] COMMANDS = {
            {"\n\n", "নতুন প্যারাগ্রাফ", "নতুন অনুচ্ছেদ", "new paragraph"},
            {"\n", "নতুন লাইন", "পরের লাইন", "new line"},
            {"?", "প্রশ্নবোধক চিহ্ন", "প্রশ্ন চিহ্ন", "question mark"},
            {"!", "বিস্ময়বোধক চিহ্ন", "বিস্ময়সূচক চিহ্ন", "বিস্ময় চিহ্ন", "আশ্চর্যবোধক চিহ্ন", "exclamation mark", "exclamation point"},
            {";", "সেমিকোলন", "semicolon"},
            {":", "কোলন", "colon"},
            {"।", "দাঁড়ি", "দাড়ি"},
            {".", "full stop"},
            {",", "কমা", "comma"},
    };
    private static final Pattern[] COMMAND_RE = new Pattern[COMMANDS.length];

    static {
        for (int i = 0; i < COMMANDS.length; i++) {
            StringBuilder alts = new StringBuilder();
            for (int j = 1; j < COMMANDS[i].length; j++) {
                if (j > 1) alts.append('|');
                alts.append(nfc(COMMANDS[i][j]).replace(" ", "\\s+"));
            }
            String tail = COMMANDS[i][0].startsWith("\n") ? "[ \\t]*" : "";
            COMMAND_RE[i] = Pattern.compile("[ \\t]*" + EDGE_L + "(?:" + alts + ")" + EDGE_R + tail, FLAGS);
        }
    }

    private TextFix() {
    }

    private static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    static String process(String raw, String before, boolean bn, SharedPreferences p) {
        String t = nfc(raw).trim();
        if (t.isEmpty()) return "";
        boolean autocorrect = p.getBoolean("autocorrect", true);

        if (p.getBoolean("commands", true)) {
            for (int i = 0; i < COMMANDS.length; i++) {
                t = COMMAND_RE[i].matcher(t).replaceAll(Matcher.quoteReplacement(COMMANDS[i][0]));
            }
        }
        t = applyReplacements(t, p.getString("replacements", ""));
        if (autocorrect) t = tidy(t, bn && p.getBoolean("dari", true));

        String digits = p.getString("digits", "keep");
        if ("bn".equals(digits) && bn) t = mapDigits(t, true);
        else if ("en".equals(digits)) t = mapDigits(t, false);

        if (p.getBoolean("autoEnd", false) && !t.isEmpty() && " \t\n.?!।,;:".indexOf(t.charAt(t.length() - 1)) < 0) {
            t += bn ? "।" : ".";
        }
        if (autocorrect && p.getBoolean("capitalize", true)) {
            String ctx = before.replaceAll("[ \\t]+$", "");
            if (ctx.isEmpty() || ".?!।\n".indexOf(ctx.charAt(ctx.length() - 1)) >= 0) t = upperAt(t, "^(\\s*)([a-z])");
            t = upperAt(t, "([.?!।]\\s+|\\n)([a-z])");
            t = t.replaceAll("\\bi\\b", "I");
        }
        if (t.isEmpty()) return "";
        boolean beforeEndsInSpace = before.isEmpty() || Character.isWhitespace(before.charAt(before.length() - 1));
        if (!before.isEmpty() && !beforeEndsInSpace && " \t\n,.?!।;:)]".indexOf(t.charAt(0)) < 0) t = " " + t;
        else if (!before.isEmpty() && beforeEndsInSpace) t = t.replaceAll("^[ \\t]+", "");
        return t;
    }

    private static String applyReplacements(String t, String rules) {
        for (String line : rules.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 1) continue;
            String from = nfc(line.substring(0, eq).trim());
            String to = line.substring(eq + 1).trim();
            if (from.isEmpty()) continue;
            StringBuilder re = new StringBuilder(EDGE_L);
            String[] words = from.split("\\s+");
            for (int i = 0; i < words.length; i++) {
                if (i > 0) re.append("\\s+");
                re.append(Pattern.quote(words[i]));
            }
            re.append(EDGE_R);
            try {
                t = Pattern.compile(re.toString(), FLAGS).matcher(t).replaceAll(Matcher.quoteReplacement(to));
            } catch (RuntimeException ignored) {
                // A malformed rule should never break dictation.
            }
        }
        return t;
    }

    private static String tidy(String t, boolean dari) {
        t = t.replaceAll("[ \\t]*\\n[ \\t]*", "\n").replaceAll("[ \\t]{2,}", " ");
        t = t.replaceAll("[ \\t]+([,.?!।;:])", "$1");
        t = t.replaceAll("([,?!।;:])\\1+", "$1");
        t = t.replaceAll("([,?!।;])(?=[^\\s,.?!।;:\\d\"')\\]])", "$1 ");
        if (dari) t = t.replaceAll("(?<=[\\u0980-\\u09FF])\\.(?=\\s|$)", "।");
        return t.replaceAll("^[ \\t]+|[ \\t]+$", "");
    }

    private static String mapDigits(String t, boolean toBengali) {
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (toBengali && c >= '0' && c <= '9') sb.append(BN_DIGITS.charAt(c - '0'));
            else if (!toBengali && BN_DIGITS.indexOf(c) >= 0) sb.append((char) ('0' + BN_DIGITS.indexOf(c)));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Upper-cases group 2 of every match, keeping group 1. */
    private static String upperAt(String t, String regex) {
        Matcher m = Pattern.compile(regex).matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2).toUpperCase()));
        m.appendTail(sb);
        return sb.toString();
    }
}
