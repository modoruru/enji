package su.enji.model.hitori;

import su.enji.model.config.Config;
import su.enji.model.plugin.PluginSource;

import java.util.List;

public record HitoriModule(String name, PluginSource source, List<Config> configs) {
}
