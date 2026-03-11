package su.enji.core;

import org.json.JSONObject;
import su.enji.request.BlockingOperation;
import su.enji.request.DownloadProgressConsumer;
import su.enji.util.IOUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

public final class PurpurCoreResolver implements CoreResolver {

    private static final String PURPUR_ENDPOINT = "https://api.purpurmc.org/v2/purpur/";

    private final ExecutorService executorService;

    private PurpurCoreResolver(ExecutorService executorService) {
        this.executorService = executorService;
    }

    public static PurpurCoreResolver create(ExecutorService executorService) {
        return new PurpurCoreResolver(executorService);
    }

    @Override
    public BlockingOperation<Integer> latestBuild(String version) {
        return BlockingOperation.run(executorService, () -> {
            URL url = IOUtil.createURL(PURPUR_ENDPOINT + version);

            try {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                if(connection.getResponseCode() != 200)
                    return -1;

                byte[] response = IOUtil.readResponse(connection);
                if(response == null)
                    return -1;

                connection.disconnect();

                JSONObject responseBody = new JSONObject(new String(response, StandardCharsets.UTF_8));
                JSONObject buildsBody = responseBody.getJSONObject("builds");

                String latest = buildsBody.optString("latest", null);
                if(latest == null) return -1;

                return Integer.parseInt(latest);
            }
            catch (Exception _) {
                return -1;
            }
        });
    }

    @Override
    public BlockingOperation<DownloadExitCode> downloadBuild(DownloadProgressConsumer downloadProgressConsumer, String version, int build, File output) {
        return BlockingOperation.run(executorService, () -> {
            URL url = IOUtil.createURL(PURPUR_ENDPOINT + version + "/" + build + "/download");

            try {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                if(connection.getResponseCode() != 200)
                    return DownloadExitCode.UNKNOWN_ERROR;

                if(connection.getHeaderField("Content-Type").equalsIgnoreCase("application/octet-stream")) {
                    int size = Integer.parseInt(connection.getHeaderField("Content-Length"));

                    try (FileOutputStream fos = new FileOutputStream(output)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        int totallyBytesRead = 0;
                        while ((bytesRead = connection.getInputStream().read(buffer)) != -1) {
                            totallyBytesRead += bytesRead;
                            downloadProgressConsumer.update(
                                    Math.clamp(totallyBytesRead / (double) size, 0, 1),
                                    totallyBytesRead
                            );
                            fos.write(buffer, 0, bytesRead);
                        }
                        fos.flush();
                    }

                    return DownloadExitCode.OK;
                }

                byte[] response = IOUtil.readResponse(connection);
                if(response == null)
                    return DownloadExitCode.UNKNOWN_ERROR;

                connection.disconnect();

                JSONObject responseBody = new JSONObject(new String(response, StandardCharsets.UTF_8));
                return responseBody.optString("error", "").equalsIgnoreCase("build not found")
                        ? DownloadExitCode.BUILD_NOT_FOUND
                        : DownloadExitCode.UNKNOWN_ERROR;
            }
            catch (Exception _) {
                return DownloadExitCode.UNKNOWN_ERROR;
            }
        });
    }

}
