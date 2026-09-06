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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared SKVS connection config from {@code plugins/.skvs/config.txt} (key=value).
 * Same file used by other SKVS-backed plugins.
 */
public final class SkvsConnectionConfig {

    private final String host;
    private final int port;
    private final String secretKey;
    private final String database;
    private final boolean useHttps;

    private SkvsConnectionConfig(String host, int port, String secretKey, String database, boolean useHttps) {
        this.host = host;
        this.port = port;
        this.secretKey = secretKey;
        this.database = database;
        this.useHttps = useHttps;
    }

    public String host() { return host; }
    public int port() { return port; }
    public String secretKey() { return secretKey; }
    public String database() { return database; }
    public boolean useHttps() { return useHttps; }

    public String baseUrl() {
        return (useHttps ? "https://" : "http://") + host + ":" + port;
    }

    /**
     * @param pluginsFolder the server's {@code plugins} directory (parent of LuckPerms data folder)
     */
    public static SkvsConnectionConfig load(Path pluginsFolder) throws SkvsConfigException {
        Path configPath = pluginsFolder.resolve(".skvs").resolve("config.txt");
        if (!Files.isRegularFile(configPath)) {
            throw new SkvsConfigException("Missing SKVS config: " + configPath.toAbsolutePath()
                    + " (need host, port, secret_key)");
        }

        String host = null;
        int port = -1;
        String secretKey = null;
        String database = "default";
        boolean useHttps = false;

        try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    throw new SkvsConfigException("Invalid line " + lineNumber + " in " + configPath);
                }
                String key = trimmed.substring(0, eq).trim().toLowerCase();
                String value = trimmed.substring(eq + 1).trim();
                switch (key) {
                    case "host":
                        host = value;
                        break;
                    case "port":
                        try {
                            port = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            throw new SkvsConfigException("Invalid port on line " + lineNumber);
                        }
                        break;
                    case "secret_key":
                    case "secretkey":
                    case "api_key":
                    case "apikey":
                        secretKey = value;
                        break;
                    case "database":
                    case "db":
                    case "db_name":
                        database = value;
                        break;
                    case "use_https":
                    case "https":
                    case "ssl":
                        useHttps = Boolean.parseBoolean(value);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            throw new SkvsConfigException("Failed to read " + configPath + ": " + e.getMessage(), e);
        }

        if (host == null || host.isEmpty()) {
            throw new SkvsConfigException("Missing 'host' in " + configPath);
        }
        if (port <= 0 || port > 65535) {
            throw new SkvsConfigException("Missing/invalid 'port' in " + configPath);
        }
        if (secretKey == null || secretKey.isEmpty()) {
            throw new SkvsConfigException("Missing 'secret_key' in " + configPath);
        }
        return new SkvsConnectionConfig(host, port, secretKey, database, useHttps);
    }

    public static final class SkvsConfigException extends Exception {
        public SkvsConfigException(String message) { super(message); }
        public SkvsConfigException(String message, Throwable cause) { super(message, cause); }
    }
}
