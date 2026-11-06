package com.example.notificator.service;

/**
 * Строка списка «дела на сегодня»: id отметки (нужен для кнопки), название и статус.
 * Это record — неизменяемый объект-данные (конструктор, геттеры, equals и toString генерирует Java).
 * Сервис возвращает его вместо сущности, потому что сущности лениво подгружают связи
 * и их нельзя безопасно использовать вне транзакции.
 */
public record TodayItem(long entryId, String title, boolean done) {
}
