package su.enji.model.plugin;

import org.jetbrains.annotations.Nullable;

import java.net.URI;

public final class PluginSource {

    private final PluginSourceType type;
    @Nullable
    private final String repo, tag, asset;
    @Nullable
    private final URI uri;

    private PluginSource(PluginSourceType type, @Nullable String repo, @Nullable String tag, @Nullable String asset, @Nullable URI uri) {
        this.type = type;
        this.repo = repo;
        this.tag = tag;
        this.asset = asset;
        this.uri = uri;
    }

    public PluginSourceType type() {
        return type;
    }

    public String repo() {
        return repo;
    }

    public String tag() {
        return tag;
    }

    public String asset() {
        return asset;
    }

    public URI uri() {
        return uri;
    }

    public static PluginSource createGithub(String repo, String tag, String asset) {
        return new PluginSource(PluginSourceType.GITHUB, repo, tag, asset, null);
    }

    public static PluginSource createDirect(URI uri) {
        return new PluginSource(PluginSourceType.DIRECT, null, null, null, uri);
    }

}
