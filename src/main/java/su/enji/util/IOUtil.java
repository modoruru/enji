package su.enji.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;

public final class IOUtil {

    private IOUtil() {
    }

    public static Pair<InputStream, Long> resolveToInputStream(URI uri) throws Exception {
        if ("file".equals(uri.getScheme())) {
            File file = new File(uri);
            return Pair.of(new FileInputStream(file), file.length());
        }
        else if (uri.getScheme() != null && (uri.getScheme().startsWith("http") || uri.getScheme().equals("ftp"))) {
            URL url = uri.toURL();
            URLConnection connection = url.openConnection();

            long size = connection.getContentLengthLong();

            return Pair.of(connection.getInputStream(), size);
        }
        else if (uri.getScheme() != null) {
            URL url = uri.toURL();
            URLConnection connection = url.openConnection();

            long size = connection.getContentLengthLong();
            return Pair.of(connection.getInputStream(), size);
        }

        File file = new File(uri.toString());
        if (file.exists())
            return Pair.of(new FileInputStream(file), file.length());

        return null;
    }

    public static URL createURL(String string) {
        if(string == null) return null;

        try {
            return URI.create(string).toURL();
        }
        catch (Exception _) {
            return null;
        }
    }

    public static byte[] readResponse(HttpURLConnection connection) {
        try (InputStream is = connection.getInputStream()) {
            return is.readAllBytes();
        }
        catch (IOException _) {
            return null;
        }
    }

    public static void deleteFileRecursively(File file) {
        if(!file.exists()) return;

        if(file.isFile()) {
            file.delete();
            return;
        }

        File[] files = file.listFiles();
        assert files != null;

        for (File file1 : files) {
            deleteFileRecursively(file1);
        }
        file.delete();
    }

}
