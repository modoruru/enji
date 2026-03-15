package su.enji.model.hitori;

import su.enji.Enji;
import su.enji.github.GitHubResolver;
import su.enji.model.config.Config;
import su.enji.model.config.ConfigSource;
import su.enji.model.config.ConfigsRepository;

import java.util.Map;

public final class Hitori {

    private final String version;
    private final Config config;
    private final Map<String, HitoriModule> modules;

    private Hitori(String version, Config config, Map<String, HitoriModule> modules) {
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

    public Map<String, HitoriModule> modules() {
        return modules;
    }

    public static int resolveReleaseId(Hitori hitori, GitHubResolver gitHubResolver) {
        return (hitori.version().equalsIgnoreCase("%latest%")
                ? gitHubResolver.latestRelease(Enji.HITORI_REPO_OWNER, Enji.HITORI_REPO)
                : gitHubResolver.releaseIdByTag(Enji.HITORI_REPO_OWNER, Enji.HITORI_REPO, hitori.version())).block();
    }

    public static Hitori create(ConfigsRepository configsRepository, String version, Map<String, HitoriModule> modules) {
        return new Hitori(version, configsRepository.create(ConfigSource.HITORI, "plugins/hitori/config/config.yml"), modules);
    }


}
