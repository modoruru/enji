package su.enji.model.core;

import su.enji.core.CoreResolver;
import su.enji.core.PaperCoreResolver;
import su.enji.core.PurpurCoreResolver;

import java.util.concurrent.ExecutorService;
import java.util.function.Function;

public enum CoreBrand {

    PAPER(PaperCoreResolver::createPaper),
    PURPUR(PurpurCoreResolver::create),
    VELOCITY(PaperCoreResolver::createVelocity);

    private final Function<ExecutorService, CoreResolver> coreResolverFunction;

    CoreBrand(Function<ExecutorService, CoreResolver> coreResolverFunction) {
        this.coreResolverFunction = coreResolverFunction;
    }

    public CoreResolver createCoreResolver(ExecutorService executorService) {
        return coreResolverFunction.apply(executorService);
    }

}
