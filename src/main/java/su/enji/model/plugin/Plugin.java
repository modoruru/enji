package su.enji.model.plugin;

import su.enji.model.config.Config;

import java.util.List;

public record Plugin(String name, PluginSource source, List<Config> configs) {
}
