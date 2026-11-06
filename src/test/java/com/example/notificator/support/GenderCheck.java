package com.example.notificator.support;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.regex.Pattern;

/**
 * Проверка, что в тексте нет форм, выдающих мужской род. Бот не знает пола пользователя, поэтому обращаться
 * к нему нужно нейтрально: вместо «ты не успел» — «нужно поправить», вместо «ты зарегистрирован» — «регистрация пройдена».
 *
 * Слова самого бота о себе («Не понял») сюда не относятся: это его собственная речь, а не обращение к пользователю.
 */
public final class GenderCheck {

    /** Мужские формы прошедшего времени и краткие прилагательные, которыми легко обратиться к пользователю. */
    public static final Pattern MASCULINE = Pattern.compile(
            "(?iu)(?<![\\p{L}])(сделал|успел|выполнил|забыл|пропустил|передумал|зарегистрирован|проленился|красава"
                    + "|готов|рад|уверен|молодец)(?![\\p{L}])");

    private GenderCheck() {
    }

    /** Падает, если в тексте есть форма мужского рода; where — подпись для сообщения об ошибке. */
    public static void assertNeutral(String where, String text) {
        var matcher = MASCULINE.matcher(text);
        boolean found = matcher.find();
        assertFalse(found, where + ": найдена форма мужского рода «" + (found ? matcher.group() : "")
                + "» в тексте: " + text);
    }
}
