package su.enji.model.config;

import java.util.*;

public final class ConfigsRepository {

    private final Map<String, Config> configs;

    public ConfigsRepository() {
        configs = new HashMap<>();
    }

    public Config config(String path) {
        return configs.get(path);
    }

    public Set<Config> configs() {
        return Set.copyOf(configs.values());
    }

    public Config create(ConfigSource source, String path) {
        if(configs.containsKey(path)) throw new IllegalArgumentException("config \"" + path + "\" already exists.");

        Config config = new Config(source, path);
        configs.put(path, config);
        return config;
    }

    public List<Config> createMany(ConfigSource source, String... paths) {
        List<Config> result = new ArrayList<>();
        for (String path : paths) {
            result.add(create(source, path));
        }
        return List.copyOf(result);
    }

}
