package com.paymentsapp.providers;

import com.paymentsapp.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

// Отказы заглушки ГИС ГМП на неверный запрос
enum ProvidersError implements ErrorCode {

    UIN_FORMAT(HttpStatus.BAD_REQUEST, "УИН — 20 или 25 цифр"),
    SEARCH_DATA_REQUIRED(HttpStatus.BAD_REQUEST, "Не указаны данные для поиска");

    private final HttpStatus status;
    private final String template;

    ProvidersError(HttpStatus status, String template) {
        this.status = status;
        this.template = template;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String template() {
        return template;
    }
}
