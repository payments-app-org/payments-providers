package com.paymentsapp.providers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Заглушка ГИС ГМП (госуслуги): начисления по УИН или по идентификаторам плательщика (СТС, ВУ, ИНН, паспорт).
// Ответ «случайный», но одинаковый для одних и тех же данных. Данные, оканчивающиеся на 0, — начислений нет.
// kind — вид начислений (GIBDD, TAX, FSSP); без него при поиске по УИН вид определяем по коду администратора в начале УИН.
@RestController
@RequestMapping("/gis-gmp")
public class GisGmpController {

    private enum Kind {
        GIBDD("18810", 20), TAX("182", 20), FSSP("32277", 25), OTHER("188", 20);

        final String uinPrefix;
        final int uinLength;

        Kind(String uinPrefix, int uinLength) {
            this.uinPrefix = uinPrefix;
            this.uinLength = uinLength;
        }
    }

    private record Template(Kind kind, String purpose, String payee, String inn, String kpp, String account, String bik,
                            String kbk, int min, int max) {
    }

    private static final String MSK_ACCOUNT = "03100643000000017300";
    private static final String MSK_BIK = "004525988";
    // Налоги с 2023 года — единым налоговым платежом на счёт Казначейства России
    private static final String ENP = "Казначейство России (ФНС России)";

    private static final List<Template> TEMPLATES = List.of(
            new Template(Kind.GIBDD, "Штраф ГИБДД за превышение скорости", "УФК по г. Москве (ГУ МВД России по г. Москве)",
                    "7707089101", "770731005", MSK_ACCOUNT, MSK_BIK, "18811601123010001140", 500, 2000),
            new Template(Kind.GIBDD, "Штраф ГИБДД за проезд на запрещающий сигнал светофора", "УФК по г. Москве (ГУ МВД России по г. Москве)",
                    "7707089101", "770731005", MSK_ACCOUNT, MSK_BIK, "18811601123010001140", 1000, 1000),
            new Template(Kind.GIBDD, "Штраф ГИБДД за нарушение правил остановки и стоянки", "УФК по г. Москве (ГУ МВД России по г. Москве)",
                    "7707089101", "770731005", MSK_ACCOUNT, MSK_BIK, "18811601123010001140", 3000, 3000),
            new Template(Kind.TAX, "Налог на имущество физических лиц", ENP,
                    "7727406020", "770801001", "03100643000000018500", "017003983", "18201061201010000510", 800, 9000),
            new Template(Kind.TAX, "Транспортный налог", ENP,
                    "7727406020", "770801001", "03100643000000018500", "017003983", "18201061201010000510", 1200, 15000),
            new Template(Kind.TAX, "Земельный налог", ENP,
                    "7727406020", "770801001", "03100643000000018500", "017003983", "18201061201010000510", 500, 6000),
            new Template(Kind.FSSP, "Задолженность по исполнительному производству", "УФК по г. Москве (ГУФССП России по г. Москве)",
                    "7704270863", "770401001", MSK_ACCOUNT, MSK_BIK, "32211701010016000180", 1500, 40000),
            new Template(Kind.FSSP, "Исполнительский сбор", "УФК по г. Москве (ГУФССП России по г. Москве)",
                    "7704270863", "770401001", MSK_ACCOUNT, MSK_BIK, "32211502010016000140", 1000, 5000),
            new Template(Kind.OTHER, "Государственная пошлина за оформление загранпаспорта", "УФК по г. Москве (ГУ МВД России по г. Москве)",
                    "7707089101", "770731005", MSK_ACCOUNT, MSK_BIK, "18810807100018000110", 6000, 6000),
            new Template(Kind.OTHER, "Штраф за неоплату парковки", "УФК по г. Москве (ГКУ «АМПП»)",
                    "7703729834", "770301001", MSK_ACCOUNT, MSK_BIK, "88811601205010000140", 5000, 5000));

    @GetMapping("/charges")
    public List<Charge> charges(@RequestParam Map<String, String> q) {
        String uin = q.get("uin");
        Kind kind = q.containsKey("kind") ? Kind.valueOf(q.get("kind")) : null;
        if (uin != null) {
            if (!uin.matches("\\d{20}|\\d{25}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "УИН — 20 или 25 цифр");
            }
            if (uin.endsWith("0")) {
                return List.of();
            }
            if (kind == null) {
                kind = uin.startsWith("18810") ? Kind.GIBDD : uin.startsWith("182") ? Kind.TAX
                        : uin.startsWith("322") ? Kind.FSSP : null;
            }
            return generate(uin, kind, uin, 1 + new Random(uin.hashCode()).nextInt(2));
        }
        // Поиск по документам плательщика: у каждого начисления свой УИН
        String key = String.join("|", nz(q.get("sts")), nz(q.get("vu")), nz(q.get("inn")), nz(q.get("passport")));
        if (key.replace("|", "").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не указаны данные для поиска");
        }
        if (key.replace("|", "").endsWith("0")) {
            return List.of();
        }
        return generate(key, kind == null ? Kind.OTHER : kind, null, 1 + new Random(key.hashCode()).nextInt(3));
    }

    // fixedUin — поиск по УИН (у всех начислений он один); null — каждому начислению свой
    private List<Charge> generate(String seed, Kind kind, String fixedUin, int count) {
        Random rnd = new Random(seed.hashCode() * 31L + 7);
        List<Template> pool = kind == null ? TEMPLATES : TEMPLATES.stream().filter(t -> t.kind() == kind).toList();
        List<Charge> charges = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Template t = pool.get(rnd.nextInt(pool.size()));
            int amount = t.min() == t.max() ? t.min() : t.min() + rnd.nextInt(t.max() - t.min()) / 100 * 100;
            LocalDate issued = LocalDate.now().minusDays(3 + rnd.nextInt(40));
            String uin = fixedUin != null ? fixedUin : uin(t.kind(), rnd);
            String purpose = t.kind() == Kind.FSSP
                    ? t.purpose() + " № " + (10000 + rnd.nextInt(89999)) + "/" + (issued.getYear() % 100) + "/77001-ИП"
                    : t.purpose();
            // Штрафы ГИБДД в первые 20 дней — со скидкой 50%
            boolean discount = t.kind() == Kind.GIBDD && t.purpose().startsWith("Штраф ГИБДД")
                    && issued.isAfter(LocalDate.now().minusDays(20));
            charges.add(new Charge(fixedUin != null ? uin + "-" + (i + 1) : uin, uin, purpose, t.payee(), t.inn(), t.kpp(),
                    t.account(), t.bik(), t.kbk(), "45000000", BigDecimal.valueOf(amount),
                    discount ? BigDecimal.valueOf(amount / 2) : null, discount ? issued.plusDays(20) : null, issued));
        }
        return charges;
    }

    private static String uin(Kind kind, Random rnd) {
        StringBuilder sb = new StringBuilder(kind.uinPrefix);
        while (sb.length() < kind.uinLength - 1) {
            sb.append(rnd.nextInt(10));
        }
        return sb.append(1 + rnd.nextInt(9)).toString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    public record Charge(String externalId, String uin, String purpose, String payeeName, String payeeInn, String payeeKpp,
                         String payeeAccount, String payeeBik, String kbk, String oktmo, BigDecimal amount,
                         BigDecimal discountedAmount, LocalDate discountUntil, LocalDate issuedAt) {
    }
}
