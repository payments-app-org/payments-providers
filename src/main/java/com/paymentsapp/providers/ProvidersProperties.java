package com.paymentsapp.providers;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Настройки заглушек поставщиков (providers.* в application.yml)
@ConfigurationProperties("providers")
public record ProvidersProperties(
        // Сколько хранить итог оплаты у шлюза: повтор и запрос статуса в этот срок получают тот же ответ
        Duration resultsTtl,
        // «Поставщик думает»: задержка ответа шлюза — случайная в этих пределах
        Duration thinkMin,
        Duration thinkMax
) {
}
