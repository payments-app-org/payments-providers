package com.paymentsapp.providers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Заглушка ГИС ГМП (госуслуги): начисления по УИН. Ответ «случайный», но одинаковый для одного УИН.
// УИН, оканчивающийся на 0, — начислений нет.
@RestController
@RequestMapping("/gis-gmp")
public class GisGmpController {

    private record Template(String purpose, String payee, String inn, String kpp, String account, String bik,
                            String kbk, int min, int max) {
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template("Штраф ГИБДД за превышение скорости", "УФК по г. Москве (ГУ МВД России по г. Москве)",
                    "7707089101", "770731005", "03100643000000017300", "004525988", "18811601123010001140", 500, 2000),
            new Template("Налог на имущество физических лиц", "УФК по г. Москве (ИФНС России № 7 по г. Москве)",
                    "7707329152", "770701001", "03100643000000017300", "004525988", "18210601020031000110", 800, 9000),
            new Template("Государственная пошлина за оформление загранпаспорта", "УФК по г. Москве (ГУ МВД России по г. Москве)",
                    "7707089101", "770731005", "03100643000000017300", "004525988", "18810807100018000110", 6000, 6000),
            new Template("Транспортный налог", "УФК по Московской области (МИФНС России № 1)",
                    "5003021311", "500301001", "03100643000000014800", "004525987", "18210604012021000110", 1200, 15000),
            new Template("Штраф за неоплату парковки", "УФК по г. Москве (ГКУ «АМПП»)",
                    "7703729834", "770301001", "03100643000000017300", "004525988", "88811601205010000140", 5000, 5000));

    @GetMapping("/charges")
    public List<Charge> charges(@RequestParam String uin) {
        if (!uin.matches("\\d{20}|\\d{25}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "УИН — 20 или 25 цифр");
        }
        if (uin.endsWith("0")) {
            return List.of();
        }
        Random rnd = new Random(uin.hashCode());
        int count = 1 + rnd.nextInt(2);
        List<Charge> charges = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Template t = TEMPLATES.get(rnd.nextInt(TEMPLATES.size()));
            int amount = t.min() == t.max() ? t.min() : t.min() + rnd.nextInt(t.max() - t.min()) / 100 * 100;
            LocalDate issued = LocalDate.now().minusDays(3 + rnd.nextInt(40));
            // Штрафы ГИБДД в первые 20 дней — со скидкой 50%
            boolean discount = t.purpose().startsWith("Штраф ГИБДД") && issued.isAfter(LocalDate.now().minusDays(20));
            charges.add(new Charge(uin + "-" + (i + 1), uin, t.purpose(), t.payee(), t.inn(), t.kpp(), t.account(), t.bik(),
                    t.kbk(), "45000000", BigDecimal.valueOf(amount),
                    discount ? BigDecimal.valueOf(amount / 2) : null, discount ? issued.plusDays(20) : null, issued));
        }
        return charges;
    }

    public record Charge(String externalId, String uin, String purpose, String payeeName, String payeeInn, String payeeKpp,
                         String payeeAccount, String payeeBik, String kbk, String oktmo, BigDecimal amount,
                         BigDecimal discountedAmount, LocalDate discountUntil, LocalDate issuedAt) {
    }
}
