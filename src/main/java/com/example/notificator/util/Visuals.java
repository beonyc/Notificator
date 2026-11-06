package com.example.notificator.util;

/** Мелкие элементы оформления сообщений. */
public final class Visuals {

    /** Длина полоски прогресса в символах: один символ = 10%. */
    static final int BAR_LENGTH = 10;

    private Visuals() {
    }

    /** Процент выполнения, округлённый до целого; 0, если дел нет. */
    public static int percent(int done, int total) {
        return total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
    }

    /**
     * Полоска прогресса из 10 символов, например 60% → «▰▰▰▰▰▰▱▱▱▱».
     * Значения вне 0–100 обрезаются до границ.
     */
    public static String bar(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        int filled = (int) Math.round(clamped * BAR_LENGTH / 100.0);
        return "▰".repeat(filled) + "▱".repeat(BAR_LENGTH - filled);
    }

    /** Смайлик для оценки продуктивности по её тексту («Огонь», «Хорошо», «Средне», «Проленился»). */
    public static String verdictEmoji(String verdict) {
        return switch (verdict) {
            case "Огонь" -> "🔥";
            case "Хорошо" -> "👍";
            case "Средне" -> "😐";
            default -> "😴";
        };
    }
}
