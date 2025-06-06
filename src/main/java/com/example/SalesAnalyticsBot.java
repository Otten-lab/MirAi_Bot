package com.example;

import com.google.api.services.sheets.v4.model.ValueRange;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class SalesAnalyticsBot extends TelegramLongPollingBot {
    private final String botToken;
    private final String botUsername;
    private final String spreadsheetId = System.getenv("SPREADSHEET_ID");
    private final GoogleSheetsService sheetsService;

    // Map для хранения состояний пользователей
    private final Map<Long, UserState> userStates = new HashMap<>();
    // Map для хранения данных пользователей
    private final Map<Long, UserData> userData = new HashMap<>();
    // Timer для первого follow-up (1 час)
    private final Map<Long, Timer> userTimers = new HashMap<>();
    // Timer для case follow-up (5 минут после первого follow-up)
    private final Map<Long, Timer> userCaseTimers = new HashMap<>();
    // Timer для case после презентации (5 минут после отправки презентации)
    private final Map<Long, Timer> userPresentationTimers = new HashMap<>();
    // Timer для follow-up после видео (5 минут)
    private final Map<Long, Timer> userVideoFollowUpTimers = new HashMap<>();
    // Timer для case после видео follow-up (5 минут)
    private final Map<Long, Timer> userVideoCaseTimers = new HashMap<>();

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
        String requestType; // консультация, расчет или аудит
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
                sendWelcomeMessage(chatId);
                // Запускаем таймер для первого follow-up через 1 час
                scheduleFollowUpMessage(chatId);
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

            // Отменяем все таймеры при любом действии пользователя
            cancelFollowUpMessage(chatId);
            cancelCaseTimer(chatId);
            cancelPresentationTimer(chatId);
            cancelVideoFollowUpTimer(chatId);
            cancelVideoCaseTimer(chatId);

            switch (callbackData) {
                case "get_video":
                    sendVideo(chatId);
                    break;
                case "get_presentation":
                    sendPresentation(chatId);
                    break;
                case "want_consultation":
                    startConsultationForm(chatId);
                    break;
                case "want_calculation":
                    startCalculationForm(chatId);
                    break;
                case "want_audit":
                    startAuditForm(chatId);
                    break;
                case "want_same":
                    startCaseForm(chatId, "Хочу так же (медкейс)");
                    break;
                case "want_calculation_case":
                    startCaseForm(chatId, "Расчет (медкейс)");
                    break;
                case "want_presentation":
                    sendPresentation(chatId);
                    break;
                case "video_want_consultation":
                    sendVideoConsultationForm(chatId);
                    break;
                case "video_want_calculation":
                    sendVideoCalculationForm(chatId);
                    break;
                case "video_want_presentation":
                    sendPresentation(chatId);
                    break;
                case "video_need_audit":
                    sendVideoCaseCalculationForm(chatId);
                    break;
                case "presentation_need_audit":
                    sendPresentationCaseCalculationForm(chatId);
                    break;
            }
        }
    }

    private void sendWelcomeMessage(Long chatId) {
        try {
            // Текст приветственного сообщения
            String text = "👋 Добро пожаловать в MirAl — это бот, который поможет вам увидеть, " +
                    "<b>что на самом деле происходит в вашем отделе продаж.</b>\n\n" +
                    "Здесь вы получите:\n" +
                    "✔️ Короткое видео от основателя проекта\n" +
                    "✔️ Презентацию с кейсами и расчётами\n" +
                    "✔️ Возможность оставить заявку на расчёт или бесплатную консультацию\n\n" +
                    "<b>Что такое MirAl?</b>\n" +
                    "Это Telegram-бот с ИИ-аналитикой звонков. Он за секунды показывает, кто из менеджеров продаёт, " +
                    "а кто просто разговаривает. Вы будете получать отчёты после каждого звонка, " +
                    "без прослушек и субъективных разборов.\n\n" +
                    "<b>Почему этому стоит доверять:</b>\n" +
                    "Валерий Елизаров — серийный предприниматель, который сам прошёл путь: " +
                    "от отдела с хаосом до выручки 500+ млн в год. Он создал MirAl не как проект \"в стол\", " +
                    "а чтобы решить собственную проблему — контролировать отдел без потерь времени и нервов.\n\n" +
                    "👇 Выберите, с чего начать:";

            // Создаем inline клавиатуру
            InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

            // Первая кнопка - Получить видео
            List<InlineKeyboardButton> row1 = new ArrayList<>();
            InlineKeyboardButton videoButton = new InlineKeyboardButton();
            videoButton.setText("Получить видео");
            videoButton.setCallbackData("get_video");
            row1.add(videoButton);

            // Вторая кнопка - Получить презентацию
            List<InlineKeyboardButton> row2 = new ArrayList<>();
            InlineKeyboardButton presentationButton = new InlineKeyboardButton();
            presentationButton.setText("Получить презентацию (PDF файл)");
            presentationButton.setCallbackData("get_presentation");
            row2.add(presentationButton);

            rowsInline.add(row1);
            rowsInline.add(row2);
            markupInline.setKeyboard(rowsInline);

            // Пытаемся отправить с изображением
            InputStream imageStream = getClass().getResourceAsStream("/img/welcome.jpg");
            if (imageStream != null) {
                // Отправляем фото с текстом (HTML-разметка)
                SendPhoto photo = new SendPhoto();
                photo.setChatId(chatId.toString());
                photo.setPhoto(new InputFile(imageStream, "welcome.jpg"));
                photo.setCaption(text);
                photo.setParseMode("HTML");
                photo.setReplyMarkup(markupInline);
                execute(photo);
            } else {
                // Если изображения нет, отправляем только текст
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText(text);
                message.setParseMode("HTML");
                message.setReplyMarkup(markupInline);
                execute(message);
            }
        } catch (Exception e) {
            e.printStackTrace();
            // В случае ошибки отправляем сообщение без изображения
            try {
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("Добро пожаловать! Используйте кнопки ниже для начала работы.");

                // Создаем inline клавиатуру
                InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

                List<InlineKeyboardButton> row1 = new ArrayList<>();
                InlineKeyboardButton videoButton = new InlineKeyboardButton();
                videoButton.setText("Получить видео");
                videoButton.setCallbackData("get_video");
                row1.add(videoButton);

                List<InlineKeyboardButton> row2 = new ArrayList<>();
                InlineKeyboardButton presentationButton = new InlineKeyboardButton();
                presentationButton.setText("Получить презентацию (PDF файл)");
                presentationButton.setCallbackData("get_presentation");
                row2.add(presentationButton);

                rowsInline.add(row1);
                rowsInline.add(row2);
                markupInline.setKeyboard(rowsInline);

                message.setReplyMarkup(markupInline);
                execute(message);
            } catch (TelegramApiException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void sendVideo(Long chatId) {
        try {
            // Текст к видео
            String text = "Отлично! Вот короткое видео — 5 минут вашего времени, но в нем самое важное:\n\n" +
                    "📌 с какой проблемой сталкиваются 90% отделов продаж\n" +
                    "📌 как ИИ решает это за 2 минуты вместо 2 часов\n" +
                    "📌 и почему выручка начинает расти уже в первый месяц\n\n" +
                    "🎥 <a href=\"https://drive.google.com/file/d/1Jdwu72HyOHrAM-KvTXRGWyzoPdkxXcZI/view?usp=drive_link\">Посмотреть видео</a>";

            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText(text);
            message.setParseMode("HTML");
            message.setDisableWebPagePreview(true);
            execute(message);

            // Запускаем таймер на 5 минут, чтобы через 5 минут отправить follow-up
            scheduleVideoFollowUp(chatId);

        } catch (Exception e) {
            e.printStackTrace();
            // Отправляем сообщение об ошибке пользователю
            try {
                SendMessage errorMessage = new SendMessage();
                errorMessage.setChatId(chatId.toString());
                errorMessage.setText("Произошла ошибка при отправке видео. Пожалуйста, попробуйте позже.");
                execute(errorMessage);
            } catch (TelegramApiException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void scheduleVideoFollowUp(Long chatId) {
        // Отменяем предыдущий, если он был
        cancelVideoFollowUpTimer(chatId);

        Timer timer = new Timer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                sendVideoFollowUpMessage(chatId);
                userVideoFollowUpTimers.remove(chatId);
            }
        };

        // Запускаем таймер на 5 минут (300000 мс)
        timer.schedule(task, 300000);
        userVideoFollowUpTimers.put(chatId, timer);
    }

    private void cancelVideoFollowUpTimer(Long chatId) {
        Timer timer = userVideoFollowUpTimers.get(chatId);
        if (timer != null) {
            timer.cancel();
            userVideoFollowUpTimers.remove(chatId);
        }
    }

    private void sendVideoFollowUpMessage(Long chatId) {
        try {
            String text = "Вы только что посмотрели, как можно получить полный контроль над звонками —\n" +
                    "<b>без прослушек</b>, без найма контролёров, без догадок.\n" +
                    "<b>MirAl — это не обещание, это цифры и результат</b>.\n" +
                    "Вы увидели, как он находит “слабые звенья”, экономит до 300 000 ₽ в месяц и даёт вам полный контроль над тем, что происходит в отделе продаж.\n\n" +
                    "📊 А теперь — выбирайте, какой следующий шаг вам ближе:";

            // Создаем inline-клавиатуру с тремя кнопками
            InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

            // Кнопка "Хочу консультацию"
            List<InlineKeyboardButton> row1 = new ArrayList<>();
            InlineKeyboardButton consultButton = new InlineKeyboardButton();
            consultButton.setText("Хочу консультацию");
            consultButton.setCallbackData("video_want_consultation");
            row1.add(consultButton);

            // Кнопка "Хочу расчёт под мой бизнес"
            List<InlineKeyboardButton> row2 = new ArrayList<>();
            InlineKeyboardButton calcButton = new InlineKeyboardButton();
            calcButton.setText("Хочу расчет под мой бизнес");
            calcButton.setCallbackData("video_want_calculation");
            row2.add(calcButton);

            // Кнопка "Хочу презентацию"
            List<InlineKeyboardButton> row3 = new ArrayList<>();
            InlineKeyboardButton presButton = new InlineKeyboardButton();
            presButton.setText("Хочу презентацию");
            presButton.setCallbackData("video_want_presentation");
            row3.add(presButton);

            rowsInline.add(row1);
            rowsInline.add(row2);
            rowsInline.add(row3);
            markupInline.setKeyboard(rowsInline);

            // Отправляем follow-up сообщение
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText(text);
            message.setParseMode("HTML");
            message.setReplyMarkup(markupInline);
            execute(message);

            // После отправки follow-up запускаем таймер на 5 минут для "кейса"
            scheduleVideoCaseTimer(chatId);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void scheduleVideoCaseTimer(Long chatId) {
        // Отменяем предыдущий, если он был
        cancelVideoCaseTimer(chatId);

        Timer timer = new Timer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                if (getUserState(chatId) == UserState.DEFAULT) {
                    sendVideoCaseMessage(chatId);
                }
                userVideoCaseTimers.remove(chatId);
            }
        };

        // Запускаем таймер на 5 минут (300000 мс)
        timer.schedule(task, 300000);
        userVideoCaseTimers.put(chatId, timer);
    }

    private void cancelVideoCaseTimer(Long chatId) {
        Timer timer = userVideoCaseTimers.get(chatId);
        if (timer != null) {
            timer.cancel();
            userVideoCaseTimers.remove(chatId);
        }
    }

    private void sendVideoCaseMessage(Long chatId) {
        try {
            String text =
                    "Владелец интернет-магазина мебели был уверен: если рекламный трафик идёт, заявки поступают, значит — дело в цене.\n" +
                    "Но выручка не росла, а затраты на аутсорс-команду продаж продолжали съедать бюджет.\n" +
                    "📉 6 менеджеров на удалёнке звонили по лидам, отчитывались в CRM, обещали результат.\n" +
                    "А по факту — клиенты “думали”, “уточняли у мужа”, “вернёмся позже”.\n\n" +
                    "После подключения MirAl картина стала резко ясной:\n" +
                    "— 4 менеджера <b>не задавали ни одного уточняющего вопроса</b>\n" +
                    "— в 60% звонков не озвучивались сроки доставки и гарантии\n" +
                    "— диалог сводился к «мы вам всё скинули на почту»\n\n" +
                    "🚫 Эти 4 сотрудника были отключены уже на второй неделе.\n" +
                    "Бизнес перестал платить за разговоры, и начал платить за результат.\n" +
                    "<b>Экономия — 240 000 ₽.</b>\n" +
                    "<b>Качество звонков выросло.</b>\n" +
                    "<b>Контроль — в Telegram, без прослушек.</b>\n\n" +
                    "📍 Хотите такую же ясность у себя? Оставляйте заявку на бесплатный аудит.";

            // 1) Отправляем изображение без длинного текста в caption (опционально можно добавить короткую подпись)
            InputStream caseImageStream = getClass().getResourceAsStream("/img/furniture_case.jpg");
            if (caseImageStream != null) {
                SendPhoto photo = new SendPhoto();
                photo.setChatId(chatId.toString());
                photo.setPhoto(new InputFile(caseImageStream, "furniture_case.jpg"));
                photo.setCaption("💸 «Мы сэкономили 240 000 ₽ за 2 недели работы с MirAl»\n");
                photo.setParseMode("HTML");
                execute(photo);
            }

            // 2) Формируем inline-клавиатуру с кнопкой "Нужен аудит"
            InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
            List<InlineKeyboardButton> row1 = new ArrayList<>();
            InlineKeyboardButton auditButton = new InlineKeyboardButton();
            auditButton.setText("Нужен аудит");
            auditButton.setCallbackData("video_need_audit");
            row1.add(auditButton);
            rowsInline.add(row1);
            markupInline.setKeyboard(rowsInline);

            // 3) Отправляем текст кейса вместе с кнопкой
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText(text);
            message.setParseMode("HTML");
            message.setReplyMarkup(markupInline);
            execute(message);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void sendPresentation(Long chatId) {
        try {
            // Текст с ссылкой на презентацию
            String text = "<b>Презентация отправлена — теперь у вас есть цифры и кейсы</b>\n\n" +
                    "📎 <a href=\"https://drive.google.com/file/d/1rIHkpo766NkbGVl2F_Qp-oC5Ln7_5FR3/view?usp=drive_link\">Скачать презентацию</a>\n\n" +
                    "Вы получили главное:\n" +
                    "— <b>Как работает MirAl</b>\n" +
                    "— <b>Реальные кейсы</b> с ростом выручки до +41%\n" +
                    "— <b>Сколько можно сэкономить</b> на контроле и неэффективных менеджерах\n" +
                    "— Примеры расчётов и формата сотрудничества\n\n" +
                    "📌 Это не “красивая упаковка” — это <b>конкретные сценарии</b>, " +
                    "которые уже сработали у предпринимателей, таких же как вы.\n\n" +
                    "Теперь самое важное — адаптировать это под вашу ситуацию. Выберите следующий шаг:";

            // Создаем inline-клавиатуру
            InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

            // Кнопка "Хочу консультацию"
            List<InlineKeyboardButton> row1 = new ArrayList<>();
            InlineKeyboardButton consultButton = new InlineKeyboardButton();
            consultButton.setText("Хочу консультацию");
            consultButton.setCallbackData("want_consultation");
            row1.add(consultButton);

            // Кнопка "Хочу расчет под свой бизнес"
            List<InlineKeyboardButton> row2 = new ArrayList<>();
            InlineKeyboardButton calcButton = new InlineKeyboardButton();
            calcButton.setText("Хочу расчет под свой бизнес");
            calcButton.setCallbackData("want_calculation");
            row2.add(calcButton);

            // Кнопка "Хочу видео"
            List<InlineKeyboardButton> row3 = new ArrayList<>();
            InlineKeyboardButton videoButton = new InlineKeyboardButton();
            videoButton.setText("Хочу видео");
            videoButton.setCallbackData("get_video");
            row3.add(videoButton);

            rowsInline.add(row1);
            rowsInline.add(row2);
            rowsInline.add(row3);
            markupInline.setKeyboard(rowsInline);

            // Отправляем сообщение с кнопками
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText(text);
            message.setParseMode("HTML");
            message.setDisableWebPagePreview(true);
            message.setReplyMarkup(markupInline);
            execute(message);

            // После отправки презентации запускаем таймер на 5 минут для case
            schedulePresentationTimer(chatId);

        } catch (Exception e) {
            e.printStackTrace();
            try {
                SendMessage errorMessage = new SendMessage();
                errorMessage.setChatId(chatId.toString());
                errorMessage.setText("Произошла ошибка при отправке презентации. " +
                        "Пожалуйста, свяжитесь с нами для получения материалов.");
                errorMessage.setParseMode("HTML");
                execute(errorMessage);
            } catch (TelegramApiException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void schedulePresentationTimer(Long chatId) {
        // Отменяем предыдущий, если он был
        cancelPresentationTimer(chatId);

        Timer timer = new Timer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                // Если пользователь не нажал ни одну из кнопок после презентации, отправляем новый кейс
                if (getUserState(chatId) == UserState.DEFAULT) {
                    sendPresentationCaseMessage(chatId);
                }
                userPresentationTimers.remove(chatId);
            }
        };

        // Запускаем таймер на 5 минут (300000 мс)
        timer.schedule(task, 300000);
        userPresentationTimers.put(chatId, timer);
    }

    private void cancelPresentationTimer(Long chatId) {
        Timer timer = userPresentationTimers.get(chatId);
        if (timer != null) {
            timer.cancel();
            userPresentationTimers.remove(chatId);
        }
    }

    private void sendPresentationCaseMessage(Long chatId) {
        try {
            // Основной текст кейса
            String longText =
                    "Владелец компании по продаже кровельных материалов, Андрей, думал, что у него сильная команда.\n" +
                            "Звонки шли, CRM заполнялась, менеджеры отчитывались — но выручка стояла.\n" +
                            "💬 «Мы лили трафик, тратили на рекламу, а в итоге слышали “будем думать” или “перезвоните позже”.\n" +
                            "Хотя продукт конкурентный, цена хорошая, логистика выстроена». Что пошло не так?\n" +
                            "После подключения MirAl стало очевидно:\n" +
                            "— 3 менеджера <b>теряли клиента прямо в первом касании</b>\n" +
                            "— скрипты игнорировались\n" +
                            "— один менеджер даже <b>называл цену, не узнав объём и регион доставки</b>\n\n" +
                            "📊 Через 2 недели:\n" +
                            "— 3 слабых сотрудника заменены\n" +
                            "— Новички с первых дней получают обратную связь от MirAl\n" +
                            "— Руководитель перестал тратить часы на прослушку\n\n" +
                            "<b>Результат через 45 дней: +1,3 млн ₽ к выручке.</b>\n" +
                            "🔒 MirAl даёт результат быстро — но мы <b>ограничиваем количество подключений в месяц</b>, " +
                            "чтобы сохранить качество внедрения.\n" +
                            "<b>Стоимость запуска от 100 000 ₽</b>, подписка — от 3 ₽ за минуту.\n" +
                            "<b>Оплата — только после результата.</b>\n\n" +
                            "📥 Презентацию вы уже видели.\n" +
                            "Готовы обсудить расчёт и запуск под вашу задачу?";

            // Отправляем короткую подпись вместе с фотографией (опционально)
            InputStream caseImageStream = getClass().getResourceAsStream("/img/roof_case.jpg");
            if (caseImageStream != null) {
                SendPhoto photo = new SendPhoto();
                photo.setChatId(chatId.toString());
                photo.setPhoto(new InputFile(caseImageStream, "roof_case.jpg"));
                // Делаем короткую подпись, не превышающую 1024 символа
                photo.setCaption("📈 +1,3 млн ₽ к выручке за 45 дней");
                photo.setParseMode("HTML");
                execute(photo);
            }

            // Формируем inline-клавиатуру для дальнейших действий
            InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

            List<InlineKeyboardButton> row1 = new ArrayList<>();
            InlineKeyboardButton sameButton = new InlineKeyboardButton();
            sameButton.setText("Хочу так же");
            sameButton.setCallbackData("presentation_need_audit");
            row1.add(sameButton);

            List<InlineKeyboardButton> row2 = new ArrayList<>();
            InlineKeyboardButton calcButton = new InlineKeyboardButton();
            calcButton.setText("Получить расчёт");
            calcButton.setCallbackData("presentation_need_audit");
            row2.add(calcButton);

            rowsInline.add(row1);
            rowsInline.add(row2);
            markupInline.setKeyboard(rowsInline);

            // Отправляем полный текст кейса вместе с кнопками
            SendMessage fullCaseMessage = new SendMessage();
            fullCaseMessage.setChatId(chatId.toString());
            fullCaseMessage.setText(longText);
            fullCaseMessage.setParseMode("HTML");
            fullCaseMessage.setReplyMarkup(markupInline);
            execute(fullCaseMessage);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startConsultationForm(Long chatId) {
        try {
            // Сначала отправляем дополнительное сообщение перед началом формы
            String preFormText = "Отличный выбор — чем быстрее разберёмся в вашей ситуации, тем быстрее вы начнёте экономить и зарабатывать больше.\n" +
                    "Наш менеджер <b>в ближайшее время свяжется с вами</b>, чтобы:\n" +
                    " — уточнить, <b>что именно у вас происходит сейчас</b> в отделе продаж\n" +
                    " — согласовать <b>удобное время для консультации</b>\n" +
                    " — и подготовить конкретные предложения под вашу задачу";

            SendMessage preFormMessage = new SendMessage();
            preFormMessage.setChatId(chatId.toString());
            preFormMessage.setText(preFormText);
            preFormMessage.setParseMode("HTML");
            execute(preFormMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        // Создаем объект для хранения данных
        UserData data = new UserData();
        data.requestType = "Консультация";
        setUserData(chatId, data);

        // Устанавливаем состояние и запрашиваем имя
        setUserState(chatId, UserState.AWAITING_NAME);
        askForName(chatId);
    }

    private void sendVideoConsultationForm(Long chatId) {
        // Повторяем логику формы консультации после видео
        startConsultationForm(chatId);
    }

    private void startCalculationForm(Long chatId) {
        try {
            // Сначала отправляем дополнительное сообщение перед началом формы
            String preFormText = "Мы видим вашу боль — и понимаем, как важно <b>точно знать</b>, во сколько вам обойдётся внедрение MirAl и какие деньги вы сможете сэкономить уже в первый месяц.\n" +
                    "<b>Наш менеджер скоро свяжется с вами</b>, чтобы:\n" +
                    " — обсудить вашу текущую ситуацию\n" +
                    " — согласовать удобное время для расчёта\n" +
                    " — задать ключевые вопросы: сколько звонков, сколько менеджеров, какая CRM, какие боли вы хотите закрыть\n" +
                    "📞 <b>Пожалуйста, обязательно возьмите трубку</b> — от этого разговора зависит, как быстро вы получите контроль, цифры и результат.";

            SendMessage preFormMessage = new SendMessage();
            preFormMessage.setChatId(chatId.toString());
            preFormMessage.setText(preFormText);
            preFormMessage.setParseMode("HTML");
            execute(preFormMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        // Создаем объект для хранения данных
        UserData data = new UserData();
        data.requestType = "Расчет под бизнес";
        setUserData(chatId, data);

        // Устанавливаем состояние и запрашиваем имя
        setUserState(chatId, UserState.AWAITING_NAME);
        askForName(chatId);
    }

    private void sendVideoCalculationForm(Long chatId) {
        // Повторяем логику формы расчёта после видео
        startCalculationForm(chatId);
    }

    private void sendVideoCaseCalculationForm(Long chatId) {
        // Логика формы расчёта после видео-кейса
        startCalculationForm(chatId);
    }

    private void sendPresentationCaseCalculationForm(Long chatId) {
        // Логика формы расчёта после презентационного кейса
        startCalculationForm(chatId);
    }

    private void askForName(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Отлично! Давайте познакомимся.\n\nПожалуйста, введите ваше имя:");
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void askForContact(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Теперь введите ваш телефон или Telegram для связи:");
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void askForComment(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Расскажите коротко о вашем бизнесе и какие задачи хотите решить:");
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void saveToGoogleSheets(Long chatId, UserData data) {
        try {
            // Подготовка данных для Google Sheets
            List<List<Object>> values = Arrays.asList(
                    Arrays.asList(
                            data.requestType,
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
        message.setText("✅ Отлично! Ваша заявка принята.\n\n" +
                "Наш специалист свяжется с вами в течение 24 часов и поможет настроить MirAl под ваши задачи.\n\n" +
                "А пока вы можете изучить презентацию или посмотреть видео, если ещё не успели это сделать.");
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
        message.setText("Я не понимаю вашего сообщения. Пожалуйста, используйте команду /start " +
                "или выберите одну из предложенных опций.");
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Методы для первого follow-up (1 час)
    private void scheduleFollowUpMessage(Long chatId) {
        // Отменяем предыдущий таймер, если он был
        cancelFollowUpMessage(chatId);

        Timer timer = new Timer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                sendFollowUpMessage(chatId);
                userTimers.remove(chatId);
            }
        };

        // Запускаем таймер на 1 час (3600000 мс)
        timer.schedule(task, 3600000);
        userTimers.put(chatId, timer);
    }

    private void cancelFollowUpMessage(Long chatId) {
        Timer timer = userTimers.get(chatId);
        if (timer != null) {
            timer.cancel();
            userTimers.remove(chatId);
        }
    }

    private void sendFollowUpMessage(Long chatId) {
        try {
            String text = "Вы тратите деньги на рекламу, платите зарплаты менеджерам, а клиенты всё равно \"не доходят\" до сделки?\n\n" +
                    "❌ Звонки есть — продаж нет.\n" +
                    "❌ Скрипты написаны — но не работают.\n" +
                    "❌ Руководитель слушает 5 звонков из 500 — и делает выводы \"на ощупь\".\n\n" +
                    "Всё это не про неудачу. Это про <b>отсутствие контроля</b>.\n\n" +
                    "👉 MirAl — ИИ-бот, который уже на третий день покажет, где теряются ваши деньги:\n" +
                    "— Кто из менеджеров сливает заявки\n" +
                    "— Где ломается воронка\n" +
                    "— Кто работает на результат, а кто просто \"отрабатывает смену\"\n\n" +
                    "Хотите увидеть это на примере <b>ваших звонков</b>?\n\n" +
                    "📩 Оставьте заявку на аудит — и получите чёткий разбор, без обязательств и продаж \"в лоб\".\n\n" +
                    "<b>Мест немного — работа с каждым клиентом индивидуальна.</b>";

            // Создаем inline-клавиатуру с одной кнопкой
            InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

            List<InlineKeyboardButton> row1 = new ArrayList<>();
            InlineKeyboardButton auditButton = new InlineKeyboardButton();
            auditButton.setText("Хочу аудит моих звонков");
            auditButton.setCallbackData("want_audit");
            row1.add(auditButton);

            rowsInline.add(row1);
            markupInline.setKeyboard(rowsInline);

            // Отправляем сообщение
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText(text);
            message.setParseMode("HTML");
            message.setReplyMarkup(markupInline);
            execute(message);

            // После отправки первого follow-up запускаем таймер на 5 минут для case
            scheduleCaseTimer(chatId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Методы для case follow-up (5 минут после первого follow-up)
    private void scheduleCaseTimer(Long chatId) {
        // Отменяем предыдущий, если он был
        cancelCaseTimer(chatId);

        Timer timer = new Timer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                // Если пользователь не нажал кнопку (состояние всё ещё DEFAULT), отправляем кейс
                if (getUserState(chatId) == UserState.DEFAULT) {
                    sendCaseMessage(chatId);
                }
                userCaseTimers.remove(chatId);
            }
        };

        // Запускаем таймер на 5 минут (300000 мс)
        timer.schedule(task, 300000);
        userCaseTimers.put(chatId, timer);
    }

    private void cancelCaseTimer(Long chatId) {
        Timer timer = userCaseTimers.get(chatId);
        if (timer != null) {
            timer.cancel();
            userCaseTimers.remove(chatId);
        }
    }

    private void sendCaseMessage(Long chatId) {
        try {
            String text = "<b>Кейс:</b> “+18% повторных визитов в медицинском центре”";

            // Создаем inline-клавиатуру
            InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

            // Кнопка "Хочу так же"
            List<InlineKeyboardButton> row1 = new ArrayList<>();
            InlineKeyboardButton sameButton = new InlineKeyboardButton();
            sameButton.setText("Хочу так же");
            sameButton.setCallbackData("want_same");
            row1.add(sameButton);

            // Кнопка "Получить расчет"
            List<InlineKeyboardButton> row2 = new ArrayList<>();
            InlineKeyboardButton calcButton = new InlineKeyboardButton();
            calcButton.setText("Получить расчет");
            calcButton.setCallbackData("want_calculation_case");
            row2.add(calcButton);

            rowsInline.add(row1);
            rowsInline.add(row2);
            markupInline.setKeyboard(rowsInline);

            // Пытаемся отправить с изображением кейса
            InputStream caseImageStream = getClass().getResourceAsStream("/img/medical_case.jpg");
            if (caseImageStream != null) {
                SendPhoto photo = new SendPhoto();
                photo.setChatId(chatId.toString());
                photo.setPhoto(new InputFile(caseImageStream, "medical_case.jpg"));
                photo.setCaption(text);
                photo.setParseMode("HTML");
                photo.setReplyMarkup(markupInline);
                execute(photo);
            } else {
                // Если изображения нет, отправляем только текст
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText(text);
                message.setParseMode("HTML");
                message.setReplyMarkup(markupInline);
                execute(message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startAuditForm(Long chatId) {
        try {
            // Отправляем подтверждающее сообщение
            String confirmText = "✅ Всё отлично! Мы получили ваш запрос на аудит звонков.\n\n" +
                    "Наш специалист в ближайшее время свяжется с вами, чтобы:\n" +
                    "— уточнить технические детали подключения\n" +
                    "— согласовать удобное время\n" +
                    "— объяснить, как именно пройдёт аудит и что вы получите на выходе\n\n" +
                    "📌 Пожалуйста, будьте на связи — от этого зависит, насколько быстро вы увидите " +
                    "реальные точки роста в вашем отделе продаж.\n\n" +
                    "До скорого!";

            SendMessage confirmMessage = new SendMessage();
            confirmMessage.setChatId(chatId.toString());
            confirmMessage.setText(confirmText);
            confirmMessage.setParseMode("HTML");
            execute(confirmMessage);

            // Создаем объект для хранения данных
            UserData data = new UserData();
            data.requestType = "Аудит звонков";
            setUserData(chatId, data);

            // Устанавливаем состояние и запрашиваем имя
            setUserState(chatId, UserState.AWAITING_NAME);
            askForName(chatId);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startCaseForm(Long chatId, String requestType) {
        // Если пользователь уже в процессе заполнения формы, не начинаем новую
        if (getUserState(chatId) != UserState.DEFAULT) {
            return;
        }

        // Создаем объект для хранения данных
        UserData data = new UserData();
        data.requestType = requestType;
        setUserData(chatId, data);

        // Устанавливаем состояние и запрашиваем имя
        setUserState(chatId, UserState.AWAITING_NAME);
        askForName(chatId);
    }
}
