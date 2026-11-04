package com.paymentsapp.providers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Заглушка шлюзов поставщиков: операторы связи (OPERATOR_GATE), транспорт (TRANSPORT_GATE), игры (GAMES_GATE),
// ГИС ГМП (квитирование). Делает вид, что поставщик зачислил деньги или выпустил ваучер.
// Отказывает, если номер счёта / карты / аккаунта оканчивается на 0000 или логин — «banned».
// Идемпотентно: повтор с тем же paymentId — тот же ответ.
@RestController
@RequestMapping("/gateway")
public class GatewayController {

    private final Map<UUID, Result> results = new ConcurrentHashMap<>();

    @PostMapping("/payments")
    public Result pay(@RequestBody PayRequest req) throws InterruptedException {
        Result cached = results.get(req.paymentId());
        if (cached != null) {
            return cached;
        }
        // Поставщик думает
        Thread.sleep(200 + new Random().nextInt(400));
        Map<String, String> fields = req.fields() == null ? Map.of() : req.fields();
        String reject = rejectReason(req.integration(), fields);
        Result result = reject != null
                ? new Result(req.paymentId(), "REJECTED", null, null, reject)
                : new Result(req.paymentId(), "ACCEPTED", prefix(req.integration()) + "-" + Long.toString(Math.abs(new Random().nextLong()), 36).toUpperCase(),
                        req.issueVoucher() ? voucher() : null, "Зачислено");
        Result first = results.putIfAbsent(req.paymentId(), result);
        return first != null ? first : result;
    }

    // Счета у поставщика по данным плательщика. «Случайные», но одинаковые для одних и тех же данных.
    // Данные, оканчивающиеся на 0, — счетов нет.
    @PostMapping("/invoices")
    public java.util.List<Invoice> invoices(@RequestBody InvoiceRequest req) {
        Map<String, String> fields = req.fields() == null ? Map.of() : req.fields();
        String key = req.providerCode() + "|" + new java.util.TreeMap<>(fields).values().stream()
                .filter(v -> v != null && v.matches(".*\\d.*")).reduce("", String::concat);
        if (key.endsWith("0")) {
            return java.util.List.of();
        }
        Random rnd = new Random(key.hashCode());
        java.time.YearMonth month = java.time.YearMonth.now();
        String monthName = java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale.of("ru")).format(month);
        String[][] services = "INTERNET".equals(req.group()) ? new String[][] {
                {"Абонентская плата", "Интернет и ТВ по тарифу", "550", "1490"}} : "UTILITIES".equals(req.group()) ? new String[][] {
                {"Жилищно-коммунальные услуги", "Содержание жилья, отопление, вода", "4200", "9800"},
                {"Электроэнергия", "По показаниям счётчика", "900", "2600"},
                {"Взнос на капитальный ремонт", "Фонд капремонта", "1100", "2400"},
                {"Вывоз ТКО", "Региональный оператор", "300", "700"}} : new String[][] {
                {"Питание в школе", "Завтраки и обеды", "2400", "4200"},
                {"Группа продлённого дня", "Присмотр и уход после уроков", "2000", "3500"},
                {"Кружок «Робототехника»", "Дополнительное образование", "3000", "6000"},
                {"Секция плавания", "Бассейн, 8 занятий", "2500", "4500"},
                {"Подготовка к ЕГЭ", "Математика, 8 занятий", "4000", "8000"}};
        int count = 1 + rnd.nextInt(Math.min(3, services.length));
        java.util.List<Invoice> list = new java.util.ArrayList<>();
        java.util.Set<Integer> used = new java.util.HashSet<>();
        while (list.size() < count) {
            int i = rnd.nextInt(services.length);
            if (!used.add(i)) {
                continue;
            }
            String[] s = services[i];
            int min = Integer.parseInt(s[2]);
            int max = Integer.parseInt(s[3]);
            int amount = min + rnd.nextInt(max - min) / 10 * 10;
            list.add(new Invoice(Integer.toHexString(key.hashCode()) + "-" + month + "-" + i, s[0] + ", " + monthName, s[1],
                    BigDecimal.valueOf(amount), month.atDay(25)));
        }
        return list;
    }

    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<Result> find(@PathVariable UUID paymentId) {
        Result r = results.get(paymentId);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
    }

    private static String rejectReason(String integration, Map<String, String> fields) {
        for (Map.Entry<String, String> f : fields.entrySet()) {
            String digits = f.getValue() == null ? "" : f.getValue().replaceAll("\\D", "");
            if (!f.getKey().equals("nominal") && digits.length() >= 6 && digits.endsWith("0000")) {
                return switch (integration) {
                    case "OPERATOR_GATE" -> "Оператор не нашёл абонента с таким номером";
                    case "TRANSPORT_GATE" -> "Поставщик не нашёл карту или лицевой счёт";
                    case "EMONEY_GATE" -> "Кошелёк не найден";
                    case "INSURANCE_GATE" -> "Страховая не нашла полис или счёт";
                    case "TRAVEL_GATE" -> "Заказ не найден у туроператора";
                    default -> "Поставщик не нашёл аккаунт";
                };
            }
            if ("banned".equalsIgnoreCase(f.getValue())) {
                return "Аккаунт заблокирован у поставщика";
            }
        }
        return null;
    }

    private static String prefix(String integration) {
        return switch (integration) {
            case "OPERATOR_GATE" -> "OPR";
            case "TRANSPORT_GATE" -> "TRN";
            case "GAMES_GATE" -> "GMS";
            case "EMONEY_GATE" -> "EMN";
            case "EDUCATION_GATE" -> "EDU";
            case "SHOPS_GATE" -> "SHP";
            case "TRAVEL_GATE" -> "TRV";
            case "INSURANCE_GATE" -> "INS";
            case "SECURITY_GATE" -> "SEC";
            case "CHARITY_GATE" -> "CHR";
            case "UTILITIES_GATE" -> "UTL";
            case "INTERNET_GATE" -> "NET";
            case "MISC_GATE" -> "MSC";
            default -> "GMP";
        };
    }

    // XXXX-XXXX-XXXX-XXXX
    private static String voucher() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    public record PayRequest(UUID paymentId, String integration, String providerCode, BigDecimal amount,
                             Map<String, String> fields, boolean issueVoucher) {
    }

    public record InvoiceRequest(String providerCode, String group, Map<String, String> fields) {
    }

    public record Invoice(String externalId, String title, String description, BigDecimal amount, java.time.LocalDate dueDate) {
    }

    public record Result(UUID paymentId, String status, String externalId, String voucherCode, String message) {
    }
}
