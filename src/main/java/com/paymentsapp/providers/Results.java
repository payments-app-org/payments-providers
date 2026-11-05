package com.paymentsapp.providers;

import com.paymentsapp.platform.redis.IdempotentResults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

// Итоги оплат у шлюзов в Redis: переживают рестарт и одни на все поды
@Configuration
class Results {

    private static final String GATEWAY_PAYMENTS = "providers:payment:";

    @Bean
    IdempotentResults<GatewayController.Result> gatewayResults(StringRedisTemplate redis, JsonMapper json,
                                                              ProvidersProperties props) {
        return new IdempotentResults<>(redis, json, GATEWAY_PAYMENTS, props.resultsTtl(), GatewayController.Result.class);
    }
}
