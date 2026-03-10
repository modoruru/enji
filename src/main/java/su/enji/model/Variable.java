package su.enji.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

public record Variable(@NotNull String name, @Nullable String description, @Nullable Pattern regex, @Nullable String defaultValue) {

    public boolean matches(String value) {
        if(regex == null) return true;

        return regex.matcher(value).find();
    }

}
