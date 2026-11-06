package com.example.notificator.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PluralsTest {

    @Test
    void picksCorrectRussianForm() {
        assertEquals("дело", Plurals.plural(1, "дело", "дела", "дел"));
        assertEquals("дела", Plurals.plural(3, "дело", "дела", "дел"));
        assertEquals("дел", Plurals.plural(5, "дело", "дела", "дел"));
        assertEquals("дел", Plurals.plural(0, "дело", "дела", "дел"));
        assertEquals("дел", Plurals.plural(11, "дело", "дела", "дел"));
        assertEquals("дел", Plurals.plural(14, "дело", "дела", "дел"));
        assertEquals("дело", Plurals.plural(21, "дело", "дела", "дел"));
        assertEquals("дела", Plurals.plural(22, "дело", "дела", "дел"));
        assertEquals("дел", Plurals.plural(112, "дело", "дела", "дел"));
    }
}
