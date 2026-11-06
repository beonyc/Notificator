package com.example.notificator.bot;

/**
 * Реакция на нажатие кнопки.
 *
 * @param notice короткое всплывающее уведомление пользователю (answerCallbackQuery)
 * @param view   новое содержимое сообщения с кнопкой (editMessageText); null — не менять сообщение
 */
public record CallbackResponse(String notice, BotReply view) {
}
