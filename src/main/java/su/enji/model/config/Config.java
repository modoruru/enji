package su.enji.model.config;

public final class Config {

    private final ConfigSource source;
    private final String path;

    Config(ConfigSource source, String path) {
        this.source = source;
        this.path = path;
    }

    public ConfigSource source() {
        return source;
    }

    public String path() {
        return path;
    }

}
