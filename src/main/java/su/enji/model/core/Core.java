package su.enji.model.core;

import su.enji.core.CoreResolver;
import su.enji.model.config.Config;
import su.enji.model.config.ConfigSource;
import su.enji.model.config.ConfigsRepository;

import java.util.List;

public final class Core {

    private final CoreBrand brand;
    private final List<Config> configs;

    private final String minecraftVersion;
    private final String build;

    private Core(CoreBrand brand, List<Config> configs, String minecraftVersion, String build) {
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

    public boolean same(Core that) {
        if((this.brand == CoreBrand.VELOCITY) != (that.brand == CoreBrand.VELOCITY)) return false;

        return this.brand == that.brand && this.minecraftVersion.equalsIgnoreCase(that.minecraftVersion);
    }

    public static int resolveBuildId(Core core, CoreResolver coreResolver) {
        assert core.build != null;

        int coreBuildId;
        if(core.build.equalsIgnoreCase("%latest%")) coreBuildId = coreResolver.latestBuild(core.minecraftVersion).block();
        else {
            try {coreBuildId = Integer.parseInt(core.build);}
            catch (Exception _) {coreBuildId = -1;}
        }

        return coreBuildId;
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
        return paperBased(CoreBrand.PAPER, configsRepository, minecraftVersion, build);
    }

    public static Core folia(ConfigsRepository configsRepository, String minecraftVersion, String build) {
        return paperBased(CoreBrand.FOLIA, configsRepository, minecraftVersion, build);
    }

    private static Core paperBased(CoreBrand brand, ConfigsRepository configsRepository, String minecraftVersion, String build) {
        return new Core(
                brand,
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
