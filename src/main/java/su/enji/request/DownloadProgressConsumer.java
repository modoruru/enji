package su.enji.request;

public interface DownloadProgressConsumer {

    void update(double percentage, long bytesDownloaded);

}
