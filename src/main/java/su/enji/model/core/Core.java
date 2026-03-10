package su.enji.model.core;

import org.jetbrains.annotations.Nullable;
import su.enji.model.config.Config;
import su.enji.model.config.ConfigSource;
import su.enji.model.config.ConfigsRepository;

import java.util.List;

public final class Core {

    private final CoreType type;
    private final List<Config> configs;

    // paper, purpur
    @Nullable
    private final String minecraftVersion;
    @Nullable
    private final String version;

    private Core(CoreType type, List<Config> configs, @Nullable String minecraftVersion, @Nullable String version) {
        this.type = type;
        this.configs = configs;
        this.minecraftVersion = minecraftVersion;
        this.version = version;
    }

    public CoreType type() {
        return type;
    }

    public List<Config> configs() {
        return configs;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public String version() {
        return version;
    }

    public static Core paper(ConfigsRepository configsRepository, String minecraftVersion, String version) {
        return new Core(
                CoreType.PAPER,
                configsRepository.createMany(
                        ConfigSource.CORE,
                        "server.properties",
                        "bukkit.yml",
                        "spigot.yml",
                        "config/paper-global.yml",
                        "config/paper-world-defaults.yml"
                ),
                minecraftVersion,
                version
        );
    }

    public static Core purpur(ConfigsRepository configsRepository, String minecraftVersion, String version) {
        return new Core(
                CoreType.PURPUR,
                configsRepository.createMany(
                        ConfigSource.CORE,
                        "server.properties",
                        "bukkit.yml",
                        "spigot.yml",
                        "config/paper-global.yml",
                        "config/paper-world-defaults.yml",
                        "purpur.yml"
                ),
                minecraftVersion,
                version
        );
    }

}
