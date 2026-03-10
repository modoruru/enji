package su.enji.core;

import su.enji.request.BlockingOperation;
import su.enji.request.DownloadProgressConsumer;

import java.io.File;

public interface CoreResolver {

    BlockingOperation<Integer> latestBuild(String version);

    BlockingOperation<DownloadExitCode> downloadBuild(DownloadProgressConsumer downloadProgressConsumer, String version, int build, File output);

}
