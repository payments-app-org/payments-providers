package com.paymentsapp.providers;

import java.util.Arrays;

// Шлюз поставщика (providers.integration в biller): префикс номера операции и текст отказа «не нашли аккаунт»
enum Integration {
    OPERATOR_GATE("OPR", "Оператор не нашёл абонента с таким номером"),
    TRANSPORT_GATE("TRN", "Поставщик не нашёл карту или лицевой счёт"),
    GAMES_GATE("GMS", Integration.ACCOUNT_NOT_FOUND),
    EMONEY_GATE("EMN", "Кошелёк не найден"),
    EDUCATION_GATE("EDU", Integration.ACCOUNT_NOT_FOUND),
    SHOPS_GATE("SHP", Integration.ACCOUNT_NOT_FOUND),
    TRAVEL_GATE("TRV", "Заказ не найден у туроператора"),
    INSURANCE_GATE("INS", "Страховая не нашла полис или счёт"),
    SECURITY_GATE("SEC", Integration.ACCOUNT_NOT_FOUND),
    CHARITY_GATE("CHR", Integration.ACCOUNT_NOT_FOUND),
    UTILITIES_GATE("UTL", Integration.ACCOUNT_NOT_FOUND),
    INTERNET_GATE("NET", Integration.ACCOUNT_NOT_FOUND),
    MISC_GATE("MSC", Integration.ACCOUNT_NOT_FOUND),
    // Квитирование в ГИС ГМП и всё, чего нет выше
    GIS_GMP("GMP", Integration.ACCOUNT_NOT_FOUND);

    private static final String ACCOUNT_NOT_FOUND = "Поставщик не нашёл аккаунт";

    private final String prefix;
    private final String notFound;

    Integration(String prefix, String notFound) {
        this.prefix = prefix;
        this.notFound = notFound;
    }

    String prefix() {
        return prefix;
    }

    String notFound() {
        return notFound;
    }

    static Integration of(String name) {
        return Arrays.stream(values()).filter(i -> i.name().equals(name)).findFirst().orElse(GIS_GMP);
    }
}
