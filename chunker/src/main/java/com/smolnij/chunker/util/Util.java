package com.smolnij.chunker.util;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;

import java.net.http.HttpClient;

public class Util {
    // LM-Studio's embedded HTTP server hangs on the JDK HttpClient's default
    // HTTP/2 upgrade handshake — pin the transport to HTTP/1.1.
    public static JdkHttpClientBuilder lmStudioHttpClientBuilder() {
        return new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1));
    }
}
