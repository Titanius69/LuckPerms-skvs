/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.storage.implementation.skvs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal blocking HTTP client for the SKVS SQL query + transaction endpoints.
 * All calls must be made off the Bukkit/Paper main thread.
 */
public final class SkvsClient {

    private final SkvsConnectionConfig config;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public SkvsClient(final SkvsConnectionConfig config, final int connectTimeoutMs, final int readTimeoutMs) {
        this.config = config;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public SkvsConnectionConfig getConfig() {
        return config;
    }

    public List<Map<String, Object>> query(final String sql, final List<Object> params) throws SkvsQueryException {
        return queryFull(sql, params, null).rows();
    }

    public QueryResult queryFull(final String sql, final List<Object> params) throws SkvsQueryException {
        return queryFull(sql, params, null);
    }

    public QueryResult queryFull(final String sql, final List<Object> params, final Long txId) throws SkvsQueryException {
        final String url = config.baseUrl() + "/api/db/" + urlEncode(config.database()) + "/query";

        final Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("sql", sql);
        body.put("params", params == null ? Collections.emptyList() : params);
        if (txId != null) {
            body.put("txId", txId);
        }
        final String jsonBody = MiniJson.writeValue(body);

        try {
            final HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-API-Key", config.secretKey());
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);

            final byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            final OutputStream os = conn.getOutputStream();
            try {
                os.write(bodyBytes);
            } finally {
                os.close();
            }

            final int status = conn.getResponseCode();
            final InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            final String responseText;
            if (stream == null) {
                responseText = "";
            } else {
                responseText = new String(readAllBytes(stream), StandardCharsets.UTF_8);
            }

            if (status < 200 || status >= 300) {
                throw new SkvsQueryException("SKVS returned HTTP " + status + ": " + responseText);
            }

            final Object parsed = MiniJson.parse(responseText.isEmpty() ? "{}" : responseText);
            if (!(parsed instanceof Map)) {
                throw new SkvsQueryException("Unexpected SKVS response shape: " + responseText);
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = (Map<String, Object>) parsed;

            final Object rowsObj = map.get("rows");
            final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            if (rowsObj instanceof List) {
                final List<?> list = (List<?>) rowsObj;
                for (final Object rowObj : list) {
                    if (rowObj instanceof Map) {
                        final Map<?, ?> rowMap = (Map<?, ?>) rowObj;
                        final Map<String, Object> row = new LinkedHashMap<String, Object>();
                        for (final Map.Entry<?, ?> e : rowMap.entrySet()) {
                            row.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        rows.add(row);
                    }
                }
            }

            Long affectedRows = null;
            final Object affectedObj = map.get("affected_rows");
            if (affectedObj instanceof Number) {
                affectedRows = ((Number) affectedObj).longValue();
            }

            return new QueryResult(rows, affectedRows);
        } catch (final IOException e) {
            throw new SkvsQueryException("Failed to reach SKVS server at " + config.baseUrl() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Begins a transaction and returns the txId to thread through subsequent queryFull(..., txId) calls.
     */
    public long beginTransaction() throws SkvsQueryException {
        final String url = config.baseUrl() + "/api/db/" + urlEncode(config.database()) + "/transaction/begin";
        try {
            final HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-API-Key", config.secretKey());
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(0);
            final OutputStream os = conn.getOutputStream();
            try {
                // empty body
            } finally {
                os.close();
            }

            final int status = conn.getResponseCode();
            final InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            final String responseText;
            if (stream == null) {
                responseText = "";
            } else {
                responseText = new String(readAllBytes(stream), StandardCharsets.UTF_8);
            }

            if (status < 200 || status >= 300) {
                throw new SkvsQueryException("SKVS beginTransaction HTTP " + status + ": " + responseText);
            }

            final Object parsed = MiniJson.parse(responseText.isEmpty() ? "{}" : responseText);
            if (!(parsed instanceof Map)) {
                throw new SkvsQueryException("Unexpected beginTransaction response: " + responseText);
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = (Map<String, Object>) parsed;
            final Object txObj = map.get("txId");
            if (txObj instanceof Number) {
                return ((Number) txObj).longValue();
            }
            throw new SkvsQueryException("No txId in beginTransaction response: " + responseText);
        } catch (final IOException e) {
            throw new SkvsQueryException("Failed to begin SKVS transaction: " + e.getMessage(), e);
        }
    }

    public void commitTransaction(final long txId) throws SkvsQueryException {
        final String url = config.baseUrl() + "/api/db/" + urlEncode(config.database()) + "/transaction/" + txId + "/commit";
        doEmptyPost(url, "commitTransaction");
    }

    public void rollbackTransaction(final long txId) throws SkvsQueryException {
        final String url = config.baseUrl() + "/api/db/" + urlEncode(config.database()) + "/transaction/" + txId + "/rollback";
        doEmptyPost(url, "rollbackTransaction");
    }

    private void doEmptyPost(final String url, final String op) throws SkvsQueryException {
        try {
            final HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-API-Key", config.secretKey());
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(0);
            final OutputStream os = conn.getOutputStream();
            try {
                // empty body
            } finally {
                os.close();
            }

            final int status = conn.getResponseCode();
            if (status == 204 || (status >= 200 && status < 300)) {
                return;
            }
            final InputStream stream = conn.getErrorStream();
            final String responseText;
            if (stream == null) {
                responseText = "";
            } else {
                responseText = new String(readAllBytes(stream), StandardCharsets.UTF_8);
            }
            throw new SkvsQueryException("SKVS " + op + " HTTP " + status + ": " + responseText);
        } catch (final IOException e) {
            throw new SkvsQueryException("Failed to " + op + ": " + e.getMessage(), e);
        }
    }

    /** Java 8 compatible replacement for InputStream.readAllBytes(). */
    private static byte[] readAllBytes(final InputStream in) throws IOException {
        final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        final byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static String urlEncode(final String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static final class QueryResult {
        private final List<Map<String, Object>> rows;
        private final Long affectedRows;

        public QueryResult(final List<Map<String, Object>> rows, final Long affectedRows) {
            this.rows = rows;
            this.affectedRows = affectedRows;
        }

        public List<Map<String, Object>> rows() {
            return rows;
        }

        public Long affectedRows() {
            return affectedRows;
        }
    }

    public static final class SkvsQueryException extends Exception {
        public SkvsQueryException(final String message) {
            super(message);
        }

        public SkvsQueryException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
