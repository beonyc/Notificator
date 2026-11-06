package com.example.notificator.service;

/** Пользователь написал команду, не выполнив /start (его нет в БД). */
public class UserNotRegisteredException extends RuntimeException {

    public UserNotRegisteredException(long chatId) {
        super("Пользователь с chatId=" + chatId + " не зарегистрирован");
    }
}
