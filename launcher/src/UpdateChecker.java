import javax.swing.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.function.Consumer;

/**
 * Checks the latest GitHub Release for johnson-cooper/RS2-CLIENT-EXTENDED,
 * compares it against the local version.txt bundled inside the running jar,
 * and offers to download launcher.jar + agent.jar if a newer release exists.
 *
 * ── How to set up version tracking ──────────────────────────────────────────
 * 1. Create a file called  version.txt  at the root of your source tree.
 *    Contents: just the version tag, e.g.   v1.0.0
 * 2. Make sure your build tool (Maven/Gradle/jar command) includes it at the
 *    root of the produced jar so it is readable via getResourceAsStream.
 * 3. When cutting a GitHub Release, name the tag the same value (e.g. v1.0.0)
 *    and attach  launcher.jar  and  agent.jar  as release assets.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class UpdateChecker {

    // ── Config ────────────────────────────────────────────────────────────────
    private static final String API_URL =
            "https://api.github.com/repos/johnson-cooper/RS2-CLIENT-EXTENDED/releases/latest";

    /** Asset names that will be downloaded and placed into the client folder. */
    private static final String[] ASSETS_TO_UPDATE = { "launcher.jar", "agent.jar" };

    // ── State returned to the caller ──────────────────────────────────────────
    public enum Status { UP_TO_DATE, UPDATE_AVAILABLE, ERROR }

    public static class CheckResult {
        public final Status status;
        public final String localVersion;
        public final String remoteVersion;
        public final String releaseNotes;   // body of the GitHub release
        public final String errorMessage;

        private CheckResult(Status status, String local, String remote,
                            String notes, String error) {
            this.status        = status;
            this.localVersion  = local;
            this.remoteVersion = remote;
            this.releaseNotes  = notes;
            this.errorMessage  = error;
        }

        static CheckResult upToDate(String v) {
            return new CheckResult(Status.UP_TO_DATE, v, v, null, null);
        }
        static CheckResult available(String local, String remote, String notes) {
            return new CheckResult(Status.UPDATE_AVAILABLE, local, remote, notes, null);
        }
        static CheckResult error(String msg) {
            return new CheckResult(Status.ERROR, null, null, null, msg);
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────
    private final Consumer<String> logger;
    /** Directory that contains launcher.jar and agent.jar (the client folder). */
    private final Path clientDir;

    public UpdateChecker(Consumer<String> logger, Path clientDir) {
        this.logger    = logger;
        this.clientDir = clientDir;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Checks GitHub for a newer release.  Safe to call from any thread.
     * Never throws — all errors are wrapped in CheckResult.
     */
    public CheckResult check() {
        try {
            String local  = readLocalVersion();
            String remote = fetchLatestTag();

            if (local.equalsIgnoreCase(remote)) {
                return CheckResult.upToDate(local);
            }

            String notes = fetchReleaseBody();
            return CheckResult.available(local, remote, notes);

        } catch (Exception e) {
            return CheckResult.error(e.getMessage());
        }
    }

    /**
     * Downloads all ASSETS_TO_UPDATE from the latest release into clientDir,
     * replacing files atomically using a .tmp → rename pattern.
     * Calls logger with progress updates throughout.
     * Safe to call from any thread.
     */
    public boolean downloadUpdate() {
        try {
            String tag        = fetchLatestTag();
            String assetsJson = fetchAssetsJson(tag);

            for (String assetName : ASSETS_TO_UPDATE) {
                String downloadUrl = parseAssetUrl(assetsJson, assetName);
                if (downloadUrl == null) {
                    logger.accept("[Updater] WARNING: asset not found in release: " + assetName);
                    continue;
                }
                downloadAsset(assetName, downloadUrl);
            }

            logger.accept("[Updater] All files updated. Please restart the launcher.");
            writeLocalVersion(tag);
            return true;

        } catch (Exception e) {
            logger.accept("[Updater] Download failed: " + e.getMessage());
            return false;
        }
    }

    // ── Version helpers ───────────────────────────────────────────────────────

    /** Reads version.txt bundled at the root of this jar. Falls back to "unknown". */
    private String readLocalVersion() {
        try (InputStream is = UpdateChecker.class.getResourceAsStream("/version.txt")) {
            if (is == null) return "unknown";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line = br.readLine();
                return line == null ? "unknown" : line.trim();
            }
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * After a successful download, write the new tag to a version.txt on disk
     * next to the launcher so subsequent launches pick up the new version even
     * if the jar hasn't been replaced yet.
     */
    private void writeLocalVersion(String tag) {
        try {
            Path versionFile = clientDir.resolve("version.txt");
            Files.write(versionFile, tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.accept("[Updater] Could not write version.txt: " + e.getMessage());
        }
    }

    // ── GitHub API helpers ────────────────────────────────────────────────────

    private String fetchLatestTag() throws IOException {
        String json = httpGet(API_URL);
        return extractJsonString(json, "tag_name");
    }

    private String fetchReleaseBody() throws IOException {
        String json = httpGet(API_URL);
        return extractJsonString(json, "body");
    }

    /**
     * Returns the full release JSON so we can parse asset download URLs.
     * Uses the assets_url field to hit the dedicated assets endpoint.
     */
    private String fetchAssetsJson(String tag) throws IOException {
        // The latest release JSON already contains the assets array inline
        return httpGet(API_URL);
    }

    /**
     * Finds the browser_download_url for a named asset inside the GitHub
     * release JSON.  Uses simple string search — no external JSON library needed.
     */
    private String parseAssetUrl(String json, String assetName) {
        // Find the block containing the asset name
        int nameIdx = json.indexOf("\"" + assetName + "\"");
        if (nameIdx == -1) return null;

        // browser_download_url appears after the name in the same asset object
        String marker = "\"browser_download_url\":\"";
        int urlStart = json.indexOf(marker, nameIdx);
        if (urlStart == -1) return null;
        urlStart += marker.length();

        int urlEnd = json.indexOf("\"", urlStart);
        if (urlEnd == -1) return null;

        return json.substring(urlStart, urlEnd);
    }

    /** Downloads a single asset to clientDir, replacing any existing file atomically. */
    private void downloadAsset(String assetName, String downloadUrl) throws IOException {
        logger.accept("[Updater] Downloading " + assetName + " ...");

        Path dest = clientDir.resolve(assetName);
        Path tmp  = clientDir.resolve(assetName + ".tmp");

        URL url = new URL(downloadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "application/octet-stream");
        conn.setRequestProperty("User-Agent", "RS2-Launcher-Updater");
        conn.setInstanceFollowRedirects(true);
        conn.connect();

        long total     = conn.getContentLengthLong();
        long downloaded = 0;
        int  lastPct   = -1;

        try (InputStream in  = conn.getInputStream();
             OutputStream out = Files.newOutputStream(tmp)) {

            byte[] buf = new byte[8192];
            int    read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                downloaded += read;

                if (total > 0) {
                    int pct = (int) (downloaded * 100 / total);
                    if (pct != lastPct && pct % 10 == 0) {
                        logger.accept("[Updater] " + assetName + " — " + pct + "%");
                        lastPct = pct;
                    }
                }
            }
        }

        // Atomic replace: move tmp over the real file
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        logger.accept("[Updater] " + assetName + " updated ✓");
    }

    // ── Minimal HTTP + JSON helpers ───────────────────────────────────────────

    private String httpGet(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "RS2-Launcher-Updater");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("GitHub API returned HTTP " + code + " for " + urlString);
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    /**
     * Extracts a JSON string value by key from a flat or nested JSON blob.
     * Handles escaped quotes and unicode — good enough for GitHub API responses
     * without pulling in a JSON library.
     */
    private String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":";
        int idx = json.indexOf(marker);
        if (idx == -1) return "";
        idx += marker.length();

        // Skip whitespace
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;

        if (idx >= json.length() || json.charAt(idx) != '"') return "";
        idx++; // skip opening quote

        StringBuilder sb = new StringBuilder();
        while (idx < json.length()) {
            char c = json.charAt(idx++);
            if (c == '\\' && idx < json.length()) {
                char esc = json.charAt(idx++);
                switch (esc) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    default:   sb.append(esc);  break;
                }
            } else if (c == '"') {
                break; // closing quote
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── UI helpers (must be called from EDT or wrapped in invokeLater) ─────────

    /**
     * Shows the update-available dialog.
     * Returns true if the user clicked "Update Now".
     */
    public static boolean promptUser(JFrame parent, CheckResult result) {
        String notes = result.releaseNotes != null && !result.releaseNotes.trim().isEmpty()
                ? "\n\nRelease notes:\n" + result.releaseNotes
                : "";

        String msg = "A new version is available!\n\n"
                + "  Current:  " + result.localVersion + "\n"
                + "  Latest:   " + result.remoteVersion
                + notes
                + "\n\nDownload and install now?";

        int choice = JOptionPane.showConfirmDialog(
                parent, msg, "Update Available",
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);

        return choice == JOptionPane.YES_OPTION;
    }
}