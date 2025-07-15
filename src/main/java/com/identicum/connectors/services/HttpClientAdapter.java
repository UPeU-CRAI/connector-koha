package com.identicum.connectors.services;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;

import java.io.Closeable;
import java.io.IOException;

/**
 * Simple adapter interface over Apache HttpClient to allow mocking in tests.
 */
public interface HttpClientAdapter extends Closeable {
    CloseableHttpResponse execute(HttpUriRequest request) throws IOException;
}
