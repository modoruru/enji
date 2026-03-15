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

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof PluginSource that)) return false;
        if(this.type != that.type) return false;

        return switch (this.type) {
            case DIRECT -> {
                assert this.uri != null;
                yield this.uri.equals(that.uri);
            }
            case GITHUB -> {
                assert this.repo != null && this.tag != null && this.asset != null;

                yield this.repo.equalsIgnoreCase(that.repo)
                        && this.tag.equalsIgnoreCase(that.tag)
                        && this.asset.equalsIgnoreCase(that.asset);
            }
        };
    }


    public static PluginSource createGithub(String repo, String tag, String asset) {
        return new PluginSource(PluginSourceType.GITHUB, repo, tag, asset, null);
    }

    public static PluginSource createDirect(URI uri) {
        return new PluginSource(PluginSourceType.DIRECT, null, null, null, uri);
    }

}
