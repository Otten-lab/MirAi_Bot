package com.example;

import com.google.api.services.sheets.v4.model.ValueRange;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.HashMap;
import java.util.Map;

public class SalesAnalyticsBot extends TelegramLongPollingBot {
    private final String botToken;
    private final String botUsername;
    private final String spreadsheetId = "1cLE2s2smwRBDa8ozngi6dcnZpGP-XiylubS5r4ygbAc";

    private final GoogleSheetsService sheetsService;

    // Map для хранения состояний пользователей
    private final Map<Long, UserState> userStates = new HashMap<>();

    // Map для хранения данных пользователей
    private final Map<Long, UserData> userData = new HashMap<>();

    // Состояния пользователя
    private enum UserState {
        DEFAULT,
        AWAITING_NAME,
        AWAITING_CONTACT,
        AWAITING_COMMENT
    }

    // Данные пользователя для формы
    private static class UserData {
        String name;
        String contact;
        String comment;
    }

    public SalesAnalyticsBot(String botToken, String botUsername) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.sheetsService = new GoogleSheetsService();
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        // Обработка обычных сообщений
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            Long chatId = message.getChatId();
            String text = message.getText();

            // Обработка команд
            if (text.equals("/start")) {
                sendBlock1(chatId);
            } else {
                // Обработка состояний формы
                UserState state = getUserState(chatId);
                switch (state) {
                    case AWAITING_NAME:
                        UserData data = getUserData(chatId);
                        data.name = text;
                        setUserState(chatId, UserState.AWAITING_CONTACT);
                        askForContact(chatId);
                        break;
                    case AWAITING_CONTACT:
                        data = getUserData(chatId);
                        data.contact = text;
                        setUserState(chatId, UserState.AWAITING_COMMENT);
                        askForComment(chatId);
                        break;
                    case AWAITING_COMMENT:
                        data = getUserData(chatId);
                        data.comment = text;
                        saveToGoogleSheets(chatId, data);
                        sendConfirmation(chatId);
                        clearUserData(chatId);
                        break;
                    default:
                        sendDefaultResponse(chatId);
                }
            }
        }

        // Обработка нажатий на inline кнопки
        else if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            Long chatId = update.getCallbackQuery().getMessage().getChatId();

            if (callbackData.equals("show_example")) {
                sendBlock2(chatId);
            } else if (callbackData.equals("analyze_case")) {
                startCaseAnalysis(chatId);
            }
        }
    }

    private void sendBlock1(Long chatId) {
        try {
            // Текст первого блока
            String text =
                    "+27% к продажам за 30 дней\n" +
                            "после внедрения ИИ-аналитики звонков\n\n" +
                            "\"Ритейл, Москва. Отдел из 6 менеджеров\"";

            // Создаем inline клавиатуру
            InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

            List<InlineKeyboardButton> row1 = new ArrayList<>();
            InlineKeyboardButton button1 = new InlineKeyboardButton();
            button1.setText("Показать еще пример");
            button1.setCallbackData("show_example");
            row1.add(button1);

            List<InlineKeyboardButton> row2 = new ArrayList<>();
            InlineKeyboardButton button2 = new InlineKeyboardButton();
            button2.setText("Разобрать мой кейс");
            button2.setCallbackData("analyze_case");
            row2.add(button2);

            rowsInline.add(row1);
            rowsInline.add(row2);

            markupInline.setKeyboard(rowsInline);

            // Отправляем изображение
            InputStream imageStream = getClass().getResourceAsStream("/img/block1.jpg");
            SendPhoto photo = new SendPhoto();
            photo.setChatId(chatId.toString());
            photo.setPhoto(new InputFile(imageStream, "block1.jpg"));
            photo.setCaption(text);
            photo.setReplyMarkup(markupInline);

            execute(photo);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendBlock2(Long chatId) {
        try {
            // Текст второго блока
            String text = "🚘 За 2 недели выручка отдела продаж автосервиса выросла на 31%.\n" +
                    "Мы подключили аналитику звонков, нашли слабые места:\n" +
                    "где терялись клиенты, где скрипты буксовали.\n\n" +
                    "Клиент быстро адаптировал подход — и результат не заставил себя ждать.\n\n" +
                    "Хочешь — разберём твою ситуацию и покажем, где можно расти?";

            // Inline клавиатура с одной кнопкой
            InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

            List<InlineKeyboardButton> row1 = new ArrayList<>();
            InlineKeyboardButton button1 = new InlineKeyboardButton();
            button1.setText("Разобрать мой кейс");
            button1.setCallbackData("analyze_case");
            row1.add(button1);

            rowsInline.add(row1);
            markupInline.setKeyboard(rowsInline);

            // Отправляем изображение
            InputStream imageStream = getClass().getResourceAsStream("/img/photo_2025-04-18_14-18-36.jpg");
            SendPhoto photo = new SendPhoto();
            photo.setChatId(chatId.toString());
            photo.setPhoto(new InputFile(imageStream, "photo_2025-04-18_14-18-36.jpg"));
            photo.setCaption(text);
            photo.setReplyMarkup(markupInline);

            execute(photo);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startCaseAnalysis(Long chatId) {
        try {
            // Текст формы
            String text = "Оставьте свой контакт и пару слов — чем вы занимаетесь, с какими трудностями сталкиваетесь, — и мы покажем, как улучшить ваши звонки.\n\n" +
                    "📩 Форма:\n\n" +
                    "Имя\n" +
                    "Телефон / Telegram\n" +
                    "Комментарий: \"Чем занимаетесь и что болит\"";

            // Отправляем изображение без клавиатуры
            InputStream imageStream = getClass().getResourceAsStream("/img/44351e59-c950-4c95-a3ad-2a3a327afea1.png");
            SendPhoto photo = new SendPhoto();
            photo.setChatId(chatId.toString());
            photo.setPhoto(new InputFile(imageStream, "44351e59-c950-4c95-a3ad-2a3a327afea1.png"));
            photo.setCaption(text);

            execute(photo);

            // Устанавливаем состояние пользователя и создаем объект для хранения данных
            setUserState(chatId, UserState.AWAITING_NAME);
            setUserData(chatId, new UserData());

            // Просим ввести имя
            askForName(chatId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void askForName(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Пожалуйста, введите ваше имя:");
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void askForContact(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Теперь введите ваш телефон или Telegram:");
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void askForComment(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Опишите, чем вы занимаетесь и с какими трудностями сталкиваетесь:");
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void saveToGoogleSheets(Long chatId, UserData data) {
        try {
            // Подготовка данных для Google Sheets - каждое значение в отдельный столбец
            List<List<Object>> values = Arrays.asList(
                    Arrays.asList(
                            data.name,
                            data.contact,
                            data.comment,
                            "Chat ID: " + chatId,
                            new java.util.Date().toString()
                    )
            );

            ValueRange body = new ValueRange()
                    .setValues(values);
            // Сохранение в Google Sheets
            sheetsService.getSheetsService().spreadsheets().values()
                    .append(spreadsheetId, "A1", body)
                    .setValueInputOption("RAW")
                    .execute();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendConfirmation(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Мы приняли вашу заявку! Скоро с вами свяжется наш специалист.");
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Методы для работы с состояниями пользователя
    private UserState getUserState(Long chatId) {
        return userStates.getOrDefault(chatId, UserState.DEFAULT);
    }

    private void setUserState(Long chatId, UserState state) {
        userStates.put(chatId, state);
    }

    private UserData getUserData(Long chatId) {
        return userData.getOrDefault(chatId, new UserData());
    }

    private void setUserData(Long chatId, UserData data) {
        userData.put(chatId, data);
    }

    private void clearUserData(Long chatId) {
        userData.remove(chatId);
        userStates.put(chatId, UserState.DEFAULT);
    }

    private void sendDefaultResponse(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Я не понимаю вашего сообщения. Пожалуйста, используйте команду /start или нажмите на кнопку в предыдущем сообщении.");
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}