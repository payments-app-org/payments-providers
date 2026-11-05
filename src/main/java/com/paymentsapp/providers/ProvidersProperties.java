package com.paymentsapp.providers;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Настройки заглушек поставщиков (providers.* в application.yml)
@ConfigurationProperties("providers")
public record ProvidersProperties(
        // Сколько хранить итог оплаты у шлюза: повтор и запрос статуса в этот срок получают тот же ответ
        Duration resultsTtl,
        // «Поставщик думает»: задержка ответа шлюза — случайная в этих пределах
        Duration thinkMin,
        Duration thinkMax,
        Async async
) {

    // Долгие оплаты: коды поставщиков (подписка через партнёра) и через сколько поставщик проводит платёж
    public record Async(Set<String> providers, Duration delay) {
    }
}
