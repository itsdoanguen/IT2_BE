package vn.udn.dut.it2.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({AiProperties.class, StorageProperties.class})
public class AiClientConfig {

    @Bean
    public RestClient aiRestClient() {
        // Force HTTP/1.1 because Python Uvicorn (h11) rejects Java HttpClient's default h2c upgrade
        // on cleartext http:// requests, which causes missing-body 422 errors.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
