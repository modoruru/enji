package su.enji.yaml;

import java.util.*;

final class MapYamlSection implements YamlSection {

    private final Map<String, Object> backend;
    private final Map<String, YamlSection> sectionIndex;

    MapYamlSection(Map<String, Object> backend) {
        this.backend = backend;
        this.sectionIndex = new HashMap<>();
    }

    @Override
    public Set<String> keySet() {
        return Set.copyOf(backend.keySet());
    }

    @Override
    public <T> T get(String key, T defaultValue) {
        if(backend == null) return defaultValue;

        T value;
        try {
            value = (T) backend.get(key);
            if(value != null) return value;
        }
        catch (Exception _) {
        }

        return defaultValue;
    }

    @Override
    public YamlSection getSection(String key) {
        if(sectionIndex.containsKey(key))
            return sectionIndex.get(key);

        Map<String, Object> backingMap = get(key, null);
        if(backingMap == null) {
            sectionIndex.put(key, null);
            return null;
        }

        MapYamlSection mapYamlSection = new MapYamlSection(backingMap);
        sectionIndex.put(key, mapYamlSection);
        return mapYamlSection;
    }

    @Override
    public List<YamlSection> getSectionsList(String key) {
        List<Map<String, Object>> list = get(key, null);
        if(list == null) return null;

        List<YamlSection> sections = new ArrayList<>();
        for (Map<String, Object> rawSection : list) {
            sections.add(new MapYamlSection(rawSection));
        }
        return sections;
    }

}
