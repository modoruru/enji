package su.enji.core;

import org.json.JSONArray;
import org.json.JSONObject;
import su.enji.request.BlockingOperation;
import su.enji.request.DownloadProgressConsumer;
import su.enji.util.IOUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

/**
 * Resolves Paper-distributed Core (paper or velocity)
 * Based on paper's Fill API
 */
public final class PaperCoreResolver implements CoreResolver {

    private static final String
            VELOCITY = "velocity",
            PAPER = "paper",
            BASE_ENDPOINT = "https://fill.papermc.io/v3/projects/%s/versions/";

    private final ExecutorService executorService;
    private final String versionEndpoint;

    private PaperCoreResolver(ExecutorService executorService, String paperProject) {
        this.executorService = executorService;
        this.versionEndpoint = String.format(BASE_ENDPOINT, paperProject);
    }

    public static PaperCoreResolver createVelocity(ExecutorService executorService) {
        return new PaperCoreResolver(executorService, VELOCITY);
    }

    public static PaperCoreResolver createPaper(ExecutorService executorService) {
        return new PaperCoreResolver(executorService, PAPER);
    }

    @Override
    public BlockingOperation<Integer> latestBuild(String version) {
        return BlockingOperation.run(executorService, () -> {
            URL url = IOUtil.createURL(versionEndpoint + version);

            try {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                if(connection.getResponseCode() != 200)
                    return -1;

                byte[] response = IOUtil.readResponse(connection);
                if(response == null)
                    return -1;

                connection.disconnect();

                JSONObject responseBody = new JSONObject(new String(response, StandardCharsets.UTF_8));
                JSONArray buildsBody = responseBody.getJSONArray("builds");

                return buildsBody.getInt(0);
            }
            catch (Exception _) {
                return -1;
            }
        });
    }

    @Override
    public BlockingOperation<DownloadExitCode> downloadBuild(DownloadProgressConsumer downloadProgressConsumer, String version, int build, File output) {
        return BlockingOperation.run(executorService, () -> {
            URL url = IOUtil.createURL(versionEndpoint + version + "/builds/" + build);

            try {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                if(connection.getResponseCode() != 200)
                    return DownloadExitCode.UNKNOWN_ERROR;

                byte[] response = IOUtil.readResponse(connection);
                if(response == null)
                    return DownloadExitCode.UNKNOWN_ERROR;

                connection.disconnect();

                JSONObject responseBody = new JSONObject(new String(response, StandardCharsets.UTF_8));
                if(!responseBody.optBoolean("ok", true)) {
                    return "build_not_found".equals(responseBody.optString("error"))
                            ? DownloadExitCode.BUILD_NOT_FOUND
                            : DownloadExitCode.UNKNOWN_ERROR;
                }

                JSONObject serverDefaultBody = responseBody.getJSONObject("downloads").getJSONObject("server:default");
                URL jarUrl = IOUtil.createURL(serverDefaultBody.optString("url", null));
                if(jarUrl == null) return DownloadExitCode.UNKNOWN_ERROR;

                return downloadJar(downloadProgressConsumer, serverDefaultBody.getLong("size"), jarUrl, output)
                        ? DownloadExitCode.OK
                        : DownloadExitCode.UNKNOWN_ERROR;
            }
            catch (Exception _) {
                return DownloadExitCode.UNKNOWN_ERROR;
            }
        });
    }

    private boolean downloadJar(DownloadProgressConsumer downloadProgressConsumer, long size, URL url, File output) {
        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            try (InputStream is = connection.getInputStream(); FileOutputStream fos = new FileOutputStream(output)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                int totallyBytesRead = 0;
                while ((bytesRead = is.read(buffer)) != -1) {
                    totallyBytesRead += bytesRead;
                    downloadProgressConsumer.update(
                            Math.clamp(totallyBytesRead / (double) size, 0, 1),
                            totallyBytesRead
                    );
                    fos.write(buffer, 0, bytesRead);
                }
                fos.flush();
            }

            return true;
        }
        catch (Exception _) {
            return false;
        }
    }

}
