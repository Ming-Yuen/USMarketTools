package com.ykm.common.common_lib.config;

import com.ykm.common.common_lib.filter.RestClientLoggingInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final RestClientLoggingInterceptor restClientLoggingInterceptor;

    @Bean
    public RestClient restClient(RestClient.Builder builder,
                                 ClientHttpRequestFactoryBuilder factoryBuilder) {
        ClientHttpRequestFactory base = factoryBuilder.build();
        ClientHttpRequestFactory buffered = new BufferingClientHttpRequestFactory(base);

        return builder
                .requestFactory(buffered)
                .requestInterceptor(restClientLoggingInterceptor)
                .build();
    }
}