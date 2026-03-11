package su.enji.model;

import org.jetbrains.annotations.Nullable;
import su.enji.model.core.Core;
import su.enji.model.hitori.Hitori;
import su.enji.model.plugin.Plugin;

import java.util.List;
import java.util.Set;

// project collected from config
public record Project(Origin origin,

                      String name,
                      String description,
                      String jvmArgs,
                      boolean autoUpdate,
                      Set<Token> tokens,
                      List<Variable> variables,

                      Core core,

                      List<Plugin> plugins,

                      @Nullable Hitori hitori
) {

}
