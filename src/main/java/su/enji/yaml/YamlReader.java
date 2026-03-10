package su.enji.yaml;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class YamlReader implements YamlSection {

    private final Yaml yaml;
    private Map<String, Object> backend;
    private MapYamlSection config;

    public YamlReader() {
        this.yaml = new Yaml();
    }

    public void load(File file) {
        try (InputStream inputStream = new FileInputStream(file)) {
            backend = yaml.load(inputStream);
            config = new MapYamlSection(backend);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void load(InputStream inputStream) {
        backend = yaml.load(inputStream);
    }

    @Override
    public Set<String> keySet() {
        return config.keySet();
    }

    @Override
    public <T> T get(String key, T defaultValue) {
        return config.get(key, defaultValue);
    }

    @Override
    public YamlSection getSection(String key) {
        return config.getSection(key);
    }

    @Override
    public List<YamlSection> getSectionsList(String key) {
        return config.getSectionsList(key);
    }

}
