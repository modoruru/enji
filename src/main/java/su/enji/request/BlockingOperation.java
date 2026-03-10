package su.enji.request;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class BlockingOperation<V> {

    private final CompletableFuture<V> future;

    private BlockingOperation(CompletableFuture<V> future) {
        this.future = future;
    }

    public boolean done() {
        return future.isDone();
    }

    public void cancel(boolean interrupt) {
        future.cancel(interrupt);
    }

    public V block() {
        try {
            return future.join();
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void subscribe(Consumer<V> consumer) {
        future.thenAccept(consumer);
    }

    public CompletableFuture<V> backend() {
        return future;
    }

    public static <V> BlockingOperation<V> run(ExecutorService executorService, Supplier<V> supplier) {
        CompletableFuture<V> future = new CompletableFuture<>();
        executorService.execute(() -> future.complete(supplier.get()));
        return new BlockingOperation<>(future);
    }

    public static BlockingOperation<Void> runWithoutReturn(ExecutorService executorService, Runnable runnable) {
        return run(executorService, () -> {
            runnable.run();
            return null;
        });
    }

}
