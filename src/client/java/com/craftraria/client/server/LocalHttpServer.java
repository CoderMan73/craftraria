package com.craftraria.client.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The minimal local HTTP server that serves the game directory to the embedded Chromium.
 *
 * <p>Two facts make it necessary rather than optional. First, {@code file://} pages cannot use
 * OPFS or SharedArrayBuffer, and the wasm runtime needs both. Second, dotnet wasm and emscripten
 * refuse to run without cross-origin isolation, so every response carries the same two headers
 * upstream's {@code public/_headers} declares: {@code Cross-Origin-Opener-Policy: same-origin} and
 * {@code Cross-Origin-Embedder-Policy: require-corp}. A {@code Cross-Origin-Resource-Policy:
 * cross-origin} companion is kept from craftlatro's proven setup, and {@code index.html} is
 * marked {@code no-store} because the served paths are version-pinned and a stale loader page is
 * the one cache failure that cannot be diagnosed from the mod's side.
 *
 * <p>The port's ctx path, the thread factory and the directory-traversal guard are ported
 * unchanged from craftlatro; the love.js {@code ?g=} cache-bust rewrite is not, because this project
 * versions served URLs itself instead.
 */
public class LocalHttpServer {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftraria");
	public static final int DEFAULT_PORT = 18080;
	private static final int MAX_PORT_ATTEMPTS = 10;
	/**
	 * Where the M1 probe page posts its findings. The handler writes the body next to the served
	 * files, so a probe run's outcome is readable from a terminal ({@code GET /probe-report}) or the
	 * log instead of only from a screenshot.
	 */
	private static final String PROBE_REPORT_PATH = "/probe-report";
	private static final String PROBE_REPORT_FILE = "probe-report.json";

	private HttpServer server;
	private final Path serveDirectory;
	private final Map<String, Path> aliases = new LinkedHashMap<>();
	private int port;
	private boolean running;
	private ExecutorService executor;

	public LocalHttpServer(Path serveDirectory) {
		this.serveDirectory = serveDirectory.normalize().toAbsolutePath();
	}

	/**
	 * Serves another directory under a URL prefix.
	 *
	 * <p>The pinned port's boot script imports its runtime by absolute URL -
	 * {@code import("/_framework/dotnet.js")} inside its bundled module - while the mod keeps the
	 * extracted build and the mod-written page in separate directories. Rather than patch the game's
	 * code or copy the runtime next to the page, this maps a URL prefix onto another directory on
	 * disk: the build is the server's root, and the mod's own pages are aliased under it. Both roots
	 * are traversal-guarded independently, and an alias can never shadow a file that really exists in
	 * the primary root - resolution checks the primary root first.
	 */
	public void addAlias(String urlPrefix, Path directory) {
		aliases.put(normalizePrefix(urlPrefix), directory.normalize().toAbsolutePath());
	}

	/** Aliases are matched with their trailing slash, so "/www" and "/www/" mean the same thing. */
	private static String normalizePrefix(String urlPrefix) {
		String prefix = urlPrefix.startsWith("/") ? urlPrefix : "/" + urlPrefix;
		return prefix.endsWith("/") ? prefix : prefix + "/";
	}

	public boolean start() {
		return start(DEFAULT_PORT);
	}

	public boolean start(int port) {
		if (running) {
			LOGGER.warn("LocalHttpServer is already running");
			return false;
		}

		for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
			int candidatePort = port + attempt;
			try {
				server = HttpServer.create(new InetSocketAddress("localhost", candidatePort), 0);
				server.createContext("/", new CoopCoepFileHandler(serveDirectory, aliases));
				server.createContext(PROBE_REPORT_PATH, new ProbeReportHandler(serveDirectory));
				ThreadFactory threadFactory = new ThreadFactory() {
					private final AtomicInteger threadNumber = new AtomicInteger(1);

					@Override
					public Thread newThread(Runnable r) {
						Thread t = new Thread(r, "craftraria-http-" + threadNumber.getAndIncrement());
						t.setDaemon(true);
						return t;
					}
				};
				server.setExecutor(Executors.newFixedThreadPool(8, threadFactory));
				server.start();
				running = true;
				// Read the port back from the bound address rather than assuming the candidate:
				// binding port 0 picks an ephemeral port, and recording the candidate would hand out
				// a URL for a port nothing is listening on.
				this.port = server.getAddress().getPort();
				LOGGER.info("Local HTTP server started on port {}", this.port);
				return true;
			} catch (IOException e) {
				LOGGER.warn("Port {} unavailable, trying next...", candidatePort);
			}
		}

		LOGGER.error("Failed to start local HTTP server on ports {}-{}", port, port + MAX_PORT_ATTEMPTS - 1);
		return false;
	}

	public void stop() {
		if (server != null && running) {
			server.stop(0);
			running = false;
			LOGGER.info("Local HTTP server stopped");
		}
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	public String getBaseUrl() {
		return "http://localhost:" + port + "/";
	}

	public boolean isRunning() {
		return running;
	}

	public int getPort() {
		return port;
	}

	private static class CoopCoepFileHandler implements HttpHandler {

		private final Path rootDir;
		private final Map<String, Path> aliases;

		public CoopCoepFileHandler(Path rootDir, Map<String, Path> aliases) {
			this.rootDir = rootDir.normalize().toAbsolutePath();
			this.aliases = aliases;
		}

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			String requestPath = exchange.getRequestURI().getPath();
			String path = stripLeadingSlash(requestPath);

			// The primary root wins: an alias only answers for what the root does not have, so a
			// file added to the build never hides behind an alias.
			Path root = rootDir;
			if (!Files.isRegularFile(resolveWithin(rootDir, path))) {
				for (Map.Entry<String, Path> alias : aliases.entrySet()) {
					if (requestPath.startsWith(alias.getKey())) {
						String remainder = stripLeadingSlash(requestPath.substring(alias.getKey().length()));
						Path aliased = resolveWithin(alias.getValue(), remainder);
						if (Files.isRegularFile(aliased)) {
							root = alias.getValue();
							path = remainder;
							break;
						}
					}
				}
			}

			if (isEmpty(path)) {
				path = "index.html";
			}

			Path filePath = resolveWithin(root, path);
			if (!filePath.startsWith(root)) {
				sendError(exchange, 403, "Forbidden");
				return;
			}

			if (Files.notExists(filePath) || Files.isDirectory(filePath)) {
				// Try appending .html
				Path htmlFile = filePath.resolveSibling(filePath.getFileName() + ".html").normalize().toAbsolutePath();
				if (!htmlFile.startsWith(root) || !Files.exists(htmlFile)) {
					sendError(exchange, 404, "Not Found");
					return;
				}
				filePath = htmlFile;
			}

			byte[] content = Files.readAllBytes(filePath);
			String mimeType = probeContentType(filePath);

			// Set COOP/COEP headers required for WebAssembly/SharedArrayBuffer in the wasm runtime
			exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
			exchange.getResponseHeaders().set("Cross-Origin-Embedder-Policy", "require-corp");
			exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "cross-origin");

			// index.html is regenerated whenever the served version changes; never let the browser
			// cache a stale copy carrying an old version key.
			if (filePath.endsWith("index.html")) {
				exchange.getResponseHeaders().set("Cache-Control", "no-store");
			}
			exchange.getResponseHeaders().set("Content-Type", mimeType);

			exchange.sendResponseHeaders(200, content.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(content);
			}
		}

		private static String stripLeadingSlash(String path) {
			if (path.startsWith("/")) {
				path = path.substring(1);
			}
			return path;
		}

		private static boolean isEmpty(String path) {
			return path.isEmpty() || path.endsWith("/");
		}

		/**
		 * Resolves a request path inside a root and normalizes it; the caller checks the result still
		 * starts with that root. On its own, normalization is the guard: {@code ../} and percent-encoded
		 * variants collapse before they are used.
		 */
		private static Path resolveWithin(Path root, String path) {
			return root.resolve(path).normalize().toAbsolutePath();
		}

		private String probeContentType(Path file) {
			String fileName = file.getFileName().toString().toLowerCase();
			if (fileName.endsWith(".wasm")) {
				return "application/wasm";
			}
			if (fileName.endsWith(".js") || fileName.endsWith(".mjs")) {
				return "application/javascript";
			}
			if (fileName.endsWith(".css")) {
				return "text/css";
			}
			if (fileName.endsWith(".html")) {
				return "text/html";
			}
			if (fileName.endsWith(".json")) {
				return "application/json";
			}
			if (fileName.endsWith(".tar")) {
				return "application/x-tar";
			}
			if (fileName.endsWith(".lua")) {
				return "text/plain";
			}
			try {
				String type = Files.probeContentType(file);
				return type != null ? type : "application/octet-stream";
			} catch (IOException e) {
				return "application/octet-stream";
			}
		}

		private void sendError(HttpExchange exchange, int code, String message) throws IOException {
			exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
			exchange.getResponseHeaders().set("Cross-Origin-Embedder-Policy", "require-corp");
			exchange.getResponseHeaders().set("Content-Type", "text/plain");
			exchange.sendResponseHeaders(code, message.length());
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(message.getBytes());
			}
		}
	}

	/**
	 * Receives the probe page's findings and stores them as {@code probe-report.json} in the served
	 * directory, and serves them back on GET. This is the only write endpoint the mod exposes: it
	 * exists so the environment probes leave a durable artifact behind them.
	 */
	private static class ProbeReportHandler implements HttpHandler {

		private final Path reportFile;

		public ProbeReportHandler(Path serveDirectory) {
			this.reportFile = serveDirectory.normalize().toAbsolutePath().resolve(PROBE_REPORT_FILE);
		}

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				try (InputStream requestBody = exchange.getRequestBody()) {
					byte[] body = requestBody.readAllBytes();
					Files.write(reportFile, body);
					LOGGER.info("Probe report written to {} ({} bytes)", reportFile, body.length);
				}
				sendText(exchange, 200, "text/plain", "ok".getBytes());
				return;
			}

			if (Files.isRegularFile(reportFile)) {
				sendText(exchange, 200, "application/json", Files.readAllBytes(reportFile));
				return;
			}

			sendText(exchange, 404, "text/plain", "No probe report found".getBytes());
		}

		private void sendText(HttpExchange exchange, int code, String contentType, byte[] content) throws IOException {
			exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
			exchange.getResponseHeaders().set("Cross-Origin-Embedder-Policy", "require-corp");
			exchange.getResponseHeaders().set("Cache-Control", "no-store");
			exchange.getResponseHeaders().set("Content-Type", contentType);
			exchange.sendResponseHeaders(code, content.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(content);
			}
		}
	}
}
