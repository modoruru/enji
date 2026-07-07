package su.enji;

import org.json.JSONArray;
import org.json.JSONObject;
import su.enji.core.CoreResolver;
import su.enji.core.DownloadExitCode;
import su.enji.github.GitHubResolver;
import su.enji.github.ReleaseAsset;
import su.enji.model.Origin;
import su.enji.model.Project;
import su.enji.model.Token;
import su.enji.model.Variable;
import su.enji.model.config.Config;
import su.enji.model.config.ConfigSource;
import su.enji.model.config.ConfigsRepository;
import su.enji.model.core.Core;
import su.enji.model.core.CoreBrand;
import su.enji.model.hitori.Hitori;
import su.enji.model.hitori.HitoriModule;
import su.enji.model.plugin.Plugin;
import su.enji.model.plugin.PluginSource;
import su.enji.model.plugin.PluginSourceType;
import su.enji.request.DownloadProgressConsumer;
import su.enji.util.Either;
import su.enji.util.IOUtil;
import su.enji.util.JSONUtil;
import su.enji.util.Pair;
import su.enji.yaml.YamlReader;
import su.enji.yaml.YamlSection;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static su.enji.util.PrintUtil.printInfo;
import static su.enji.util.PrintUtil.printWarning;

@SuppressWarnings("ResultOfMethodCallIgnored")
public final class Enji {

    private static final String SERVER_JAR = "server.jar";
    private static final String HITORI_JAR = "hitori.jar";
    private static final String PLUGINS_DIR = "plugins/";
    private static final String HITORI_MODULES_DIR = "plugins/hitori/";
    private static final int BUFFER_SIZE = 4096;

    private static final DownloadProgressConsumer DOWNLOAD_PROGRESS_CONSUMER = (percentage, bytesDownloaded) -> {
        System.out.printf("\rdownloading... %.1f%% (%s kb)", percentage * 100, bytesDownloaded / 1024);
        System.out.flush();
    };

    public static final String HITORI_REPO_OWNER = "modoruru";
    public static final String HITORI_REPO = "hitori";

    private final File workingDirectory;
    private final ExecutorService executorService;
    private final File tempFolder;

    private Project project;
    private ConfigsRepository configsRepository;
    private GitHubResolver gitHubResolver;

    private File coreFile;
    private Map<String, File> pluginsFiles;
    private Map<String, File> configsFiles;

    private File hitoriFile;
    private File modulesFolder;
    private int hitoriReleaseId = -1;
    private Map<String, File> modulesFiles;

    Enji(File workingDirectory) {
        this.workingDirectory = workingDirectory;
        this.executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        this.tempFolder = new File(workingDirectory, ".enji/temp/");
    }

    public static Optional<String> downloadProjectFile(File file, URI uri) {
        try {
            Pair<InputStream, Long> inputStreamAndSize = IOUtil.resolveToInputStream(uri);
            if(inputStreamAndSize == null)
                return Optional.of("unable to resolve project configuration");

            try (InputStream inputStream = inputStreamAndSize.first(); FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
                fos.flush();
            }
        }
        catch (Exception exception) {
            return Optional.of("unable to resolve project configuration: " + exception);
        }

        return Optional.empty();
    }

    public Project project() {
        return project;
    }

    private Optional<String> downloadFromPluginSource(GitHubResolver gitHubResolver, PluginSource pluginSource, File output) {
        switch (pluginSource.type()) {
            case DIRECT -> {
                URI uri = pluginSource.uri();
                assert uri != null;

                try {
                    Pair<InputStream, Long> inputStreamAndSize = IOUtil.resolveToInputStream(uri);
                    if(inputStreamAndSize == null) return Optional.of("unable to resolve input stream for source");

                    long size = inputStreamAndSize.second();
                    try (InputStream inputStream = inputStreamAndSize.first(); FileOutputStream fos = new FileOutputStream(output)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        int totallyBytesRead = 0;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            totallyBytesRead += bytesRead;
                            Enji.DOWNLOAD_PROGRESS_CONSUMER.update(
                                    Math.clamp(totallyBytesRead / (double) size, 0, 1),
                                    totallyBytesRead
                            );
                            fos.write(buffer, 0, bytesRead);
                        }
                        fos.flush();
                    }
                }
                catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            case GITHUB -> {
                String repo = pluginSource.repo(), tag = pluginSource.tag(), assetName = pluginSource.asset();
                assert repo != null && tag != null && assetName != null;

                String[] unboxedRepo = repo.split("/", 2);
                if(unboxedRepo.length != 2) return Optional.of("malformed repo format");

                List<ReleaseAsset> releaseAssets = gitHubResolver.listAssetsOfRelease(
                        unboxedRepo[0],
                        unboxedRepo[1],
                        gitHubResolver.releaseIdByTag(unboxedRepo[0], unboxedRepo[1], tag).block()
                ).block();

                ReleaseAsset releaseAsset = null;
                for (ReleaseAsset asset : releaseAssets) {
                    if(asset.name().equalsIgnoreCase(assetName)) {
                        releaseAsset = asset;
                        break;
                    }
                }

                if(releaseAsset == null)
                    return Optional.of("unable to resolve release");

                if(!gitHubResolver.downloadReleaseAsset(Enji.DOWNLOAD_PROGRESS_CONSUMER, releaseAsset, output).block())
                    return Optional.of("unable to download release");
            }
        }

        return Optional.empty();
    }

    private void moveConfigAndProcessPlaceholders(File input, File output, Map<String, String> variables) throws IOException {
        String content = Files.readString(input.toPath());

        Pattern pattern = Pattern.compile("\\$\\{enji:([^}]+)\\}");
        Matcher matcher = pattern.matcher(content);

        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = variables.get(key);
            if(replacement == null) replacement = "null";

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        output.getParentFile().mkdirs();
        Files.writeString(output.toPath(), result.toString());
    }

    private void moveFile(File input, File output) {
        try {
            Files.move(input.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private <T> Optional<String> validateRequiredItems(Set<T> requiredItems, Set<T> oldItems, Map<T, String> existingValues, Map<T, String> userSupplied, String itemType) {
        Set<T> remaining = new HashSet<>(requiredItems);
        remaining.removeAll(oldItems);

        if(!remaining.isEmpty()) {
            if(userSupplied == null)
                return Optional.of("new project file requires new " + itemType + "s. please run \"enji update\" and enter them manually.");

            remaining.removeAll(userSupplied.keySet());
            if(!remaining.isEmpty()) {
                return Optional.of("missing required " + itemType + "s: " + remaining);
            }
        }
        return Optional.empty();
    }

    private Optional<String> copyAndCleanup(boolean core, boolean plugins, boolean hitori, Map<String, String> variables) {
        if(core) moveFile(coreFile, new File(workingDirectory, SERVER_JAR));

        File outPluginsFolder = new File(workingDirectory, PLUGINS_DIR);
        if(plugins) {
            outPluginsFolder.mkdirs();

            for (File pluginFile : pluginsFiles.values()) {
                moveFile(pluginFile, new File(outPluginsFolder, pluginFile.getName()));
                pluginFile.delete();
            }
        }

        if(hitori && project.hitori() != null) {
            outPluginsFolder.mkdirs();

            moveFile(hitoriFile, new File(outPluginsFolder, HITORI_JAR));
            hitoriFile.delete();

            File outHitoriFolder = new File(outPluginsFolder, "hitori/");
            outHitoriFolder.mkdir();
            for (File moduleFile : modulesFiles.values()) {
                moveFile(moduleFile, new File(outHitoriFolder, moduleFile.getName()));
                moduleFile.delete();
            }
        }

        for (Map.Entry<String, File> entry : configsFiles.entrySet()) {
            String path = entry.getKey();
            File configFile = entry.getValue();
            try {
                moveConfigAndProcessPlaceholders(configFile, new File(workingDirectory, path), variables);
                configFile.delete();
            }
            catch (Exception exception) {
                return Optional.of("problem copying config \"" + path + "\": " + exception);
            }
        }

        return Optional.empty();
    }

    public Optional<String> update(Map<Token, String> userSuppliedTokens, Map<String, String> userSuppliedVariables) {
        File enjiFolder = new File(workingDirectory, ".enji/");
        File installationFile = new File(enjiFolder, "installation.json");
        if(!installationFile.exists())
            return Optional.of("installation doesn't exists in this folder.");

        JSONObject json;
        try {
            json = JSONUtil.readFile(installationFile);
        }
        catch (Exception _) {
            return Optional.of("malformed installation file.");
        }

        JSONObject originBody = json.optJSONObject("origin"),
                versionBody = json.optJSONObject("version"),
                tokensBody = json.optJSONObject("tokens"),
                variablesBody = json.optJSONObject("variables");
        if(originBody == null || versionBody == null || tokensBody == null || variablesBody == null)
            return Optional.of("installation file misses \"origin\", \"tokens\", \"variables\" or/and \"version\" sections.");

        String originRepo = originBody.optString("repo", null),
                originBranch = originBody.optString("branch", null),
                originPath = originBody.optString("path", null);
        if(originRepo == null || originBranch == null || originPath == null)
            return Optional.of("origin format of installation misses \"repo\", \"branch\" or/and \"path\" values.");

        String[] unboxedRepo = originRepo.split("/", 2);
        if(unboxedRepo.length != 2) return Optional.of("malformed origin repo format");

        String lastCommitHash = versionBody.optString("last_commit_hash", null);
        if(lastCommitHash == null)
            return Optional.of("installation file missed information about last commit.");

        String githubToken = tokensBody.optString(Token.GITHUB.name().toLowerCase(), null);

        if(githubToken == null) gitHubResolver = GitHubResolver.unauthorized(executorService);
        else gitHubResolver = GitHubResolver.authorized(executorService, githubToken);

        String lastCommitOnRemote = gitHubResolver.lastCommitHash(
                unboxedRepo[0],
                unboxedRepo[1],
                originBranch
        ).block();

        // update is not needed
        // todo: possibly update core and hitori as they are can be resolved with %latest% placeholder
        if(lastCommitOnRemote.equalsIgnoreCase(lastCommitHash)) {
            printInfo("installation is up-to-date");
            return Optional.empty();
        }
        printInfo(String.format(
                "HEAD is now at %s (current %s), updating",
                lastCommitOnRemote.substring(0, 8),
                lastCommitHash.substring(0, 8)
        ));

        // https://raw.githubusercontent.com/modoruru/enji/refs/heads/dev/src/test/resources/test.yml
        URI uri;
        try {
            uri = URI.create(String.format(
                    "https://raw.githubusercontent.com/%s/refs/heads/%s/%s",
                    originRepo,
                    originBranch,
                    originPath
            ));
        }
        catch (Exception _) {
            return Optional.of("url of remote project file is malformed. fix your origin configuration and reinstall the project from the scratch.");
        }

        File remoteProjectFile = new File(enjiFolder, "update.yml");
        Enji.downloadProjectFile(remoteProjectFile, uri);

        // read old one
        File currentProjectFile = new File(enjiFolder, "project.yml");
        readProject(currentProjectFile);

        Optional<String> readError = readProject(currentProjectFile);
        if(readError.isPresent())
            return readError.map(str -> "unable to read old project file: " + str);

        Project oldProject = project;

        // tokens
        Map<Token, String> tokens = new HashMap<>();
        for (Token token : project.tokens()) {
            String value = variablesBody.optString(token.name().toLowerCase());
            if(value != null && !value.isEmpty()) {
                tokens.put(token, value);
            }
        }
        if(userSuppliedTokens != null) tokens.putAll(userSuppliedTokens);

        Optional<String> tokenValidationError = validateRequiredItems(
                project.tokens(),
                oldProject.tokens(),
                tokens,
                userSuppliedTokens,
                "token"
        );
        if(tokenValidationError.isPresent()) return tokenValidationError;

        // variables
        Map<String, String> variables = new HashMap<>();
        for (Variable variable : project.variables()) {
            String value = variablesBody.optString(variable.name());
            if(value != null && !value.isEmpty()) {
                variables.put(variable.name(), value);
            }
        }
        if(userSuppliedVariables != null) variables.putAll(userSuppliedVariables);

        Optional<String> variableValidationError = validateRequiredItems(
                new HashSet<>(project.variables().stream().map(Variable::name).collect(Collectors.toSet())),
                oldProject.variables().stream().map(Variable::name).collect(Collectors.toSet()),
                variables,
                userSuppliedVariables,
                "variable"
        );
        if(variableValidationError.isPresent()) return variableValidationError;

        // reinitialize GitHub resolver with new token
        if(tokens.containsKey(Token.GITHUB)) gitHubResolver = GitHubResolver.authorized(executorService, tokens.get(Token.GITHUB));
        else gitHubResolver = GitHubResolver.unauthorized(executorService);

        boolean core = false,
                hitori = false,
                plugins = false;

        // first - core
        Core oldCore = oldProject.core();
        Core newCore = project.core();
        if((oldCore.brand() == CoreBrand.VELOCITY) != (newCore.brand() == CoreBrand.VELOCITY))
            return Optional.of("core has changed the type (from proxy to backend or vice versa). it's not supported.");

        tempFolder.mkdirs();

        CoreResolver newCoreResolver = newCore.brand().createCoreResolver(executorService);
        File coreFile = new File(tempFolder, "core.jar");
        int newCoreBuildId;
        if(oldCore.same(newCore)) {
            int oldBuildId = versionBody.getInt("core_build_id");
            newCoreBuildId = Core.resolveBuildId(newCore, newCoreResolver);

            if(oldBuildId != newCoreBuildId) {
                printInfo(String.format(
                        "updating core: %s -> %s",
                        oldBuildId,
                        newCoreBuildId
                ));

                printInfo("downloading core...");
                core = true;
            }
        }
        else {
            printInfo("updating core...");
            printInfo(String.format("old: [brand: %s, game %s]", oldCore.brand().name().toLowerCase(), oldCore.minecraftVersion()));
            printInfo(String.format("new: [brand: %s, game %s]", newCore.brand().name().toLowerCase(), newCore.minecraftVersion()));
            core = true;
            newCoreBuildId = Core.resolveBuildId(newCore, newCoreResolver);
        }

        if(core) {
            versionBody.put("core_build_id", newCoreBuildId);
            DownloadExitCode downloadExitCode = newCoreResolver.downloadBuild(DOWNLOAD_PROGRESS_CONSUMER, newCore.minecraftVersion(), newCoreBuildId, coreFile).block();
            if(downloadExitCode != DownloadExitCode.OK)
                return Optional.of("problem downloading core. exit code " + downloadExitCode.name().toUpperCase());
            System.out.println();
        }

        // hitori
        modulesFiles = new HashMap<>();
        JSONObject hitoriBody = json.optJSONObject("hitori");
        if((oldProject.hitori() == null) != (project.hitori() == null)) {
            if(oldProject.hitori() == null) hitori = true;
            else {
                printInfo("hitori is no longer in the project, deleting it...");
                if(hitoriBody == null)
                    return Optional.of("installation doesn't contains info about hitori installation");

                new File(workingDirectory, hitoriBody.getString("path")).delete();
                for (Object rawModulesPath : hitoriBody.getJSONArray("modules_path")) {
                    new File(workingDirectory, (String) rawModulesPath).delete();
                }
            }
        }
        else {
            if(hitoriBody == null)
                return Optional.of("installation doesn't contains info about hitori installation");

            int oldReleaseId = hitoriBody.optInt("release_id");
            int newReleaseId = Hitori.resolveReleaseId(project.hitori(), gitHubResolver);
            if(oldReleaseId != newReleaseId) hitori = true;
        }

        if(hitori) {
            printInfo("updating hitori...");
            boolean wasInstalled = oldProject.hitori() != null;
            Optional<String> hitoriInstallError = downloadHitori(project.hitori(), !wasInstalled, false);
            if(hitoriInstallError.isPresent())
                return hitoriInstallError;
            System.out.println();
        }

        // update modules
        if(oldProject.hitori() != null && project.hitori() != null) {
            Hitori newHitori = project.hitori(), oldHitori = oldProject.hitori();

            Map<String, HitoriModule> oldModules = new HashMap<>(oldHitori.modules());
            Map<String, HitoriModule> newModules = newHitori.modules();

            for (Map.Entry<String, HitoriModule> entry : newModules.entrySet()) {
                String name = entry.getKey();
                HitoriModule oldModule = oldModules.remove(name);
                HitoriModule newModule = entry.getValue();
                if(oldModule != null) {
                    if(newModule.source().equals(oldModule.source())) continue;
                    new File(workingDirectory, HITORI_MODULES_DIR + name.replace(':', '_') + ".jar").delete();
                }

                printInfo("updating " + name + " module...");
                var either = downloadModule(modulesFolder, name, entry.getValue());
                if(either.firstPresent())
                    return Optional.of(either.first());

                modulesFiles.put(name, either.second());
            }

            for (Map.Entry<String, HitoriModule> entry : oldModules.entrySet()) {
                String name = entry.getKey();
                printInfo("uninstalling " + name + " module");
                new File(workingDirectory, HITORI_MODULES_DIR + name.replace(':', '_') + ".jar").delete();
            }
        }

        // plugins
        Map<String, Plugin> oldPlugins = new HashMap<>(oldProject.plugins());
        Map<String, Plugin> newPlugins = project.plugins();

        File pluginsFolder = new File(tempFolder, PLUGINS_DIR);
        pluginsFiles = new HashMap<>();
        pluginsFolder.mkdirs();
        for (Plugin plugin : newPlugins.values()) {
            Plugin oldPlugin = oldPlugins.remove(plugin.name()); // we'll later iterate through old plugins which remained
            if(oldPlugin != null) {
                if(plugin.source().equals(oldPlugin.source())) continue;
                new File(workingDirectory, PLUGINS_DIR + plugin.name() + ".jar").delete();
            }

            // install new
            printInfo("updating " + plugin.name() + " plugin...");
            var either = downloadPlugin(pluginsFolder, plugin);
            if(either.firstPresent())
                return Optional.of(either.first());

            plugins = true;
            pluginsFiles.put(plugin.name(), either.second());
        }

        for (Plugin plugin : oldPlugins.values()) {
            printInfo("uninstalling " + plugin.name() + " plugin");
            new File(workingDirectory, PLUGINS_DIR + plugin.name() + ".jar").delete();
        }

        // configs
        JSONArray array = json.optJSONArray("configs");
        if(array != null) {
            for (Object obj : array) {
                new File(workingDirectory, (String) obj).delete();
            }
        }

        configsFiles = new HashMap<>();
        Optional<String> configsDownloadError = downloadConfigs();
        if(configsDownloadError.isPresent())
            return configsDownloadError;

        Optional<String> metadataWriteError = writeInstallationMetadata(
                tokens,
                variables,
                json.getJSONObject("run_command").getJSONObject("decomposed").optString("java_path"),
                newCoreBuildId
        );
        if(metadataWriteError.isPresent())
            return metadataWriteError;

        moveFile(remoteProjectFile, currentProjectFile);

        return copyAndCleanup(core, plugins, hitori, variables);
    }

    private Optional<String> writeInstallationMetadata(Map<Token, String> tokens, Map<String, String> variables, String javaPath, int coreBuildId) {
        JSONObject tokensBody = new JSONObject();
        for (Map.Entry<Token, String> entry : tokens.entrySet()) {
            tokensBody.put(entry.getKey().name().toLowerCase(), entry.getValue());
        }

        Origin origin = project.origin();

        String[] unboxedRepo = origin.repo().split("/", 2);
        if(unboxedRepo.length != 2) return Optional.of("malformed origin repo format");

        JSONObject json = new JSONObject()
                .put(
                        "origin",
                        new JSONObject()
                                .put("repo", origin.repo())
                                .put("branch", origin.branch())
                                .put("path", origin.path())
                )
                .put(
                        "version",
                        new JSONObject()
                                .put("last_commit_hash", gitHubResolver.lastCommitHash(
                                        unboxedRepo[0], unboxedRepo[1],
                                        origin.branch()
                                ).block())
                                .put("core_build_id", coreBuildId)
                )
                .put("auto_update", project.autoUpdate())
                .put(
                        "run_command",
                        new JSONObject()
                                .put("command", String.format(
                                        "%s %s -jar %s nogui",
                                        javaPath,
                                        project.jvmArgs(),
                                        SERVER_JAR
                                ))
                                .put(
                                        "decomposed",
                                        new JSONObject()
                                                .put("java_path", javaPath)
                                                .put("jvm_args", project.jvmArgs())
                                )
                )
                .put("configs", new JSONArray().putAll(configsFiles.keySet()))
                .put("variables", new JSONObject(variables))
                .put("tokens", tokensBody);

        if(project.hitori() != null) {
            JSONArray modulesPaths = new JSONArray();
            for (File value : modulesFiles.values()) {
                modulesPaths.put(HITORI_MODULES_DIR + value.getName());
            }

            json.put(
                    "hitori",
                    new JSONObject()
                            .put("path", PLUGINS_DIR + HITORI_JAR)
                            .put("modules_paths", modulesPaths)
                            .put("release_id", hitoriReleaseId)
            );
        }

        try (FileWriter writer = new FileWriter(new File(workingDirectory, ".enji/installation.json"))) {
            writer.write(json.toString(2));
            writer.flush();
        }
        catch (IOException _) {
            return Optional.of("problem creating installation metadata");
        }

        return Optional.empty();
    }

    private Optional<String> downloadHitori(Hitori hitori, boolean installModules, boolean sendDownloadMessage) {
        hitoriFile = new File(tempFolder, HITORI_JAR);
        modulesFolder = new File(tempFolder, "modules/");
        modulesFiles = new HashMap<>();

        List<ReleaseAsset> releaseAssets = gitHubResolver.listAssetsOfRelease(
                HITORI_REPO_OWNER,
                HITORI_REPO,
                hitoriReleaseId = Hitori.resolveReleaseId(hitori, gitHubResolver)
        ).block();

        ReleaseAsset releaseAsset = null;
        for (ReleaseAsset asset : releaseAssets) {
            if(asset.name().startsWith("hitori") && asset.name().endsWith(".jar")) {
                releaseAsset = asset;
                break;
            }
        }

        if(releaseAsset == null)
            return Optional.of("unable to resolve hitori release.");

        if(sendDownloadMessage) printInfo("downloading hitori...");

        if(!gitHubResolver.downloadReleaseAsset(DOWNLOAD_PROGRESS_CONSUMER, releaseAsset, hitoriFile).block())
            return Optional.of("unable to download release");
        System.out.println();

        if(installModules) {
            modulesFolder.mkdirs();
            for (Map.Entry<String, HitoriModule> entry : hitori.modules().entrySet()) {
                String name = entry.getKey();

                var either = downloadModule(modulesFolder, name, entry.getValue());
                if(either.firstPresent())
                    return Optional.of(either.first());

                modulesFiles.put(name, either.second());
            }
        }

        return Optional.empty();
    }

    private Either<String, File> downloadModule(File modulesFolder, String moduleName, HitoriModule module) {
        File pluginFile = new File(modulesFolder, moduleName.replace(':', '_') + ".jar");

        printInfo("downloading module \"" + moduleName + "\"...");
        Optional<String> error = downloadFromPluginSource(
                gitHubResolver,
                module.source(),
                pluginFile
        );
        if(error.isPresent())
            return Either.ofFirst("problem downloading module \"" + moduleName + "\": " + error.get());

        System.out.println();

        return Either.ofSecond(pluginFile);
    }

    private Either<String, File> downloadPlugin(File pluginsFolder, Plugin plugin) {
        String name = plugin.name();
        File pluginFile = new File(pluginsFolder, name + ".jar");

        printInfo("downloading plugin \"" + name + "\"...");
        Optional<String> error = downloadFromPluginSource(
                gitHubResolver,
                plugin.source(),
                pluginFile
        );
        if(error.isPresent())
            return Either.ofFirst("problem downloading plugin \"" + name + "\": " + error.get());

        System.out.println();

        return Either.ofSecond(pluginFile);
    }

    private Optional<String> downloadConfigs() {
        Origin origin = project.origin();

        String[] unboxedRepo = origin.repo().split("/", 2);
        if(unboxedRepo.length != 2) return Optional.of("malformed origin repo format");

        File configsFolder = new File(tempFolder, "configs/");
        configsFolder.mkdirs();

        String projectPath = origin.path();
        int lastSeparator = projectPath.lastIndexOf('/');
        String basePath;
        if(lastSeparator == -1) basePath = "";
        else basePath = projectPath.substring(0, lastSeparator + 1);

        for (Config config : configsRepository.configs()) {
            File configFile = new File(configsFolder, config.path());
            configFile.getParentFile().mkdirs();

            if(!gitHubResolver.downloadFile(DOWNLOAD_PROGRESS_CONSUMER, unboxedRepo[0], unboxedRepo[1], origin.branch(), basePath + config.path(), configFile).block()) {
                printWarning("unable to resolve " + config.path() + " config file.");
                continue;
            }
            System.out.println();

            configsFiles.put(config.path(), configFile);
        }

        return Optional.empty();
    }

    /**
     * @return error or nothing if installed successfully
     */
    public Optional<String> install(Map<Token, String> tokens, Map<String, String> variables, String javaPath) {
        File tempFolder = new File(workingDirectory, ".enji/temp/");
        tempFolder.mkdirs();

        if(tokens.containsKey(Token.GITHUB)) gitHubResolver = GitHubResolver.authorized(executorService, tokens.get(Token.GITHUB));
        else gitHubResolver = GitHubResolver.unauthorized(executorService);

        // check tokens and variables
        for (Token token : project.tokens()) {
            if(!tokens.containsKey(token)) return Optional.of("token \"" + token.name().toLowerCase() + "\" is not set.");
        }

        for (Variable variable : project.variables()) {
            if(!variables.containsKey(variable.name())) return Optional.of("variable \"" + variable.name() + "\" is not set.");
        }

        // install core
        Core core = project.core();
        coreFile = new File(tempFolder, "core.jar");
        int coreBuildId;
        switch (core.brand()) {
            case PAPER, PURPUR, VELOCITY -> {
                String minecraftVersion = core.minecraftVersion();
                String version = core.build();
                assert minecraftVersion != null && version != null;

                CoreResolver coreResolver = core.brand().createCoreResolver(executorService);
                coreBuildId = Core.resolveBuildId(core, coreResolver);

                if(coreBuildId == -1)
                    return Optional.of("can't resolve build number for core");

                printInfo("downloading core...");
                DownloadExitCode downloadExitCode = coreResolver.downloadBuild(DOWNLOAD_PROGRESS_CONSUMER, minecraftVersion, coreBuildId, coreFile).block();
                if(downloadExitCode != DownloadExitCode.OK) {
                    return Optional.of("problem downloading core. exit code " + downloadExitCode.name().toUpperCase());
                }
                System.out.println();
            }
            default -> coreBuildId = -1;
        }

        // install hitori and modules
        if(project.hitori() != null) {
            Optional<String> hitoriInstallError = downloadHitori(project.hitori(), true, true);
            if(hitoriInstallError.isPresent())
                return hitoriInstallError;
        }

        // install plugins
        pluginsFiles = new HashMap<>();
        File pluginsFolder = new File(tempFolder, PLUGINS_DIR);
        pluginsFolder.mkdirs();
        for (Plugin plugin : project.plugins().values()) {
            var either = downloadPlugin(pluginsFolder, plugin);
            if(either.firstPresent())
                return Optional.of(either.first());

            pluginsFiles.put(plugin.name(), either.second());
        }

        // install configs
        configsFiles = new HashMap<>();
        Optional<String> configsDownloadError = downloadConfigs();
        if(configsDownloadError.isPresent())
            return configsDownloadError;

        printInfo("copying everything...");
        // copy everything and process configs
        Optional<String> copyError = copyAndCleanup(true, true, true, variables);
        if(copyError.isPresent())
            return copyError;

        printInfo("creating installation metadata...");
        writeInstallationMetadata(tokens, variables, javaPath, coreBuildId);

        return Optional.empty();
    }

    /**
     * @return error or nothing if parsed successfully
     */
    public Optional<String> readProject(File yamlConfiguration) {
        YamlReader config = new YamlReader();
        try {
            config.load(yamlConfiguration);
        }
        catch (Exception exception) {
            exception.printStackTrace();
            return Optional.of("malformed yaml file");
        }

        // READ PROJECT
        YamlSection projectSection = config.getSection("project");
        if(projectSection == null)
            return Optional.of("\"project\" section doesn't exists.");

        ConfigsRepository configsRepository = new ConfigsRepository();

        String name = projectSection.getString("name", "");
        String description = projectSection.getString("description", "");
        String jvmArgs = projectSection.getString("jvm_args", "");
        if(name.isEmpty() || description.isEmpty() || jvmArgs.isEmpty())
            return Optional.of("name, description or jvm_args is empty");

        YamlSection originSection = projectSection.getSection("origin");
        if(originSection == null) return Optional.of("\"origin\" section doesn't exists.");
        String repo = originSection.getString("repo", ""),
                branch = originSection.getString("branch", ""),
                path = originSection.getString("path", "");
        if(repo.isEmpty() || branch.isEmpty() || path.isEmpty())
            return Optional.of("repo or branch or path of project origin is empty");

        boolean autoUpdate = projectSection.getBoolean("auto_update", false);
        List<String> rawTokens = projectSection.getStringList("tokens");
        Set<Token> tokens;
        if(rawTokens == null || rawTokens.isEmpty()) tokens = Set.of();
        else {
            tokens = new HashSet<>();
            for (String rawToken : rawTokens) {
                try {
                    tokens.add(Token.valueOf(rawToken.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    return Optional.of("wrong token type: \"" + rawToken + "\"");
                }
            }
        }


        // READ VARIABLES
        YamlSection variablesSection = config.getSection("variables");
        List<Variable> variables;
        if(variablesSection == null) variables = List.of();
        else {
            variables = new ArrayList<>();

            for (String variableName : variablesSection.keySet()) {
                YamlSection variableSection = variablesSection.getSection(variableName);
                if(variableSection == null) continue;

                String variableDescription = variableSection.getString("description", "");
                if(variableDescription.isEmpty())
                    return Optional.of("variable description is empty");

                String regex = variableSection.getString("regex", null);
                String defaultValue = variableSection.getString("default", null);

                Pattern pattern;
                if(regex == null) pattern = null;
                else {
                    try {
                        pattern = Pattern.compile(regex);
                    }
                    catch (Exception _) {
                        return Optional.of("problem parsing regex on \"" + variableName + "\" variable");
                    }
                }

                if(pattern != null && defaultValue != null && !pattern.matcher(defaultValue).find())
                    return Optional.of("default value doesn't matches regex");

                variables.add(new Variable(variableName, variableDescription, pattern, defaultValue));
            }
        }

        // READ CORE
        YamlSection coreSection = config.getSection("core");
        if(coreSection == null)
            return Optional.of("\"core\" section doesn't exists.");

        String rawCoreBrand = coreSection.getString("brand", "");
        CoreBrand coreBrand;
        try {
            coreBrand = CoreBrand.valueOf(rawCoreBrand.toUpperCase());
        }
        catch (Exception _) {
            return Optional.of("wrong core type \"" + rawCoreBrand + "\"");
        }

        String coreParseError = null;
        Core core = switch (coreBrand) {
            case PAPER, FOLIA, PURPUR -> {
                String minecraftVersion = coreSection.getString("minecraft_version", "");
                String build = coreSection.getString("build", "");
                if(minecraftVersion.isEmpty() || build.isEmpty()) {
                    coreParseError = "minecraft_version or build of core is empty";
                    yield null;
                }

                yield switch (coreBrand) {
                    case PAPER -> Core.paper(configsRepository, minecraftVersion, build);
                    case FOLIA -> Core.folia(configsRepository, minecraftVersion, build);
                    case PURPUR -> Core.purpur(configsRepository, minecraftVersion, build);
                    default -> throw new UnsupportedOperationException();
                };
            }
            case VELOCITY -> {
                String version = coreSection.getString("version", "");
                String build = coreSection.getString("build", "");
                if(version.isEmpty() || build.isEmpty()) {
                    coreParseError = "version or build of core is empty";
                    yield null;
                }

                yield Core.velocity(configsRepository, version, build);
            }
        };

        if(core == null) return Optional.of(coreParseError);

        // READ PLUGINS
        YamlSection pluginsSection = config.getSection("plugins");
        Map<String, Plugin> plugins;
        if(pluginsSection == null) plugins = Map.of();
        else {
            plugins = new HashMap<>();
            for (String pluginName : pluginsSection.keySet()) {
                YamlSection pluginSection = pluginsSection.getSection(pluginName);
                if(pluginSection == null) continue;

                YamlSection pluginSourceSection = pluginSection.getSection("source");
                if(pluginSourceSection == null)
                    return Optional.of("plugin \"" + pluginName + "\" doesn't have source.");

                var pluginSourceOrError = readPluginSource(pluginName, pluginSourceSection, false);
                if(pluginSourceOrError.secondPresent()) return Optional.of(pluginSourceOrError.second());

                List<Config> configs = new ArrayList<>();
                List<String> rawConfigs = pluginSection.getStringList("configs");
                for (String rawConfig : rawConfigs) {
                    try {
                        configs.add(configsRepository.create(ConfigSource.PLUGIN, rawConfig));
                    }
                    catch (Exception _) {
                        return Optional.of(String.format(
                                "plugin \"%s\" tried to create already existing configuration: \"%s\" created from %s",
                                pluginName,
                                rawConfig,
                                configsRepository.config(rawConfig).source().name().toLowerCase()
                        ));
                    }
                }

                plugins.put(pluginName, new Plugin(
                        pluginName,
                        pluginSourceOrError.first(),
                        configs
                ));
            }
        }

        // read hitori
        YamlSection hitoriSection = config.getSection("hitori");
        Hitori hitori;
        if(hitoriSection == null) hitori = null;
        else if(coreBrand == CoreBrand.VELOCITY) return Optional.of("hitori is incompatible with velocity core.");
        else {
            String version = hitoriSection.getString("version", "");
            if(version.isEmpty()) return Optional.of("hitori version is empty");

            YamlSection modulesSection = hitoriSection.getSection("modules");
            Map<String, HitoriModule> modules;
            if(modulesSection == null) modules = Map.of();
            else {
                modules = new HashMap<>();
                for (String moduleKey : modulesSection.keySet()) {
                    YamlSection moduleSection = modulesSection.getSection(moduleKey);
                    if(moduleSection == null) continue;

                    YamlSection moduleSourceSection = moduleSection.getSection("source");
                    if(moduleSourceSection == null)
                        return Optional.of("module \"" + moduleKey + "\" doesn't have source.");

                    var pluginSourceOrError = readPluginSource(moduleKey, moduleSourceSection, true);
                    if(pluginSourceOrError.secondPresent()) return Optional.of(pluginSourceOrError.second());

                    List<Config> configs = new ArrayList<>();
                    List<String> rawConfigs = moduleSection.getStringList("configs");
                    for (String rawConfig : rawConfigs) {
                        try {
                            configs.add(configsRepository.create(ConfigSource.HITORI_MODULE, rawConfig));
                        }
                        catch (Exception _) {
                            return Optional.of(String.format(
                                    "module \"%s\" tried to create already existing configuration: \"%s\" created from %s",
                                    moduleKey,
                                    rawConfig,
                                    configsRepository.config(rawConfig).source().name().toLowerCase()
                            ));
                        }
                    }

                    modules.put(moduleKey, new HitoriModule(pluginSourceOrError.first(), configs));
                }
            }


            hitori = Hitori.create(configsRepository, version, modules);
        }

        project = new Project(
                new Origin(
                        repo,
                        branch,
                        path
                ),
                name,
                description,
                jvmArgs,
                autoUpdate,
                tokens,
                variables,
                core,
                plugins,
                hitori
        );
        this.configsRepository = configsRepository;

        return Optional.empty();
    }

    private Either<PluginSource, String> readPluginSource(String parentName, YamlSection sourceSection, boolean module) {
        String rawPluginSourceType = sourceSection.getString("type", "");
        PluginSourceType pluginSourceType;
        try {
            pluginSourceType = PluginSourceType.valueOf(rawPluginSourceType.toUpperCase());
        }
        catch (Exception _) {
            return Either.ofSecond(String.format(
                    "%s \"%s\" source has wrong type: \"%s\"",
                    module ? "module" : "plugin",
                    parentName,
                    rawPluginSourceType
            ));
        }

        PluginSource pluginSource;
        switch (pluginSourceType) {
            case DIRECT -> {
                String rawUri = sourceSection.getString("uri", "");
                if(rawUri.isEmpty()) return Either.ofSecond(String.format(
                        "%s \"%s\" source has empty uri",
                        module ? "module" : "plugin",
                        parentName
                ));
                try {
                    pluginSource = PluginSource.createDirect(URI.create(rawUri));
                }
                catch (Exception _) {
                    return Either.ofSecond(String.format(
                            "%s \"%s\" source has malformed uri",
                            module ? "module" : "plugin",
                            parentName
                    ));
                }
            }
            case GITHUB -> {
                String pluginRepo = sourceSection.getString("repo", ""),
                        tag = sourceSection.getString("tag", ""),
                        asset = sourceSection.getString("asset", "");
                if(pluginRepo.isEmpty() || tag.isEmpty() || asset.isEmpty())
                    return Either.ofSecond(String.format(
                            "%s \"%s\" source repo, tag or asset is empty",
                            module ? "module" : "plugin",
                            parentName
                    ));

                pluginSource = PluginSource.createGithub(pluginRepo, tag, asset);
            }
            default -> pluginSource = null;
        }

        return Either.ofFirst(pluginSource);
    }

}
