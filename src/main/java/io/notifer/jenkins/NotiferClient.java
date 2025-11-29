package io.notifer.jenkins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for communicating with Notifer API.
 */
public class NotiferClient implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(NotiferClient.class.getName());
    private static final int TIMEOUT_MS = 30000;
    private static final Gson GSON = new GsonBuilder().create();

    private final String serverUrl;
    private final String token;

    /**
     * Create a new Notifer client.
     *
     * @param serverUrl Base URL of the Notifer server (e.g., https://app.notifer.io)
     * @param token     Topic access token with write permission
     */
    public NotiferClient(String serverUrl, String token) {
        this.serverUrl = normalizeUrl(serverUrl);
        this.token = token;
    }

    /**
     * Send a notification to a topic.
     *
     * @param topic    Topic name
     * @param message  Message content
     * @param title    Optional title (can be null)
     * @param priority Priority 1-5 (default 3)
     * @param tags     Optional list of tags (can be null or empty)
     * @return Response from the server
     * @throws NotiferException if the request fails
     */
    public NotiferResponse send(String topic, String message, String title, int priority, List<String> tags)
            throws NotiferException {

        String url = buildUrl(topic);
        Map<String, Object> payload = buildPayload(message, title, priority, tags);

        LOGGER.log(Level.FINE, "Sending notification to {0}", url);

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(TIMEOUT_MS)
                .setSocketTimeout(TIMEOUT_MS)
                .setConnectionRequestTimeout(TIMEOUT_MS)
                .build();

        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build()) {

            HttpPost request = new HttpPost(url);
            request.setHeader("Content-Type", "application/json");
            request.setHeader("X-Topic-Token", token);
            request.setEntity(new StringEntity(GSON.toJson(payload), ContentType.APPLICATION_JSON));

            HttpResponse response = client.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            HttpEntity entity = response.getEntity();
            String responseBody = entity != null ? EntityUtils.toString(entity) : "";

            if (statusCode >= 200 && statusCode < 300) {
                LOGGER.log(Level.FINE, "Notification sent successfully: {0}", responseBody);
                return GSON.fromJson(responseBody, NotiferResponse.class);
            } else {
                String errorMessage = String.format("Notifer API returned status %d: %s", statusCode, responseBody);
                LOGGER.log(Level.WARNING, errorMessage);
                throw new NotiferException(errorMessage, statusCode);
            }

        } catch (IOException e) {
            String errorMessage = "Failed to send notification: " + e.getMessage();
            LOGGER.log(Level.SEVERE, errorMessage, e);
            throw new NotiferException(errorMessage, e);
        }
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "https://app.notifer.io";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String buildUrl(String topic) {
        // Use the standard publish endpoint with topic token
        return serverUrl + "/" + topic;
    }

    private Map<String, Object> buildPayload(String message, String title, int priority, List<String> tags) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", message);

        if (title != null && !title.isEmpty()) {
            payload.put("title", title);
        }

        // Clamp priority to valid range
        priority = Math.max(1, Math.min(5, priority));
        payload.put("priority", priority);

        if (tags != null && !tags.isEmpty()) {
            payload.put("tags", tags);
        }

        return payload;
    }

    /**
     * Response from Notifer API.
     */
    public static class NotiferResponse implements Serializable {
        private static final long serialVersionUID = 1L;

        private String id;
        private String topic;
        private String message;
        private int priority;
        private List<String> tags;

        public String getId() {
            return id;
        }

        public String getTopic() {
            return topic;
        }

        public String getMessage() {
            return message;
        }

        public int getPriority() {
            return priority;
        }

        public List<String> getTags() {
            return tags;
        }

        @Override
        public String toString() {
            return String.format("NotiferResponse{id='%s', topic='%s', priority=%d}", id, topic, priority);
        }
    }

    /**
     * Exception thrown when Notifer API request fails.
     */
    public static class NotiferException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        public NotiferException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public NotiferException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = -1;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
