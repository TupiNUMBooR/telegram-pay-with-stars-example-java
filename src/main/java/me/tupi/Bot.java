package me.tupi;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.telegram.abilitybots.api.bot.AbilityBot;
import org.telegram.abilitybots.api.objects.Ability;
import org.telegram.abilitybots.api.objects.MessageContext;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.telegram.abilitybots.api.objects.Locality.ALL;
import static org.telegram.abilitybots.api.objects.Privacy.PUBLIC;

@Slf4j
@Component
public class Bot extends AbilityBot {

    // Initial free quota and pack config
    private static final int START_LETTERS = 10;  // initial free letters
    private static final int PACK_LETTERS = 20;  // +20 letters per 1 star
    private static final int WARN_THRESHOLD = 5;   // warn when below this

    // In-memory state: remaining letters per chat
    private final Map<Long, Integer> lettersLeft = new ConcurrentHashMap<>();

    @Value("${telegram.bot.debug_chat}")
    Long debugChatId;
    @Value("${telegram.bot.token}")
    private String token;

    private RestClient restClient;

    public Bot(
        @Value("${telegram.bot.username}") String username,
        @Value("${telegram.bot.token}") String token
    ) {
        super(token, username);
    }

    @Override
    public long creatorId() {
        // Not used here; return 0 or your own Telegram user id if you want admin-only abilities
        return 0L;
    }

    @PostConstruct
    @SneakyThrows
    private void init() {
        restClient = RestClient.builder()
            .baseUrl("https://api.telegram.org/bot" + token)
            .build();

        new TelegramBotsApi(DefaultBotSession.class)
            .registerBot(this);
        sendDebugText("Bot start");
    }

    @PreDestroy
    private void destroy() {
        sendDebugText("Bot shutdown");
    }

    // /start command — initializes quota and explains rules
    public Ability start() {
        return Ability
            .builder()
            .name("start")
            .info("Initialize the bot")
            .locality(ALL)
            .privacy(PUBLIC)
            .action(this::onStart)
            .build();
    }

    // /buy command — sends invoice for 1 star
    private void onStart(MessageContext ctx) {
        long chatId = ctx.chatId();
        lettersLeft.putIfAbsent(chatId, START_LETTERS);
        sendText(chatId,
            "Hello! You have 20 letters for free. Send me any text — I echo it.\n" +
            "When quota ends, I’ll offer a 1⭐ invoice (= +20 letters). Command: /buy");
    }

    public Ability buy() {
        return Ability
            .builder()
            .name("buy")
            .info("Buy +20 letters (1⭐)")
            .locality(ALL)
            .privacy(PUBLIC)
            .action(this::onBuy)
            .build();
    }

    private void onBuy(MessageContext ctx) {
        sendStarsInvoice(ctx.chatId());
    }

    /**
     * Override to catch non-command updates: payments + plain text.
     */
    @Override
    @SneakyThrows
    public void onUpdateReceived(Update update) {
        boolean hasMessage = update.hasMessage();
        Message message = update.getMessage();

        // Handle pre-checkout queries (MUST reply within ~10s)
        if (update.hasPreCheckoutQuery()) {
            answerPreCheckout(update.getPreCheckoutQuery());
            return;
        }

        // Handle successful payment
        if (hasMessage && message.getSuccessfulPayment() != null) {
            onSuccessfulPayment(message);
            return;
        }

        // let Abilities handle slash-commands first
        if (hasMessage && message.hasText() && message.getText().startsWith("/")) {
            super.onUpdateReceived(update);
            return;
        }

        // Echo logic for plain text messages with quota
        if (hasMessage && message.hasText()) {
            echo(message.getChatId(), message.getText());
            return;
        }

        // Fallback to Abilities for anything else (stickers, etc.)
        super.onUpdateReceived(update);
    }

    private void echo(Long chatId, String text) throws TelegramApiException {
        lettersLeft.putIfAbsent(chatId, START_LETTERS);
        int left = lettersLeft.get(chatId);
        int need = text.length();

        if (left <= 0) {
            sendText(chatId, "❌ No access (0 letters)");
            sendStarsInvoice(chatId);
            return;
        }
        if (need > left) {
            sendText(chatId, "❌ Not enough letters: need " + need + ", left " + left + "");
            sendStarsInvoice(chatId);
            return;
        }

        // Consume quota and echo back
        lettersLeft.put(chatId, left - need);
        sendText(chatId, text);

        int now = lettersLeft.get(chatId);
        if (now < WARN_THRESHOLD) {
            sendText(chatId, "⚠️ Only " + now + " letters left");
            sendStarsInvoice(chatId);
        }
    }

    /**
     * Send invoice for 1 star (XTR). Amount is in "cents": 1⭐ == 20.
     */
//    @SneakyThrows
//    private void sendStarsInvoice(Long chatId) {
//        List<LabeledPrice> prices = List.of(new LabeledPrice("Letter pack +20", 20)); // 20 = 1 star
//
//        SendInvoice inv = new SendInvoice();
//        inv.setChatId(chatId.toString());
//        inv.setTitle("Top up letters");
//        inv.setDescription("1⭐ = +20 letters. Instant, inside Telegram.");
//        inv.setPayload("letters_pack_20");
//        inv.setStartParameter("buy_letters");
//        inv.setCurrency("XTR");     // Telegram Stars currency for digital goods
//        inv.setPrices(prices);
//
//        execute(inv);
//    }

    /**
     * Send invoice for 1⭐ (XTR)
     */
    public void sendStarsInvoice(long chatId) {
        Map<String, Object> payload = Map.of(
            "chat_id", chatId,
            "title", "Top up letters",
            "description", "1⭐ = +20 letters",
            "payload", "letters_pack_20",
            "start_parameter", "buy_letters",
            "currency", "XTR",
            "prices", List.of(
                Map.of("label", "Letter pack +20", "amount", 1)
            )
        );

        String response = restClient.post()
            .uri("/sendInvoice")
            .body(payload)
            .retrieve()
            .body(String.class);

        log.info(response);
    }

    /**
     * Must answer pre_checkout_query within 10s.
     * ok=true  → allow Telegram to proceed with the payment (normal case).
     * ok=false → reject the payment (e.g. wrong payload, product unavailable, user not eligible).
     */
    private void answerPreCheckout(PreCheckoutQuery q) throws TelegramApiException {
        AnswerPreCheckoutQuery ans = AnswerPreCheckoutQuery.builder()
            .preCheckoutQueryId(q.getId())
            .ok(true) // usually true; set false + errorMessage to block
            .build();

        execute(ans);
    }

    /**
     * After payment, add +20 letters and notify the user.
     */
    private void onSuccessfulPayment(Message msg) throws TelegramApiException {
        Long chatId = msg.getChatId();
        lettersLeft.merge(chatId, PACK_LETTERS, Integer::sum);
        int now = lettersLeft.get(chatId);
        sendText(chatId, "✅ Payment received: +20 letters added. Current quota: " + now + ".");
    }

    private void sendDebugText(String text) {
        log.info(text);
        if (debugChatId != null) {
            sendText(debugChatId, text);
        }
    }

    @SneakyThrows
    void sendText(Long chatId, String text) {
        SendMessage message = SendMessage
            .builder()
            .chatId(chatId.toString())
            .text(text)
            .build();
        execute(message);
    }
}
