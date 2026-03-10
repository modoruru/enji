package su.enji.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class JavaUtil {

    private JavaUtil() {}

    public static boolean checkJavaInstallation(String path) {
        try {
            Process process = new ProcessBuilder()
                    .command(path, "--version")
                    .start();

            try (InputStream inputStream = process.getInputStream(); BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return line != null && line.startsWith("java ");
            }
        }
        catch (Exception e) {
            return false;
        }
    }

    public static void start(String command, File directory) {
        try {
            new ProcessBuilder(command)
                    .directory(directory)
                    .inheritIO()
                    .start();

            System.exit(0);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

}
