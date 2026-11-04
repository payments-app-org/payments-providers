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

    public record Result(UUID paymentId, String status, String externalId, String voucherCode, String message) {
    }
}
