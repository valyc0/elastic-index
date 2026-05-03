package com.example.ragclient.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${rag.api.base-url}")
    private String baseUrl;

    @Value("${rag.api.timeout:60000}")
    private long timeout;

    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(timeout));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
