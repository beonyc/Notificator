package com.example.notificator.util;

/** Склонение русских слов по числу. */
public final class Plurals {

    private Plurals() {
    }

    /** Выбирает форму слова по числу: 1 дело, 2 дела, 5 дел, 11 дел, 21 дело. */
    public static String plural(int n, String one, String few, String many) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        if (mod10 == 1) {
            return one;
        }
        return (mod10 >= 2 && mod10 <= 4) ? few : many;
    }
}
