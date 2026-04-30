package io.bootify.my_app.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.Arrays;

@Configuration
public class ElasticsearchConfig {

    /** Supporta lista separata da virgole, es. "http://es1:9200,http://es2:9200". */
    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String elasticsearchUris;

    @Bean
    public RestClient restClient() {
        HttpHost[] hosts = Arrays.stream(elasticsearchUris.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(uriStr -> {
                    URI uri = URI.create(uriStr);
                    int port = uri.getPort() > 0 ? uri.getPort() : 9200;
                    String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
                    return new HttpHost(uri.getHost(), port, scheme);
                })
                .toArray(HttpHost[]::new);
        return RestClient.builder(hosts).build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        RestClientTransport transport = new RestClientTransport(
                restClient,
                new JacksonJsonpMapper()
        );
        return new ElasticsearchClient(transport);
    }
}
