package com.example;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        // Чтение переменных окружения
        String botToken = System.getenv("BOT_TOKEN");
        String botUsername = System.getenv("BOT_USERNAME");

        // Проверка на пустые значения
        if (botToken == null || botToken.isEmpty()) {
            System.err.println("❌ Ошибка: BOT_TOKEN не задан!");
            System.exit(1);
        }
        if (botUsername == null || botUsername.isEmpty()) {
            System.err.println("❌ Ошибка: BOT_USERNAME не задан!");
            System.exit(1);
        }

        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new SalesAnalyticsBot(botToken, botUsername));
            System.out.println("🤖 Бот запущен!");
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
