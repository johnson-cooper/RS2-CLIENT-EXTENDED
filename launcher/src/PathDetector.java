import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PathDetector {

    /** Simple DTO holding the three auto-detected paths (may be null if not found). */
    public static class DetectedPaths {
        public final Path websiteExe;
        public final Path serverJar;
        public final Path clientJar;

        public DetectedPaths(Path websiteExe, Path serverJar, Path clientJar) {
            this.websiteExe = websiteExe;
            this.serverJar = serverJar;
            this.clientJar = clientJar;
        }
    }

    private final Consumer<String> logger;

    public PathDetector(Consumer<String> logger) {
        this.logger = logger;
    }

    /**
     * Detects the three standard paths relative to rootDir and returns them as a DTO.
     * Fields are null when nothing was found.
     */
    public DetectedPaths detectDefaults(Path rootDir) {
        Path websiteExe = null;
        Path serverJar = null;
        Path clientJar = null;

        Path webCandidate = rootDir.resolve("web").resolve("unicontroller.exe");
        if (Files.exists(webCandidate)) {
            websiteExe = webCandidate;
        }

        serverJar = findBestJar(rootDir.resolve("server"), "server");
        clientJar = findBestJar(rootDir.resolve("client"), "client");

        return new DetectedPaths(websiteExe, serverJar, clientJar);
    }

    public Path findBestJar(Path folder, String preferredHint) {
        if (!Files.isDirectory(folder)) return null;

        try (Stream<Path> stream = Files.list(folder)) {
            List<Path> jars = stream
                    .filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .collect(Collectors.toList());

            if (jars.isEmpty()) return null;

            String hint = preferredHint == null ? "" : preferredHint.toLowerCase();
            for (Path jar : jars) {
                if (jar.getFileName().toString().toLowerCase().contains(hint)) return jar;
            }

            if (jars.size() == 1) return jars.get(0);

            return jars.stream()
                    .max(Comparator.comparingLong(this::lastModifiedSafe))
                    .orElse(jars.get(0));

        } catch (IOException e) {
            logger.accept("Auto-detect failed for " + folder + ": " + e.getMessage());
            return null;
        }
    }

    public Path findJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        Path bin = Paths.get(javaHome, "bin");

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Path javaw = bin.resolve("javaw.exe");
            if (Files.exists(javaw)) return javaw;
            Path java = bin.resolve("java.exe");
            if (Files.exists(java)) return java;
        } else {
            Path java = bin.resolve("java");
            if (Files.exists(java)) return java;
        }

        return bin.resolve("java");
    }

    public boolean isBlankOrMissing(String path) {
        if (path == null || path.trim().isEmpty()) return true;
        return !Files.exists(Paths.get(path.trim()));
    }

    public Path requireExistingFile(String path, String label) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " path is empty");
        }
        Path p = Paths.get(path.trim());
        if (!Files.exists(p)) {
            throw new IllegalArgumentException(label + " not found: " + p);
        }
        return p;
    }

    public long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}