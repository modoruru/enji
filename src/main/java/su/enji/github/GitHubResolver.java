package su.enji.github;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

public final class GitHubResolver {

    private static final String API_ENDPOINT = "https://api.github.com";

    private final ExecutorService executorService;
    private final String token;

    private GitHubResolver(ExecutorService executorService, String token) {
        this.executorService = executorService;
        this.token = token;
    }

    public static GitHubResolver unauthorized(ExecutorService executorService) {
        return new GitHubResolver(executorService, null);
    }

    public static GitHubResolver authorized(ExecutorService executorService, String token) {
        if(token == null) return unauthorized(executorService);
        return new GitHubResolver(executorService, token);
    }

    public BlockingOperation<String> lastCommitHash(String owner, String repo, String branch) {
        return BlockingOperation.run(executorService, () -> {
            try {
                URL url = IOUtil.createURL(String.format(
                        "%s/repos/%s/%s/commits?sha=%s",
                        API_ENDPOINT,
                        owner,
                        repo,
                        branch
                ));

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                if(token != null) connection.setRequestProperty("Authorization", "token " + token);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

                connection.setRequestMethod("GET");

                if(connection.getResponseCode() != 200)
                    return null;

                byte[] response = IOUtil.readResponse(connection);
                if(response == null)
                    return null;

                connection.disconnect();

                JSONObject responseBody = new JSONArray(new String(response, StandardCharsets.UTF_8)).getJSONObject(0);
                return responseBody.getString("sha");
            }
            catch (Exception _) {
                return null;
            }
        });
    }

    public BlockingOperation<Boolean> downloadReleaseAsset(DownloadProgressConsumer downloadProgressConsumer, ReleaseAsset releaseAsset, File output) {
        return BlockingOperation.run(executorService, () -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) releaseAsset.url().openConnection();

                if(token != null) connection.setRequestProperty("Authorization", "token " + token);

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

                    return true;
                }
            }
            catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        });
    }

    public BlockingOperation<List<ReleaseAsset>> listAssetsOfRelease(String owner, String repo, int releaseId) {
        return BlockingOperation.run(executorService, () -> {
            String assetsUrl;

            try {
                URL url = IOUtil.createURL(String.format(
                        "%s/repos/%s/%s/releases/%s",
                        API_ENDPOINT,
                        owner,
                        repo,
                        releaseId
                ));

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                if(token != null) connection.setRequestProperty("Authorization", "token " + token);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

                connection.setRequestMethod("GET");

                if(connection.getResponseCode() != 200)
                    return List.of();

                byte[] response = IOUtil.readResponse(connection);
                if(response == null)
                    return List.of();

                connection.disconnect();

                JSONObject responseBody = new JSONObject(new String(response, StandardCharsets.UTF_8));
                assetsUrl = responseBody.optString("assets_url", null);
            }
            catch (Exception _) {
                return List.of();
            }

            if(assetsUrl == null) return List.of();

            URL url = IOUtil.createURL(assetsUrl);
            if(url == null) return List.of();

            try {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                if(token != null) connection.setRequestProperty("Authorization", "token " + token);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

                if(connection.getResponseCode() != 200)
                    return List.of();

                byte[] response = IOUtil.readResponse(connection);
                if(response == null)
                    return List.of();

                connection.disconnect();

                JSONArray array = new JSONArray(new String(response, StandardCharsets.UTF_8));
                List<ReleaseAsset> assets = new ArrayList<>();

                for (Object object : array) {
                    JSONObject assetBody = (JSONObject) object;
                    try {
                        URL assetUrl = IOUtil.createURL(assetBody.getString("browser_download_url"));
                        if(assetUrl == null) continue;

                        assets.add(new ReleaseAsset(
                                assetBody.getInt("id"),
                                assetBody.getString("name"),
                                assetUrl
                        ));
                    }
                    catch (Exception _) {
                    }
                }

                return assets;
            }
            catch (Exception _) {
                return List.of();
            }
        });
    }

    public BlockingOperation<Integer> releaseIdByTag(String owner, String repo, String tag) {
        return BlockingOperation.run(executorService, () -> {
            try {
                URL url = IOUtil.createURL(String.format(
                        "%s/repos/%s/%s/releases/tags/%s",
                        API_ENDPOINT,
                        owner,
                        repo,
                        tag
                ));

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                if(token != null) connection.setRequestProperty("Authorization", "token " + token);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

                connection.setRequestMethod("GET");

                if(connection.getResponseCode() != 200)
                    return -1;

                byte[] response = IOUtil.readResponse(connection);
                if(response == null)
                    return -1;

                connection.disconnect();

                JSONObject responseBody = new JSONObject(new String(response, StandardCharsets.UTF_8));
                return responseBody.optInt("id", -1);
            }
            catch (Exception _) {
                return -1;
            }
        });
    }

    public BlockingOperation<Integer> latestRelease(String owner, String repo) {
        return BlockingOperation.run(executorService, () -> {
            try {
                URL url = IOUtil.createURL(String.format(
                        "%s/repos/%s/%s/releases/latest",
                        API_ENDPOINT,
                        owner,
                        repo
                ));

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                if(token != null) connection.setRequestProperty("Authorization", "token " + token);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

                connection.setRequestMethod("GET");

                if(connection.getResponseCode() != 200)
                    return -1;

                byte[] response = IOUtil.readResponse(connection);
                if(response == null)
                    return -1;

                connection.disconnect();

                JSONObject responseBody = new JSONObject(new String(response, StandardCharsets.UTF_8));

                return responseBody.optInt("id", -1);
            }
            catch (Exception _) {
                return -1;
            }
        });
    }

    public BlockingOperation<Boolean> downloadFile(DownloadProgressConsumer downloadProgressConsumer, String owner, String repo, String branch, String path, File output) {
        return BlockingOperation.run(executorService, () -> {
            URL url = IOUtil.createURL(String.format(
                    "https://raw.githubusercontent.com/%s/%s/refs/heads/%s/%s",
                    owner,
                    repo,
                    branch,
                    path
            ));

            if(url == null) return false;

            try {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                if(token != null) connection.setRequestProperty("Authorization", "token " + token);
                connection.setRequestProperty("Accept", "application/octet-stream");
                connection.setRequestMethod("GET");

                int response = connection.getResponseCode();
                if (response != HttpURLConnection.HTTP_OK)
                    return false;

                int size = Integer.parseInt(connection.getHeaderField("Content-Length"));

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

                connection.disconnect();
                return true;
            }
            catch (Exception _) {
                return false;
            }
        });
    }

}
