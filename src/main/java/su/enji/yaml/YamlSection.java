package su.enji.yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public interface YamlSection {

    Set<String> keySet();

    <T> T get(String key, T defaultValue);

    default String getString(String key, String defaultValue) {
        Object obj = get(key, null);

        if(obj == null) return defaultValue;
        if(obj instanceof String asString) return asString;

        return obj.toString();
    }

    default int getInt(String key, int defaultValue) {
        String rawInt = getString(key, null);
        if(rawInt == null) return defaultValue;

        try {
            return Integer.parseInt(rawInt);
        }
        catch (Exception _) {
        }

        return defaultValue;
    }

    default boolean getBoolean(String key, boolean defaultValue) {
        Object obj = get(key, null);
        switch (obj) {
            case null -> {
                return defaultValue;
            }
            case Boolean asBoolean -> {
                return asBoolean;
            }
            case String asString -> {
                try {
                    return Boolean.parseBoolean(asString);
                }
                catch (Exception _) {}
            }
            default -> {
            }
        }

        return defaultValue;
    }

    default double getDouble(String key, double defaultValue) {
        String rawDouble = getString(key, null);
        if(rawDouble == null) return defaultValue;

        try {
            return Double.parseDouble(rawDouble);
        }
        catch (Exception _) {
        }

        return defaultValue;
    }

    default List<String> getStringList(String key) {
        List<Object> list = get(key, null);
        if(list == null) return List.of();

        List<String> result = new ArrayList<>();
        for (Object o : list) {
            result.add(o.toString());
        }

        return List.copyOf(result);
    }

    YamlSection getSection(String key);

    List<YamlSection> getSectionsList(String key);

}
