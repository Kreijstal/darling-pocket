package dev.kreijstal.darlingpocket;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The web engine of iOS apps' UIWebViews: Darling's darling-webhost, done
 * with Android's WebView (protocol in Darling's docs/darling-webhost.md).
 *
 * Each app connects to 127.0.0.1:7690 and asks for views; each view is an
 * Android WebView laid over the part of the app's window that its UIWebView
 * covers, hidden while another window is in front of that part. Pages reach
 * the app's Objective-C objects through the app's bridge port: runtime's
 * webhost/bridge.js, put first in every page this loads, carries the calls
 * through {@link Bridge}.
 */
final class WebHost {
    private static final String TAG = "DarlingPocket";
    static final int PORT = 7690;
    /** How often the views follow their windows. */
    private static final long PLACE_EVERY_MS = 100;
    private static final Set<String> BUILTIN_SCHEMES = new HashSet<>(java.util.Arrays.asList(
            "http", "https", "file", "about", "data", "blob", "javascript", "ftp"));

    private static WebHost instance;

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Where the views are, over the screen; the screen's activity shows it. */
    private final FrameLayout overlay;
    private final List<App> apps = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger nextPage = new AtomicInteger();
    private final String bridgeScript;
    private float scale = 1;

    private WebHost(Context context) {
        this.context = context.getApplicationContext();
        overlay = new FrameLayout(this.context);
        String script;
        try (InputStream in = context.getAssets().open("webhost/bridge.js")) {
            script = new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.e(TAG, "No webhost/bridge.js: pages cannot reach the app", e);
            script = null;
        }
        bridgeScript = script;
    }

    /** Starts listening for apps, once per process. */
    static synchronized WebHost start(Context context) {
        if (instance == null) {
            instance = new WebHost(context);
            instance.listen();
            instance.ui.post(instance.placer);
        }
        return instance;
    }

    /** The views, to lay over the screen. */
    View overlay() {
        return overlay;
    }

    /** Pixels per point of the screen the apps draw to. */
    void setScale(float scale) {
        this.scale = scale;
    }

    private void listen() {
        Thread thread = new Thread(() -> {
            try (ServerSocket listener = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))) {
                Log.i(TAG, "Web host listening on 127.0.0.1:" + PORT);
                for (;;) {
                    Socket socket = listener.accept();
                    socket.setTcpNoDelay(true);
                    App app = new App(socket);
                    apps.add(app);
                    app.start();
                }
            } catch (IOException e) {
                Log.e(TAG, "The web host cannot listen on " + PORT, e);
            }
        }, "webhost");
        thread.setDaemon(true);
        thread.start();
    }

    // Placement

    private final Runnable placer = new Runnable() {
        @Override
        public void run() {
            for (App app : apps)
                for (HostView view : app.views.values())
                    view.place();
            ui.postDelayed(this, PLACE_EVERY_MS);
        }
    };

    // An app

    /** One app's control connection. */
    private final class App {
        final Socket socket;
        final Map<Long, HostView> views = new ConcurrentHashMap<>();
        final Set<String> schemes = ConcurrentHashMap.newKeySet();
        final Map<Long, Waiter> requests = new ConcurrentHashMap<>();
        final AtomicLong nextRequest = new AtomicLong();
        volatile int pid;
        volatile int bridgePort;
        private OutputStream out;
        /** Messages go out on their own thread: Android forbids the network on the UI's. */
        private final LinkedBlockingQueue<byte[]> outgoing = new LinkedBlockingQueue<>();

        App(Socket socket) {
            this.socket = socket;
        }

        void start() throws IOException {
            out = socket.getOutputStream();
            Thread thread = new Thread(this::read, "webhost app");
            thread.setDaemon(true);
            thread.start();
            Thread writer = new Thread(this::write, "webhost app writer");
            writer.setDaemon(true);
            writer.start();
        }

        private void write() {
            try {
                while (true) {
                    byte[] message = outgoing.take();
                    if (message.length == 0)
                        return;
                    out.write(message);
                    if (outgoing.isEmpty())
                        out.flush();
                }
            } catch (IOException | InterruptedException e) {
                // the reader notices
            }
        }

        private void read() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isEmpty())
                        continue;
                    try {
                        handle(new JSONObject(line));
                    } catch (JSONException e) {
                        Log.w(TAG, "web host: not a message: " + line);
                    }
                }
            } catch (IOException e) {
                // the app is gone
            }
            apps.remove(this);
            outgoing.add(new byte[0]); // the writer's end
            for (Waiter waiter : requests.values())
                waiter.finish(null);
            ui.post(() -> {
                for (HostView view : views.values())
                    view.destroy();
                views.clear();
            });
        }

        void send(JSONObject message) {
            outgoing.add((message.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        }

        /** Sends an event and waits for the app's answer (policy, scheme), or null. */
        JSONObject ask(JSONObject event, long timeoutMs) {
            long id = nextRequest.incrementAndGet();
            Waiter waiter = new Waiter();
            requests.put(id, waiter);
            try {
                event.put("req", id);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            send(event);
            JSONObject answer = waiter.await(timeoutMs);
            requests.remove(id);
            return answer;
        }

        private void handle(JSONObject message) {
            String op = message.optString("op");
            long id = message.optLong("view", -1);
            switch (op) {
            case "hello":
                pid = message.optInt("pid");
                bridgePort = message.optInt("bridge");
                JSONArray list = message.optJSONArray("schemes");
                for (int i = 0; list != null && i < list.length(); i++)
                    registerScheme(list.optString(i));
                Log.i(TAG, "web host: app " + pid + " connected, bridge port " + bridgePort);
                return;
            case "scheme-register":
                registerScheme(message.optString("scheme"));
                return;
            case "policy":
            case "scheme": {
                Waiter waiter = requests.get(message.optLong("req"));
                if (waiter != null)
                    waiter.finish(message);
                return;
            }
            case "create":
                ui.post(() -> {
                    if (!views.containsKey(id))
                        views.put(id, new HostView(this, id, message.optString("ua", null)));
                });
                return;
            default:
                if ("load".equals(op))
                    registerScheme(Uri.parse(message.optString("url")).getScheme());
                ui.post(() -> {
                    HostView view = views.get(id);
                    if (view != null)
                        view.handle(op, message);
                });
            }
        }

        void registerScheme(String scheme) {
            if (scheme != null && !scheme.isEmpty() && !BUILTIN_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)))
                schemes.add(scheme.toLowerCase(Locale.ROOT));
        }
    }

    /** An answer one thread waits for and another gives. */
    private static final class Waiter {
        private JSONObject answer;
        private boolean done;

        synchronized void finish(JSONObject answer) {
            this.answer = answer;
            done = true;
            notifyAll();
        }

        synchronized JSONObject await(long timeoutMs) {
            long end = System.currentTimeMillis() + timeoutMs;
            while (!done) {
                long left = end - System.currentTimeMillis();
                if (left <= 0)
                    return null;
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    return null;
                }
            }
            return answer;
        }
    }

    // A view

    /** One of an app's UIWebViews' pages: a WebView over its window. */
    private final class HostView {
        final App app;
        final long id;
        final int page = nextPage.incrementAndGet();
        final WebView web;
        final Bridge bridge;
        long parent;
        int x, y, w, h;
        boolean visible;
        /** The WebView's, for fetches on its network thread: its settings are the UI thread's. */
        volatile String userAgent;
        private final int[] rect = new int[4];

        @SuppressLint({ "SetJavaScriptEnabled", "AddJavascriptInterface" })
        HostView(App app, long id, String userAgent) {
            this.app = app;
            this.id = id;
            web = new WebView(context);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
            // iOS's UIWebView shows a secure page's images from anywhere, and
            // Cydia's https pages show icons from its cydia: scheme.
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            if (userAgent != null && !userAgent.isEmpty())
                settings.setUserAgentString(userAgent);
            this.userAgent = settings.getUserAgentString();
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            bridge = new Bridge(this);
            web.addJavascriptInterface(bridge, "__dwkNative");
            web.setWebViewClient(new Client());
            web.setWebChromeClient(new Chrome());
            web.setVisibility(View.INVISIBLE);
            overlay.addView(web, new FrameLayout.LayoutParams(1, 1));
            event("created", "page", page);
        }

        void handle(String op, JSONObject message) {
            switch (op) {
            case "settings":
                String userAgent = message.optString("ua", null);
                if (userAgent != null) {
                    web.getSettings().setUserAgentString(userAgent);
                    this.userAgent = web.getSettings().getUserAgentString();
                }
                break;
            case "embed":
                parent = message.optLong("parent");
                x = message.optInt("x");
                y = message.optInt("y");
                w = message.optInt("w");
                h = message.optInt("h");
                visible = message.optBoolean("visible");
                place();
                break;
            case "load":
                web.loadUrl(message.optString("url"));
                break;
            case "loadHTML":
                loadHTML(message.optString("html"), message.optString("base", null));
                break;
            case "loadData": {
                byte[] data = Base64.decode(message.optString("data"), Base64.DEFAULT);
                String mime = message.optString("mime", "text/html");
                String encoding = message.optString("encoding", null);
                String base = message.optString("base", null);
                if (mime.startsWith("text/html")) {
                    Charset charset = charset(encoding, StandardCharsets.UTF_8);
                    loadHTML(new String(data, charset), base);
                } else {
                    web.loadDataWithBaseURL(base, Base64.encodeToString(data, Base64.NO_WRAP), mime, "base64", null);
                }
                break;
            }
            case "reload":
                web.reload();
                break;
            case "stop":
                web.stopLoading();
                break;
            case "back":
                web.goBack();
                break;
            case "forward":
                web.goForward();
                break;
            case "destroy":
                app.views.remove(id);
                destroy();
                break;
            default:
                Log.d(TAG, "web host: ignoring " + op);
            }
        }

        private void loadHTML(String html, String base) {
            web.loadDataWithBaseURL(base, inject(html), "text/html", "utf-8", null);
        }

        /** The page with the bridge first in it. */
        String inject(String html) {
            if (bridgeScript == null)
                return html;
            String tag = "<script>" + bridgeScript.replace("__DWK_PAGE__", Integer.toString(page)) + "</script>";
            int head = indexOfTag(html, "head");
            if (head >= 0)
                return html.substring(0, head) + tag + html.substring(head);
            int start = indexOfTag(html, "html");
            if (start >= 0)
                return html.substring(0, start) + tag + html.substring(start);
            // Before the doctype would put the page in quirks mode.
            int doctype = html.regionMatches(true, 0, "<!doctype", 0, 9) ? html.indexOf('>') + 1 : 0;
            return html.substring(0, doctype) + tag + html.substring(doctype);
        }

        /** Over its part of its window, when that shows. */
        void place() {
            boolean shows = false;
            if (visible && w > 0 && h > 0 && app.pid != 0) {
                rect[0] = Math.round(x * scale);
                rect[1] = Math.round(y * scale);
                rect[2] = Math.round(w * scale);
                rect[3] = Math.round(h * scale);
                shows = PocketDisplay.windowPart(app.pid, (int) parent, rect) == 1;
            }
            if (shows) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) web.getLayoutParams();
                if (params.leftMargin != rect[0] || params.topMargin != rect[1] || params.width != rect[2]
                        || params.height != rect[3]) {
                    params.leftMargin = rect[0];
                    params.topMargin = rect[1];
                    params.width = rect[2];
                    params.height = rect[3];
                    web.setLayoutParams(params);
                    Log.i(TAG, "web host: view " + id + " at " + x + "," + y + " " + w + "x" + h + " -> "
                            + rect[0] + "," + rect[1] + " " + rect[2] + "x" + rect[3]);
                }
            }
            int visibility = shows ? View.VISIBLE : View.INVISIBLE;
            if (web.getVisibility() != visibility)
                web.setVisibility(visibility);
        }

        void destroy() {
            bridge.close();
            overlay.removeView(web);
            web.destroy();
        }

        /** An event about this view; call on the UI thread (it reads the history). */
        void event(String name, Object... pairs) {
            JSONObject event = new JSONObject();
            try {
                event.put("ev", name);
                event.put("view", id);
                event.put("back", web.canGoBack());
                event.put("forward", web.canGoForward());
                for (int i = 0; i + 1 < pairs.length; i += 2)
                    event.put((String) pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            app.send(event);
        }

        private final class Client extends WebViewClient {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("about:") || url.startsWith("data:"))
                    return false;
                JSONObject event = new JSONObject();
                try {
                    event.put("ev", "policy");
                    event.put("view", id);
                    event.put("back", web.canGoBack());
                    event.put("forward", web.canGoForward());
                    event.put("url", url);
                    event.put("method", request.getMethod());
                    event.put("type", request.hasGesture() ? "link" : "other");
                    event.put("main", request.isForMainFrame());
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
                // The app decides; it should not take long, and the screen waits.
                JSONObject answer = app.ask(event, 5000);
                boolean use = answer == null || answer.optBoolean("use");
                if (!use)
                    return true;
                // An app's scheme only loads through shouldInterceptRequest.
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                // Any scheme the web doesn't have is the app's to load: an app
                // can serve one (an NSURLProtocol) without loading a page from
                // it, as Cydia's https pages show cydia://package-icon images.
                if (app.schemes.contains(scheme) || !scheme.isEmpty() && !BUILTIN_SCHEMES.contains(scheme))
                    return fromApp(uri.toString());
                if (request.isForMainFrame() && "GET".equals(request.getMethod())
                        && (scheme.equals("http") || scheme.equals("https")))
                    return fetchWithBridge(request);
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                event("started", "url", url);
                event("committed", "url", url, "mime", "text/html");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                event("finished");
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame())
                    return;
                event("failed", "url", request.getUrl().toString(), "error", String.valueOf(error.getDescription()),
                        "code", error.getErrorCode(), "provisional", true);
            }
        }

        private final class Chrome extends WebChromeClient {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                event("title", "title", title);
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                event("alert", "message", message);
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                    JsPromptResult result) {
                result.confirm(defaultValue);
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                event("close");
            }
        }

        /** A request for a scheme the app serves: it loads it itself. */
        private WebResourceResponse fromApp(String url) {
            JSONObject event = new JSONObject();
            try {
                event.put("ev", "scheme");
                event.put("url", url);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            JSONObject answer = app.ask(event, 30000);
            if (answer == null || answer.has("error")) {
                byte[] text = (answer == null ? "no answer" : answer.optString("error")).getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null,
                        new ByteArrayInputStream(text));
            }
            byte[] data = Base64.decode(answer.optString("data"), Base64.DEFAULT);
            String mime = answer.optString("mime", "application/octet-stream");
            int status = answer.optInt("status", 200);
            if (mime.startsWith("text/html"))
                data = inject(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse(mime, mime.startsWith("text/") ? "utf-8" : null, status,
                    status == 200 ? "OK" : "Status " + status, headers, new ByteArrayInputStream(data));
        }

        /**
         * A page, fetched here so the bridge can go first in it. Android's
         * WebView has no way to run a script before a page's own; this is it.
         * Anything this cannot do (a redirect, an error) goes back to the
         * WebView, which then loads the page without the bridge.
         */
        private WebResourceResponse fetchWithBridge(WebResourceRequest request) {
            String url = request.getUrl().toString();
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet())
                    connection.setRequestProperty(header.getKey(), header.getValue());
                connection.setRequestProperty("User-Agent", userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null)
                    connection.setRequestProperty("Cookie", cookies);
                int status = connection.getResponseCode();
                List<String> setCookies = connection.getHeaderFields().get("Set-Cookie");
                if (setCookies != null)
                    for (String cookie : setCookies)
                        CookieManager.getInstance().setCookie(url, cookie);
                if (status >= 300 && status < 400) {
                    connection.disconnect();
                    return null;
                }
                String type = connection.getContentType();
                String mime = type == null ? "text/html" : type.split(";")[0].trim();
                Charset charset = charset(parameter(type, "charset"), StandardCharsets.UTF_8);
                InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                byte[] bytes = body == null ? new byte[0] : readAll(body);
                Map<String, String> headers = new HashMap<>();
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    String name = header.getKey();
                    if (name == null || header.getValue().isEmpty())
                        continue;
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.equals("content-length") || lower.equals("content-encoding")
                            || lower.equals("transfer-encoding") || lower.equals("set-cookie")
                            || lower.equals("content-type"))
                        continue;
                    headers.put(name, header.getValue().get(0));
                }
                if (mime.equals("text/html") || mime.equals("application/xhtml+xml"))
                    bytes = inject(new String(bytes, charset)).getBytes(charset);
                String reason = connection.getResponseMessage();
                return new WebResourceResponse(mime, charset.name(), status,
                        reason == null || reason.isEmpty() ? "OK" : reason, headers, new ByteArrayInputStream(bytes));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "web host: cannot fetch " + url + " for the bridge: " + e);
                return null;
            } finally {
                if (connection != null)
                    connection.disconnect();
            }
        }
    }

    // The bridge

    /**
     * A page's connection to its app's bridge, used by bridge.js through
     * {@code window.__dwkNative}. Requests either way are lines of JSON;
     * replies carry {@code re}, the request's id. All of a page's calls come
     * on one thread, and nest: {@link #request} and {@link #answer} return
     * {@code {"reply": ...}} with the awaited reply, or {@code {"serve": ...}}
     * with a request of the app's, which the page answers with {@link #answer}.
     */
    private final class Bridge {
        private final HostView view;
        private final LinkedBlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        private final Map<Long, JSONObject> replies = new HashMap<>();
        private final ArrayDeque<Long> waiting = new ArrayDeque<>();
        private Socket socket;
        private OutputStream out;
        private long nextId;
        private volatile boolean closed;
        private volatile boolean drainPosted;

        Bridge(HostView view) {
            this.view = view;
        }

        private boolean connect() {
            if (socket != null)
                return !closed;
            if (view.app.bridgePort == 0)
                return false;
            try {
                socket = new Socket(InetAddress.getByName("127.0.0.1"), view.app.bridgePort);
                socket.setTcpNoDelay(true);
                out = socket.getOutputStream();
            } catch (IOException e) {
                Log.w(TAG, "web host: cannot reach the app's bridge on " + view.app.bridgePort + ": " + e);
                closed = true;
                return false;
            }
            Thread reader = new Thread(this::read, "webhost bridge");
            reader.setDaemon(true);
            reader.start();
            return true;
        }

        private void read() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isEmpty())
                        continue;
                    JSONObject message;
                    try {
                        message = new JSONObject(line);
                    } catch (JSONException e) {
                        continue;
                    }
                    inbox.add(message);
                    // A request while the page waits for nothing: the page serves it.
                    if (!message.has("re"))
                        drainLater();
                }
            } catch (IOException e) {
                // the app is gone
            }
            closed = true;
            inbox.add(new JSONObject());
        }

        private void drainLater() {
            synchronized (this) {
                if (!waiting.isEmpty() || drainPosted)
                    return;
                drainPosted = true;
            }
            ui.post(() -> {
                drainPosted = false;
                view.web.evaluateJavascript("window.__dwk && window.__dwk.drain()", null);
            });
        }

        private void write(String json) {
            try {
                out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                closed = true;
            }
        }

        @JavascriptInterface
        public String request(String json) {
            if (!connect())
                return "{}";
            long id;
            try {
                JSONObject request = new JSONObject(json);
                synchronized (this) {
                    id = ++nextId;
                    waiting.push(id);
                }
                request.put("id", id);
                write(request.toString());
            } catch (JSONException e) {
                return "{}";
            }
            return await(id);
        }

        @JavascriptInterface
        public String answer(String json) {
            write(json);
            Long id;
            synchronized (this) {
                id = waiting.peek();
            }
            return id == null ? "{}" : await(id);
        }

        /** The app's next request while the page is idle, or null. */
        @JavascriptInterface
        public String poll() {
            JSONObject message;
            while ((message = inbox.poll()) != null) {
                if (message.has("re")) {
                    synchronized (this) {
                        replies.put(message.optLong("re"), message);
                    }
                    continue;
                }
                if (message.length() == 0)
                    return null; // closed
                return message.toString();
            }
            return null;
        }

        @JavascriptInterface
        public void reply(String json) {
            write(json);
        }

        private String await(long id) {
            for (;;) {
                synchronized (this) {
                    JSONObject reply = replies.remove(id);
                    if (reply != null) {
                        waiting.remove(id);
                        return wrap("reply", reply);
                    }
                }
                JSONObject message = null;
                if (!closed || !inbox.isEmpty()) {
                    try {
                        message = inbox.poll(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        message = null;
                    }
                }
                // Nothing for a minute, or the app is gone: the page goes on without an answer.
                if (message == null || message.length() == 0) {
                    synchronized (this) {
                        waiting.remove(id);
                    }
                    return "{}";
                }
                if (message.has("re")) {
                    synchronized (this) {
                        replies.put(message.optLong("re"), message);
                    }
                    continue;
                }
                return wrap("serve", message);
            }
        }

        private String wrap(String key, JSONObject message) {
            try {
                return new JSONObject().put(key, message).toString();
            } catch (JSONException e) {
                return "{}";
            }
        }

        void close() {
            closed = true;
            try {
                if (socket != null)
                    socket.close();
            } catch (IOException e) {
                // closed anyway
            }
        }
    }

    // Helpers

    private static int indexOfTag(String html, String tag) {
        String lower = html.length() > 4096 ? html.substring(0, 4096).toLowerCase(Locale.ROOT) : html.toLowerCase(Locale.ROOT);
        int at = lower.indexOf("<" + tag);
        if (at < 0)
            return -1;
        int end = lower.indexOf('>', at);
        return end < 0 ? -1 : end + 1;
    }

    private static String parameter(String contentType, String name) {
        if (contentType == null)
            return null;
        for (String part : contentType.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].trim().equalsIgnoreCase(name))
                return pair[1].trim().replace("\"", "");
        }
        return null;
    }

    private static Charset charset(String name, Charset fallback) {
        try {
            return name == null || name.isEmpty() ? fallback : Charset.forName(name);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 16];
        for (int n; (n = in.read(buffer)) > 0;)
            out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
