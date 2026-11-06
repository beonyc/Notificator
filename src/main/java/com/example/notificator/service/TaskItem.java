package com.example.notificator.service;

/**
 * Активное дело пользователя (шаблон, а не отметка за день): id и название.
 * Используется в списке для команды /remove.
 */
public record TaskItem(long taskId, String title) {
}
