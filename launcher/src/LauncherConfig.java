import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class LauncherConfig {

    /** Simple DTO returned by load(). */
    public static class ConfigData {
        public final String websiteExe;
        public final String serverJar;
        public final String clientJar;

        public ConfigData(String websiteExe, String serverJar, String clientJar) {
            this.websiteExe = websiteExe;
            this.serverJar = serverJar;
            this.clientJar = clientJar;
        }
    }

    private LauncherConfig() {}

    /**
     * Persists the three paths to a .properties file.
     */
    public static void save(Path file, String websiteExe, String serverJar, String clientJar)
            throws Exception {
        Properties props = new Properties();
        props.setProperty("websiteExe", websiteExe);
        props.setProperty("serverJar", serverJar);
        props.setProperty("clientJar", clientJar);

        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(writer, "RS2 Progressive Launcher");
        }
    }

    /**
     * Loads the three paths from a .properties file.
     * Returns a ConfigData with empty strings if the file does not exist.
     */
    public static ConfigData load(Path file) throws Exception {
        if (!Files.exists(file)) {
            return new ConfigData("", "", "");
        }

        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }

        return new ConfigData(
                props.getProperty("websiteExe", ""),
                props.getProperty("serverJar", ""),
                props.getProperty("clientJar", "")
        );
    }
}