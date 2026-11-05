package com.paymentsapp.providers;

import com.paymentsapp.platform.redis.IdempotentResults;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Заглушка шлюзов поставщиков: операторы связи, транспорт, игры, ГИС ГМП (квитирование) и др.
// Делает вид, что поставщик зачислил деньги или выпустил ваучер.
// Отказывает, если номер счёта / карты / аккаунта (от 6 цифр) оканчивается на 0000 или логин — «banned».
// Идемпотентно: повтор с тем же paymentId — тот же ответ; итоги — в Redis с TTL.
// Поставщики из providers.async.providers (подписка через партнёра) проводят оплату не сразу: отвечают PENDING,
// окончательный итог отдают на запрос статуса через providers.async.delay.
@RestController
@RequestMapping("/gateway")
public class GatewayController {

    // Правила отказа для ручной проверки
    private static final String NOT_FOUND_SUFFIX = "0000";
    private static final int ACCOUNT_MIN_DIGITS = 6;
    private static final String NOMINAL_FIELD = "nominal";
    private static final String BANNED_LOGIN = "banned";
    private static final String BANNED = "Аккаунт заблокирован у поставщика";
    private static final String CREDITED = "Зачислено";
    // Данные плательщика, оканчивающиеся на 0, — счетов у поставщика нет
    private static final String NO_INVOICES_SUFFIX = "0";
    private static final String KEY_SEPARATOR = "|";
    private static final String HAS_DIGIT = ".*\\d.*";
    private static final int ID_RADIX = Character.MAX_RADIX;
    // Ваучер XXXX-XXXX-XXXX-XXXX без похожих символов (0/O, 1/I)
    private static final String VOUCHER_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int VOUCHER_LENGTH = 16;
    private static final int VOUCHER_GROUP = 4;
    private static final char VOUCHER_SEPARATOR = '-';

    // Счета у поставщика: в счёте до MAX_INVOICES услуг, срок оплаты — DUE_DAY число месяца, суммы — кратно AMOUNT_STEP
    private static final int MAX_INVOICES = 3;
    private static final int DUE_DAY = 25;
    private static final int AMOUNT_STEP = 10;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.of("ru"));
    private static final String INTERNET = "INTERNET";
    private static final String UTILITIES = "UTILITIES";
    private static final Map<String, List<Service>> SERVICES = Map.of(
            INTERNET, List.of(new Service("Абонентская плата", "Интернет и ТВ по тарифу", 550, 1490)),
            UTILITIES, List.of(
                    new Service("Жилищно-коммунальные услуги", "Содержание жилья, отопление, вода", 4200, 9800),
                    new Service("Электроэнергия", "По показаниям счётчика", 900, 2600),
                    new Service("Взнос на капитальный ремонт", "Фонд капремонта", 1100, 2400),
                    new Service("Вывоз ТКО", "Региональный оператор", 300, 700)));
    // Образование и прочее: школа, кружки, подготовка
    private static final List<Service> EDUCATION = List.of(
            new Service("Питание в школе", "Завтраки и обеды", 2400, 4200),
            new Service("Группа продлённого дня", "Присмотр и уход после уроков", 2000, 3500),
            new Service("Кружок «Робототехника»", "Дополнительное образование", 3000, 6000),
            new Service("Секция плавания", "Бассейн, 8 занятий", 2500, 4500),
            new Service("Подготовка к ЕГЭ", "Математика, 8 занятий", 4000, 8000));

    private static final String PENDING_NOTE = "Партнёр оформляет подписку";

    private final IdempotentResults<Result> results;
    private final IdempotentResults<Pending> pending;
    private final ProvidersProperties props;
    private final Clock clock;

    public GatewayController(IdempotentResults<Result> gatewayResults, IdempotentResults<Pending> pendingResults,
                             ProvidersProperties props, Clock clock) {
        this.results = gatewayResults;
        this.pending = pendingResults;
        this.props = props;
        this.clock = clock;
    }

    @PostMapping("/payments")
    public Result pay(@Valid @RequestBody PayRequest req) throws InterruptedException {
        Result cached = results.find(req.paymentId()).orElse(null);
        if (cached != null) {
            return cached;
        }
        // Поставщик думает
        Thread.sleep(ThreadLocalRandom.current().nextLong(props.thinkMin().toMillis(), props.thinkMax().toMillis()));
        Integration integration = Integration.of(req.integration());
        Map<String, String> fields = req.fields() == null ? Map.of() : req.fields();
        String reject = rejectReason(integration, fields);
        Result result = reject != null
                ? new Result(req.paymentId(), Status.REJECTED, null, null, reject)
                : new Result(req.paymentId(), Status.ACCEPTED, integration.prefix() + "-"
                        + Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()), ID_RADIX).toUpperCase(),
                        req.issueVoucher() ? voucher() : null, CREDITED);
        if (props.async().providers().contains(req.providerCode())) {
            // Итог решён, но поставщик «проведёт» его позже
            Result accepted = new Result(req.paymentId(), Status.PENDING, null, null, PENDING_NOTE);
            Result first = results.putIfAbsent(req.paymentId(), accepted);
            if (first == accepted) {
                pending.putIfAbsent(req.paymentId(), new Pending(result, clock.instant().plus(props.async().delay())));
            }
            return first;
        }
        return results.putIfAbsent(req.paymentId(), result);
    }

    // Счета у поставщика по данным плательщика. «Случайные», но одинаковые для одних и тех же данных.
    @PostMapping("/invoices")
    public List<Invoice> invoices(@Valid @RequestBody InvoiceRequest req) {
        Map<String, String> fields = req.fields() == null ? Map.of() : req.fields();
        String key = req.providerCode() + KEY_SEPARATOR + new TreeMap<>(fields).values().stream()
                .filter(v -> v != null && v.matches(HAS_DIGIT)).reduce("", String::concat);
        if (key.endsWith(NO_INVOICES_SUFFIX)) {
            return List.of();
        }
        Random rnd = new Random(key.hashCode());
        YearMonth month = YearMonth.now(clock);
        String monthName = MONTH.format(month);
        List<Service> services = SERVICES.getOrDefault(req.group(), EDUCATION);
        int count = 1 + rnd.nextInt(Math.min(MAX_INVOICES, services.size()));
        List<Invoice> list = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        while (list.size() < count) {
            int i = rnd.nextInt(services.size());
            if (!used.add(i)) {
                continue;
            }
            Service s = services.get(i);
            int amount = s.min() + rnd.nextInt(s.max() - s.min()) / AMOUNT_STEP * AMOUNT_STEP;
            list.add(new Invoice(Integer.toHexString(key.hashCode()) + "-" + month + "-" + i, s.title() + ", " + monthName,
                    s.description(), BigDecimal.valueOf(amount), month.atDay(DUE_DAY)));
        }
        return list;
    }

    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<Result> find(@PathVariable UUID paymentId) {
        return results.find(paymentId).map(this::completedIfDue).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Долгая оплата: срок подошёл — отдаём и запоминаем окончательный итог
    private Result completedIfDue(Result stored) {
        if (stored.status() != Status.PENDING) {
            return stored;
        }
        return pending.find(stored.paymentId())
                .filter(p -> !clock.instant().isBefore(p.readyAt()))
                .map(p -> {
                    results.replace(stored.paymentId(), p.result());
                    return p.result();
                })
                .orElse(stored);
    }

    private static String rejectReason(Integration integration, Map<String, String> fields) {
        for (Map.Entry<String, String> f : fields.entrySet()) {
            String digits = f.getValue() == null ? "" : f.getValue().replaceAll("\\D", "");
            if (!f.getKey().equals(NOMINAL_FIELD) && digits.length() >= ACCOUNT_MIN_DIGITS && digits.endsWith(NOT_FOUND_SUFFIX)) {
                return integration.notFound();
            }
            if (BANNED_LOGIN.equalsIgnoreCase(f.getValue())) {
                return BANNED;
            }
        }
        return null;
    }

    private static String voucher() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < VOUCHER_LENGTH; i++) {
            if (i > 0 && i % VOUCHER_GROUP == 0) {
                sb.append(VOUCHER_SEPARATOR);
            }
            sb.append(VOUCHER_ALPHABET.charAt(rnd.nextInt(VOUCHER_ALPHABET.length())));
        }
        return sb.toString();
    }

    // PENDING — принят, итог позже (долгая оплата)
    public enum Status { ACCEPTED, REJECTED, PENDING }

    // Будущий итог долгой оплаты и когда его отдавать
    public record Pending(Result result, Instant readyAt) {
    }

    private record Service(String title, String description, int min, int max) {
    }

    public record PayRequest(@NotNull UUID paymentId, @NotBlank String integration, @NotBlank String providerCode,
                             @NotNull @Positive BigDecimal amount, Map<String, String> fields, boolean issueVoucher) {
    }

    public record InvoiceRequest(@NotBlank String providerCode, @NotBlank String group, Map<String, String> fields) {
    }

    public record Invoice(String externalId, String title, String description, BigDecimal amount, LocalDate dueDate) {
    }

    public record Result(UUID paymentId, Status status, String externalId, String voucherCode, String message) {
    }
}
