package su.enji.model.core;

import org.jetbrains.annotations.Nullable;
import su.enji.model.config.Config;
import su.enji.model.config.ConfigSource;
import su.enji.model.config.ConfigsRepository;

import java.util.List;

public final class Core {

    private final CoreBrand brand;
    private final List<Config> configs;

    // paper, purpur
    @Nullable
    private final String minecraftVersion;
    @Nullable
    private final String build;

    private Core(CoreBrand brand, List<Config> configs, @Nullable String minecraftVersion, @Nullable String build) {
        this.brand = brand;
        this.configs = configs;
        this.minecraftVersion = minecraftVersion;
        this.build = build;
    }

    public CoreBrand brand() {
        return brand;
    }

    public List<Config> configs() {
        return configs;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public String build() {
        return build;
    }

    public static Core velocity(ConfigsRepository configsRepository, String version, String build) {
        return new Core(
                CoreBrand.VELOCITY,
                configsRepository.createMany(
                        ConfigSource.CORE,
                        "velocity.toml"
                ),
                version,
                build
        );
    }

    public static Core paper(ConfigsRepository configsRepository, String minecraftVersion, String build) {
        return new Core(
                CoreBrand.PAPER,
                configsRepository.createMany(
                        ConfigSource.CORE,
                        "server.properties",
                        "bukkit.yml",
                        "spigot.yml",
                        "config/paper-global.yml",
                        "config/paper-world-defaults.yml"
                ),
                minecraftVersion,
                build
        );
    }

    public static Core purpur(ConfigsRepository configsRepository, String minecraftVersion, String build) {
        return new Core(
                CoreBrand.PURPUR,
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
                build
        );
    }

}
