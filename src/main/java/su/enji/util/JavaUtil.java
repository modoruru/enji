package su.enji.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaUtil {

    private JavaUtil() {}

    public static boolean checkJavaInstallation(String path) {
        try {
            Process process = new ProcessBuilder()
                    .command(path, "--version")
                    .start();

            try (InputStream inputStream = process.getInputStream()) {
                String line = readStream(inputStream);
                return line.contains("java") ||
                        line.contains("jre") ||
                        line.contains("jdk") ||
                        line.contains("openjdk");
            }
        }
        catch (Exception e) {
            return false;
        }
    }

    private static String readStream(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }
            return result.toString();
        }
    }

    private static String[] splitCommand(String command) {
        List<String> tokens = new ArrayList<>();
        Pattern pattern = Pattern.compile("([^\"'\\s]+|\"[^\"]*\"|'[^']*')");
        Matcher matcher = pattern.matcher(command);

        while (matcher.find()) {
            String token = matcher.group(1);
            if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2)
                token = token.substring(1, token.length() - 1);
            else if (token.startsWith("'") && token.endsWith("'") && token.length() >= 2)
                token = token.substring(1, token.length() - 1);

            tokens.add(token);
        }

        return tokens.toArray(new String[0]);
    }

    public static void start(String command, File directory) {
        try {
            Process process = new ProcessBuilder(splitCommand(command))
                    .directory(directory)
                    .inheritIO()
                    .start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if(!process.isAlive()) return;

                try {
                    boolean exited = process.waitFor(1, TimeUnit.MINUTES);
                    if (!exited)
                        process.destroyForcibly();
                }
                catch (InterruptedException e) {
                    process.destroyForcibly();
                }
            }));

            process.waitFor();
            System.exit(0);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

}
