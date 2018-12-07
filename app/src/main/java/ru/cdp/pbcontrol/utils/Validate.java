package ru.cdp.pbcontrol.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Validate {

    public static boolean isUrl(String value) {
        String regex = "^(https?|http)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]";
        return isMatch(value, regex);
    }

    private static boolean isMatch(String s, String pattern) {
        try {
            Pattern patt = Pattern.compile(pattern);
            Matcher matcher = patt.matcher(s);
            return matcher.matches();
        } catch (RuntimeException e) {
            return false;
        }
    }

}
