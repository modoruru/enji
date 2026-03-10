package su.enji.model.hitori;

import su.enji.model.config.Config;
import su.enji.model.config.ConfigSource;
import su.enji.model.config.ConfigsRepository;

import java.util.List;

public final class Hitori {

    private final String version;
    private final Config config;
    private final List<HitoriModule> modules;

    private Hitori(String version, Config config, List<HitoriModule> modules) {
        this.version = version;
        this.config = config;
        this.modules = modules;
    }

    public String version() {
        return version;
    }

    public Config config() {
        return config;
    }

    public List<HitoriModule> modules() {
        return modules;
    }

    public static Hitori create(ConfigsRepository configsRepository, String version, List<HitoriModule> modules) {
        return new Hitori(version, configsRepository.create(ConfigSource.HITORI, "hitori/config/config.yml"), modules);
    }


}
