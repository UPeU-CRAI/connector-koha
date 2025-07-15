package com.identicum.connectors.services;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;

import java.io.IOException;

/**
 * Default implementation of HttpClientAdapter delegating to a CloseableHttpClient.
 */
public class DefaultHttpClientAdapter implements HttpClientAdapter {

    private final CloseableHttpClient delegate;

    public DefaultHttpClientAdapter(CloseableHttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public CloseableHttpResponse execute(HttpUriRequest request) throws IOException {
        return delegate.execute(request);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
