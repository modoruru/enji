package su.enji;

import su.enji.core.CoreResolver;
import su.enji.core.DownloadExitCode;
import su.enji.core.PaperCoreResolver;
import su.enji.core.PurpurCoreResolver;
import su.enji.github.GitHubResolver;
import su.enji.github.ReleaseAsset;
import su.enji.model.*;
import su.enji.model.config.Config;
import su.enji.model.config.ConfigSource;
import su.enji.model.config.ConfigsRepository;
import su.enji.model.core.Core;
import su.enji.model.core.CoreType;
import su.enji.model.hitori.Hitori;
import su.enji.model.hitori.HitoriModule;
import su.enji.model.plugin.Plugin;
import su.enji.model.plugin.PluginSource;
import su.enji.model.plugin.PluginSourceType;
import su.enji.request.DownloadProgressConsumer;
import su.enji.util.Either;
import su.enji.util.IOUtil;
import su.enji.util.Pair;
import su.enji.yaml.YamlReader;
import su.enji.yaml.YamlSection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static su.enji.util.PrintUtil.*;

public final class Enji {

    private static final String HITORI_REPO_OWNER = "modoruru";
    private static final String HITORI_REPO = "hitori";

    private final File workingDirectory;

    private Project project;
    private ConfigsRepository configsRepository;

    Enji(File workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public Project project() {
        return project;
    }

    private Optional<String> downloadFromPluginSource(GitHubResolver gitHubResolver, DownloadProgressConsumer downloadProgressConsumer, PluginSource pluginSource, File output) {
        switch (pluginSource.type()) {
            case DIRECT -> {
                URI uri = pluginSource.uri();
                assert uri != null;

                try {
                    Pair<InputStream, Long> inputStreamAndSize = IOUtil.resolveToInputStream(uri);
                    if(inputStreamAndSize == null) return Optional.of("unable to resolve input stream for source");

                    long size = inputStreamAndSize.second();
                    try (InputStream inputStream = inputStreamAndSize.first(); FileOutputStream fos = new FileOutputStream(output)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        int totallyBytesRead = 0;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            totallyBytesRead += bytesRead;
                            downloadProgressConsumer.update(
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
                        Either.ofSecond(tag)
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

                if(!gitHubResolver.downloadReleaseAsset(downloadProgressConsumer, releaseAsset, output).block())
                    return Optional.of("unable to download release");
            }
        }

        return Optional.empty();
    }

    private void copyConfigAndProcessPlaceholders(File input, File output, Map<String, String> variables) throws IOException {
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

        Files.writeString(output.toPath(), result.toString());
        input.delete();
    }

    private boolean moveFile(File input, File output) {
        try {
            Files.move(input.toPath(), output.toPath());
            return true;
        }
        catch (Exception _) {
            return false;
        }
    }

    /**
     * @return error or nothing if installed successfully
     */
    public Optional<String> install(Map<Token, String> tokens, Map<String, String> variables) {
        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        File tempFolder = new File(workingDirectory, ".enji/temp/");
        tempFolder.mkdirs();
        DownloadProgressConsumer downloadProgressConsumer = (percentage, bytesDownloaded) -> {
            System.out.printf("\rdownloading... %.1f%% (%s kb)", percentage * 100, bytesDownloaded / 1024);
            System.out.flush();
        };

        GitHubResolver gitHubResolver;
        if(tokens.containsKey(Token.GITHUB))
            gitHubResolver = GitHubResolver.authorized(executorService, tokens.get(Token.GITHUB));
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
        File coreFile = new File(tempFolder, "core.jar");
        switch (core.type()) {
            case PAPER, PURPUR -> {
                String minecraftVersion = core.minecraftVersion();
                String version = core.version();
                assert minecraftVersion != null && version != null;

                int build;
                CoreResolver coreResolver = core.type() == CoreType.PAPER
                        ? PaperCoreResolver.create(executorService)
                        : PurpurCoreResolver.create(executorService);
                if(version.equalsIgnoreCase("%latest%"))
                    build = coreResolver.latestBuild(minecraftVersion).block();
                else {
                    try {build = Integer.parseInt(version);}
                    catch (Exception _) {build = -1;}
                }

                if(build == -1)
                    return Optional.of("can't resolve build number for core");

                printInfo("downloading core...");
                DownloadExitCode downloadExitCode = coreResolver.downloadBuild(downloadProgressConsumer, minecraftVersion, build, coreFile).block();
                if(downloadExitCode != DownloadExitCode.OK) {
                    return Optional.of("problem downloading core. exit code " + downloadExitCode.name().toUpperCase());
                }
                System.out.println();
            }
        }

        // install hitori and modules
        Hitori hitori = project.hitori();
        File hitoriFile = new File(tempFolder, "hitori.jar");
        Map<String, File> modulesFiles = new HashMap<>();
        if(hitori != null) {
            String version = hitori.version();

            Either<Integer, String> retrievalStrategy;
            if(version.equalsIgnoreCase("%latest%"))
                retrievalStrategy = Either.ofFirst(gitHubResolver.latestRelease(HITORI_REPO_OWNER, HITORI_REPO).block());
            else retrievalStrategy = Either.ofSecond(version);

            List<ReleaseAsset> releaseAssets = gitHubResolver.listAssetsOfRelease(
                    HITORI_REPO_OWNER,
                    HITORI_REPO,
                    retrievalStrategy
            ).block();

            ReleaseAsset releaseAsset = null;
            for (ReleaseAsset asset : releaseAssets) {;
                if(asset.name().startsWith("hitori") && asset.name().endsWith(".jar")) {
                    releaseAsset = asset;
                    break;
                }
            }

            if(releaseAsset == null)
                return Optional.of("unable to resolve hitori release.");

            printInfo("downloading hitori...");
            if(!gitHubResolver.downloadReleaseAsset(downloadProgressConsumer, releaseAsset, hitoriFile).block())
                return Optional.of("unable to download release");
            System.out.println();

            File modulesFolder = new File(tempFolder, "modules/");
            modulesFolder.mkdirs();
            for (HitoriModule module : hitori.modules()) {
                String name = module.name();
                File file = new File(modulesFolder, name.replace(':', '_') + ".jar");

                printInfo("downloading module \"" + name + "\"...");
                Optional<String> error = downloadFromPluginSource(
                        gitHubResolver,
                        downloadProgressConsumer,
                        module.source(),
                        file
                );
                if(error.isPresent()) {
                    return Optional.of("problem downloading module \"" + name + "\": " + error.get());
                }
                System.out.println();

                modulesFiles.put(name, file);
            }
        }

        // install plugins
        Map<String, File> pluginsFiles = new HashMap<>();
        File pluginsFolder = new File(tempFolder, "plugins/");
        pluginsFolder.mkdirs();
        for (Plugin plugin : project.plugins()) {
            String name = plugin.name();
            File pluginFile = new File(pluginsFolder, name.replace(':', '_') + ".jar");

            printInfo("downloading plugin \"" + name + "\"...");
            Optional<String> error = downloadFromPluginSource(
                    gitHubResolver,
                    downloadProgressConsumer,
                    plugin.source(),
                    pluginFile
            );
            if(error.isPresent()) {
                return Optional.of("problem downloading plugin \"" + name + "\": " + error.get());
            }
            System.out.println();

            pluginsFiles.put(name, pluginFile);
        }

        // install configs
        Origin origin = project.origin();

        String[] unboxedRepo = origin.repo().split("/", 2);
        if(unboxedRepo.length != 2) return Optional.of("malformed origin repo format");

        File configsFolder = new File(tempFolder, "configs/");
        Map<String, File> configsFiles = new HashMap<>();
        configsFolder.mkdirs();

        String projectPath = origin.path();
        int lastSeparator = projectPath.lastIndexOf('/');
        String basePath;
        if(lastSeparator == -1) basePath = "";
        else basePath = projectPath.substring(0, lastSeparator + 1);

        for (Config config : configsRepository.configs()) {
            File configFile = new File(configsFolder, config.path());
            configFile.getParentFile().mkdirs();

            if(!gitHubResolver.downloadFile(downloadProgressConsumer, unboxedRepo[0], unboxedRepo[1], origin.branch(), basePath + config.path(), configFile).block()) {
                printWarning("unable to resolve " + config.path() + " config file.");
                continue;
            }

            configsFiles.put(config.path(), configFile);
        }

        printInfo("copying everything...");
        // copy everything and process configs
        moveFile(coreFile, new File(workingDirectory, "server.jar"));

        File outPluginsFolder = new File(workingDirectory, "plugins/");
        outPluginsFolder.mkdirs();
        if(hitori != null) {
            moveFile(hitoriFile, new File(outPluginsFolder, "hitori.jar"));

            File outHitoriFolder = new File(outPluginsFolder, "hitori/");
            outHitoriFolder.mkdir();
            for (File moduleFile : modulesFiles.values()) {
                moveFile(moduleFile, new File(outHitoriFolder, moduleFile.getName()));
            }
        }

        for (File pluginFile : pluginsFiles.values()) {
            moveFile(pluginFile, new File(outPluginsFolder, pluginFile.getName()));
        }

        for (Map.Entry<String, File> entry : configsFiles.entrySet()) {
            String path = entry.getKey();
            File configFile = entry.getValue();
            try {
                copyConfigAndProcessPlaceholders(configFile, new File(workingDirectory, path), variables);
            }
            catch (Exception e) {
                return Optional.of("problem copying config \"" + path + "\": " + e.getMessage());
            }
        }

        printInfo("installed!");

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
        catch (Exception e) {
            e.printStackTrace();
            return Optional.of("malformed yaml file");
        }

        // READ PROJECT
        YamlSection projectSection = config.getSection("project");
        if(projectSection == null)
            return Optional.of("\"project\" section doesn't exists.");

        ConfigsRepository configsRepository = new ConfigsRepository();

        String name = projectSection.getString("name", "");
        String description = projectSection.getString("description", "");
        if(name.isEmpty() || description.isEmpty())
            return Optional.of("name or description is empty");

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

        String rawCoreType = coreSection.getString("type", "");
        CoreType coreType;
        try {
            coreType = CoreType.valueOf(rawCoreType.toUpperCase());
        }
        catch (Exception _) {
            return Optional.of("wrong core type \"" + rawCoreType + "\"");
        }

        String coreParseError = null;
        Core core = switch (coreType) {
            case PAPER, PURPUR -> {
                String minecraftVersion = coreSection.getString("minecraft_version", "");
                String version = coreSection.getString("version", "");
                if(minecraftVersion.isEmpty() || version.isEmpty()) {
                    coreParseError = "minecraft_version or version of core is empty";
                    yield null;
                }

                yield coreType == CoreType.PAPER ? Core.paper(configsRepository, minecraftVersion, version) : Core.purpur(configsRepository, minecraftVersion, version);
            }
        };

        if(core == null) return Optional.of(coreParseError);

        // READ PLUGINS
        YamlSection pluginsSection = config.getSection("plugins");
        List<Plugin> plugins;
        if(pluginsSection == null) plugins = List.of();
        else {
            plugins = new ArrayList<>();
            for (String pluginName : pluginsSection.keySet()) {
                YamlSection pluginSection = pluginsSection.getSection(pluginName);
                if(pluginSection == null) continue;

                YamlSection pluginSourceSection = pluginSection.getSection("source");
                if(pluginSourceSection == null)
                    return Optional.of("plugin \"" + pluginName + "\" doesn't have source.");

                PluginSourceOrError pluginSourceOrError = readPluginSource(pluginName, pluginSourceSection, false);
                if(pluginSourceOrError.error != null) return Optional.of(pluginSourceOrError.error);

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

                plugins.add(new Plugin(
                        pluginName,
                        pluginSourceOrError.pluginSource,
                        configs
                ));
            }
        }

        // read hitori
        YamlSection hitoriSection = config.getSection("hitori");
        Hitori hitori;
        if(hitoriSection == null) hitori = null;
        else {
            String version = hitoriSection.getString("version", "");
            if(version.isEmpty()) return Optional.of("hitori version is empty");

            YamlSection modulesSection = hitoriSection.getSection("modules");
            List<HitoriModule> modules;
            if(modulesSection == null) modules = List.of();
            else {
                modules = new ArrayList<>();
                for (String moduleKey : modulesSection.keySet()) {
                    YamlSection moduleSection = modulesSection.getSection(moduleKey);
                    if(moduleSection == null) continue;

                    YamlSection moduleSourceSection = moduleSection.getSection("source");
                    if(moduleSourceSection == null)
                        return Optional.of("module \"" + moduleKey + "\" doesn't have source.");

                    PluginSourceOrError pluginSourceOrError = readPluginSource(moduleKey, moduleSourceSection, true);
                    if(pluginSourceOrError.error != null) return Optional.of(pluginSourceOrError.error);

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

                    modules.add(new HitoriModule(moduleKey, pluginSourceOrError.pluginSource, configs));
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
                autoUpdate,
                tokens,
                variables,
                core,
                hitori,
                plugins
        );
        this.configsRepository = configsRepository;

        return Optional.empty();
    }

    private PluginSourceOrError readPluginSource(String parentName, YamlSection sourceSection, boolean module) {
        String rawPluginSourceType = sourceSection.getString("type", "");
        PluginSourceType pluginSourceType;
        try {
            pluginSourceType = PluginSourceType.valueOf(rawPluginSourceType.toUpperCase());
        }
        catch (Exception _) {
            return PluginSourceOrError.error(String.format(
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
                if(rawUri.isEmpty()) return PluginSourceOrError.error(String.format(
                        "%s \"%s\" source has empty uri",
                        module ? "module" : "plugin",
                        parentName
                ));
                try {
                    pluginSource = PluginSource.createDirect(URI.create(rawUri));
                }
                catch (Exception _) {
                    return PluginSourceOrError.error(String.format(
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
                    return PluginSourceOrError.error(String.format(
                            "%s \"%s\" source repo, tag or asset is empty",
                            module ? "module" : "plugin",
                            parentName
                    ));

                pluginSource = PluginSource.createGithub(pluginRepo, tag, asset);
            }
            default -> pluginSource = null;
        }

        return PluginSourceOrError.pluginSource(pluginSource);
    }



    private record PluginSourceOrError(PluginSource pluginSource, String error) {
        static PluginSourceOrError error(String error) {
            return new PluginSourceOrError(null, error);
        }

        static PluginSourceOrError pluginSource(PluginSource pluginSource) {
            return new PluginSourceOrError(pluginSource, null);
        }
    }

}
