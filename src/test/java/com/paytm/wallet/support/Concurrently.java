package com.paytm.wallet.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Fire N tasks with heavy overlap (single release gate) and collect their results. */
public final class Concurrently {

    private Concurrently() {
    }

    public static <T> List<T> run(int n, IndexedTask<T> task) {
        int threads = Math.min(n, 128);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    return task.run(idx);
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>(n);
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface IndexedTask<T> {
        T run(int index) throws Exception;
    }
}
