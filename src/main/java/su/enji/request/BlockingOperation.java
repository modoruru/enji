package su.enji.request;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class BlockingOperation<V> {

    private final AtomicBoolean waiting;
    private @Nullable V result;
    private @Nullable Throwable throwable;

    private BlockingOperation() {
        this.waiting = new AtomicBoolean(true);
    }

    public V block() {
        while (waiting.get()) {
            Thread.onSpinWait();
        }

        if(throwable != null) throw new RuntimeException(throwable);
        return result;
    }

    public static <V> BlockingOperation<V> run(ExecutorService executorService, Supplier<V> supplier) {
        BlockingOperation<V> blockingOperation = new BlockingOperation<>();
        try {
            executorService.execute(() -> {
                try {
                    blockingOperation.result = supplier.get();
                }
                catch (Throwable throwable) {
                    blockingOperation.throwable = throwable;
                }
                finally {
                    blockingOperation.waiting.set(false);
                }
            });
        }
        catch (Throwable throwable) {
            blockingOperation.throwable = throwable;
            blockingOperation.waiting.set(false);
        }
        return blockingOperation;
    }

    public static BlockingOperation<Void> runWithoutReturn(ExecutorService executorService, Runnable runnable) {
        return run(executorService, () -> {
            runnable.run();
            return null;
        });
    }

}
