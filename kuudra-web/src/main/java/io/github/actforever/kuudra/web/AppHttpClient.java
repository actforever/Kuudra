package io.github.actforever.kuudra.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Remote client used by Web; it has no in-process dependency on KuudraApp. */
@Component
final class AppHttpClient {
    private final RestClient client;
    AppHttpClient(@Value("${kuudra.app.base-url:http://127.0.0.1:8081}") String baseUrl) { client = RestClient.builder().baseUrl(baseUrl).build(); }
    @SuppressWarnings("unchecked")
    Object get(String path) { return client.get().uri(path).retrieve().body(Object.class); }
    @SuppressWarnings("unchecked")
    Object post(String path) { return client.post().uri(path).retrieve().body(Object.class); }
}
