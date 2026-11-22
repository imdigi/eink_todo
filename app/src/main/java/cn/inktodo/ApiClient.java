package cn.inktodo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class ApiClient {
    static final String ENDPOINT = "https://api.todoist.com/api/v1/sync";
    interface Transport { Reply post(String token, byte[] body) throws Exception; }
    static final class Reply {
        final int code; final String body, retryAfter;
        Reply(int code, String body, String retryAfter) { this.code = code; this.body = body; this.retryAfter = retryAfter; }
    }
    static final class Failure extends IOException {
        final int code; final long retryAt;
        Failure(int code, String message, long retryAt) { super(message); this.code = code; this.retryAt = retryAt; }
    }
    static final class InvalidResponse extends IOException {
        InvalidResponse(){super("同步响应异常，请稍后重试");}
    }
    private final Transport transport;
    ApiClient() { this(new HttpsTransport()); }
    ApiClient(Transport transport) { this.transport = transport; }
    JSONObject sync(String token, String cursor, List<Core.Operation> operations) throws Exception {
        JSONArray commands = new JSONArray(); for (Core.Operation op : operations) commands.put(Core.command(op.uuid, op.taskId, op.kind));
        String body = "sync_token=" + enc(cursor) + "&resource_types=" + enc("[\"items\",\"projects\"]")
            + "&commands=" + enc(commands.toString());
        Reply r = transport.post(token, body.getBytes(StandardCharsets.UTF_8));
        if (r.code != 200) {
            String message = r.code == 401 ? "授权已失效，请在设置中重新设置"
                : r.code == 403 ? "账号或权限受限，请检查 Todoist 授权"
                : r.code == 429 ? "同步过于频繁，请稍后重试" : "Todoist 暂时不可用，请稍后重试";
            throw new Failure(r.code, message, retryAt(r.retryAfter));
        }
        JSONObject j;
        try {j=new JSONObject(r.body);}catch(org.json.JSONException error){throw new InvalidResponse();}
        if (j.optString("sync_token", "").isEmpty() || j.optJSONArray("items") == null || j.optJSONArray("projects") == null)
            throw new InvalidResponse();
        return j;
    }
    static long retryAt(String value) {
        if (value == null || value.isEmpty()) return 0;
        try { return System.currentTimeMillis() + Math.max(0, Long.parseLong(value)) * 1000L; }
        catch (NumberFormatException e) {
            try { return java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(); }
            catch (Exception ignored) { return 0; }
        }
    }
    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }
    private static final class HttpsTransport implements Transport {
        @Override public Reply post(String token, byte[] body) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            c.setInstanceFollowRedirects(false); // Never forward a personal token to another origin.
            c.setConnectTimeout(15000); c.setReadTimeout(25000); c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            c.setRequestProperty("Accept", "application/json"); c.setDoOutput(true); c.setFixedLengthStreamingMode(body.length);
            try {
                try (OutputStream out = c.getOutputStream()) { out.write(body); }
                int code = c.getResponseCode(); String data = "";
                if (code == 200) {
                    try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) != -1) { if (out.size() + n > 24 * 1024 * 1024) throw new IOException("任务数据过大"); out.write(buf, 0, n); }
                        data = new String(out.toByteArray(), StandardCharsets.UTF_8);
                    }
                }
                return new Reply(code, data, c.getHeaderField("Retry-After"));
            } finally { c.disconnect(); }
        }
    }
}
