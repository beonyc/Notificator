package com.example.notificator.stats;

import com.example.notificator.util.Html;
import com.example.notificator.util.Plurals;
import com.example.notificator.util.Visuals;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Превращает {@link WeeklyStats} в текст сообщения для Telegram. Текст содержит HTML-разметку
 * (бот отправляет сообщения с parse_mode=HTML), поэтому названия дел экранируются.
 */
public final class ReportFormatter {

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM");
    private static final Locale RU = Locale.forLanguageTag("ru");

    private ReportFormatter() {
    }

    /**
     * @param finalReport true — итоги закончившейся недели (присылаются автоматически),
     *                    false — промежуточная статистика по запросу /week
     */
    public static String format(WeeklyStats s, boolean finalReport) {
        String header = "📊 <b>" + (finalReport ? "Итоги недели" : "Неделя в процессе") + "</b> "
                + SHORT_DATE.format(s.from()) + "–" + SHORT_DATE.format(s.to());
        if (!s.hasData()) {
            return header + "\n\n📭 За этот период дел не было.\nДобавь: /add Читать 30 минут";
        }

        StringBuilder sb = new StringBuilder(header).append("\n\n");
        sb.append(Visuals.bar(s.percent())).append(' ').append(s.percent()).append("%\n");
        sb.append("Выполнено <b>").append(s.done()).append("</b> из <b>").append(s.total()).append("</b>\n\n");

        sb.append("🏆 Оценка: <b>").append(s.score()).append("/100</b> — ")
                .append(Visuals.verdictEmoji(s.verdict())).append(' ').append(s.verdict()).append('\n');
        sb.append(comparison(s)).append("\n\n");

        if (s.best().date().equals(s.worst().date())) {
            sb.append("📍 День: ").append(dayLabel(s.best())).append('\n');
        } else {
            sb.append("🥇 Лучший день: ").append(dayLabel(s.best())).append('\n');
            sb.append("🥶 Худший день: ").append(dayLabel(s.worst())).append('\n');
        }
        if (s.streak() > 0) {
            sb.append("🔥 Серия 100%: ").append(s.streak()).append(' ')
                    .append(Plurals.plural(s.streak(), "день", "дня", "дней")).append(" подряд\n");
        }

        sb.append("\n📝 <b>По делам:</b>\n");
        for (TaskStat t : s.tasks()) {
            sb.append("• ").append(Html.escape(t.title())).append(" — ").append(t.daysDone())
                    .append(" из ").append(t.daysTotal()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String comparison(WeeklyStats s) {
        if (s.previousPercent() == null) {
            return "📈 С прошлой неделей сравнить нельзя: там нет данных.";
        }
        int diff = s.percent() - s.previousPercent();
        String change = diff > 0 ? "↑ " + diff + "%" : diff < 0 ? "↓ " + (-diff) + "%" : "без изменений";
        return "📈 С прошлой неделей: " + change + " (было " + s.previousPercent() + "%)";
    }

    private static String dayLabel(DayStat d) {
        LocalDate date = d.date();
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, RU) + " (" + d.percent() + "%)";
    }
}
