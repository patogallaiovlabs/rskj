package co.rsk.cli.tools;

import org.bitcoin.NativeSecp256k1;
import org.bitcoin.Secp256k1Context;
import org.ethereum.crypto.ECKey;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.SplittableRandom;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone probe to isolate native/direct-memory behavior around NativeSecp256k1.
 *
 * Usage:
 *   java -cp rskj-core/build/libs/rskj-core-<version>-all.jar co.rsk.cli.tools.Secp256k1NativeMemoryProbe
 */
public final class Secp256k1NativeMemoryProbe {

    private static final int DEFAULT_THREADS = 200;
    private static final int DEFAULT_OPS_PER_THREAD = 0;
    private static final int DEFAULT_HOLD_SECONDS = 600;
    private static final int DEFAULT_REPORT_EVERY_SECONDS = 5;
    private static final int DEFAULT_INVALID_RATE_PERCENT = 50;

    private Secp256k1NativeMemoryProbe() {
        // no-op
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        if (config.help) {
            printUsage();
            return;
        }

        requireNativeSecp();

        System.out.printf(Locale.ROOT,
                "Starting probe. pid=%d threads=%d opsPerThread=%d holdSeconds=%d reportEverySeconds=%d invalidRatePercent=%d%n",
                ProcessHandle.current().pid(),
                config.threads,
                config.opsPerThread,
                config.holdSeconds,
                config.reportEverySeconds,
                config.invalidRatePercent);

        AtomicLong totalOps = new AtomicLong();
        AtomicLong validCases = new AtomicLong();
        AtomicLong invalidCases = new AtomicLong();
        AtomicLong verifyTrue = new AtomicLong();
        AtomicLong verifyFalse = new AtomicLong();
        AtomicLong verifyErrors = new AtomicLong();
        CountDownLatch workersDone = new CountDownLatch(config.threads);

        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "secp256k1-probe-reporter");
            t.setDaemon(true);
            return t;
        });

        Instant startedAt = Instant.now();
        reporter.scheduleAtFixedRate(
                () -> reportMemory(startedAt, totalOps, validCases, invalidCases, verifyTrue, verifyFalse, verifyErrors),
                0,
                config.reportEverySeconds,
                TimeUnit.SECONDS
        );

        for (int i = 0; i < config.threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    runWorker(config.opsPerThread, config.holdSeconds, config.invalidRatePercent, totalOps, validCases, invalidCases, verifyTrue, verifyFalse, verifyErrors);
                } finally {
                    workersDone.countDown();
                }
            }, "secp256k1-probe-worker-" + i);
            t.start();
        }

        workersDone.await();

        reporter.shutdownNow();
        System.out.println("Probe finished.");
    }

    private static void runWorker(
            int opsPerThread,
            int holdSeconds,
            int invalidRatePercent,
            AtomicLong totalOps,
            AtomicLong validCases,
            AtomicLong invalidCases,
            AtomicLong verifyTrue,
            AtomicLong verifyFalse,
            AtomicLong verifyErrors) {
        byte[] data = new byte[32];
        SplittableRandom random = new SplittableRandom();
        ECKey key = new ECKey();
        byte[] pub = key.getPubKey(true);
        byte[] priv = key.getPrivKeyBytes();

        if (priv == null || pub == null) {
            throw new IllegalStateException("Generated ECKey without private/public material");
        }

        if (opsPerThread == 0) {
            while (!Thread.currentThread().isInterrupted()) {
                runSingleCase(random, invalidRatePercent, data, pub, priv, totalOps, validCases, invalidCases, verifyTrue, verifyFalse, verifyErrors);
            }
            return;
        }

        for (int i = 0; i < opsPerThread; i++) {
            runSingleCase(random, invalidRatePercent, data, pub, priv, totalOps, validCases, invalidCases, verifyTrue, verifyFalse, verifyErrors);
        }

        // Keep worker threads alive to make ThreadLocal direct-buffer retention visible.
        if (holdSeconds == 0) {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TimeUnit.DAYS.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return;
        }

        try {
            TimeUnit.SECONDS.sleep(holdSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runSingleCase(
            SplittableRandom random,
            int invalidRatePercent,
            byte[] data,
            byte[] pub,
            byte[] priv,
            AtomicLong totalOps,
            AtomicLong validCases,
            AtomicLong invalidCases,
            AtomicLong verifyTrue,
            AtomicLong verifyFalse,
            AtomicLong verifyErrors) {
        fillRandom(random, data);
        try {
            byte[] signature = NativeSecp256k1.sign(data, priv);

            boolean invalidCase = random.nextInt(100) < invalidRatePercent;
            if (invalidCase) {
                invalidCases.incrementAndGet();
                // Corrupt one random bit so this case is intentionally invalid.
                int index = random.nextInt(signature.length);
                signature[index] ^= (byte) (1 << random.nextInt(8));
            } else {
                validCases.incrementAndGet();
            }

            boolean result = NativeSecp256k1.verify(data, signature, pub);
            if (result) {
                verifyTrue.incrementAndGet();
            } else {
                verifyFalse.incrementAndGet();
            }
        } catch (Exception e) {
            // Never fail the probe loop; keep collecting memory behavior.
            verifyErrors.incrementAndGet();
        } finally {
            totalOps.incrementAndGet();
        }
    }

    private static void fillRandom(SplittableRandom random, byte[] target) {
        for (int i = 0; i < target.length; i++) {
            target[i] = (byte) random.nextInt(256);
        }
    }

    private static void requireNativeSecp() {
        if (Secp256k1Context.isEnabled()) {
            return;
        }

        Throwable loadError = Secp256k1Context.getLoadError();
        String message = "Native secp256k1 is not enabled. "
                + "Ensure libsecp256k1 is available in java.library.path / LD_LIBRARY_PATH.";

        if (loadError == null) {
            throw new IllegalStateException(message);
        }
        throw new IllegalStateException(message, loadError);
    }

    private static void reportMemory(
            Instant startedAt,
            AtomicLong totalOps,
            AtomicLong validCases,
            AtomicLong invalidCases,
            AtomicLong verifyTrue,
            AtomicLong verifyFalse,
            AtomicLong verifyErrors) {
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();

        BufferPoolSnapshot direct = getBufferPool("direct");
        BufferPoolSnapshot mapped = getBufferPool("mapped");

        long uptime = Duration.between(startedAt, Instant.now()).toSeconds();

        System.out.printf(Locale.ROOT,
                "t=%4ds ops=%d validCases=%d invalidCases=%d verifyTrue=%d verifyFalse=%d verifyErrors=%d heapUsed=%s heapCommitted=%s nonHeapUsed=%s directUsed=%s directCap=%s directCount=%d mappedUsed=%s mappedCount=%d liveThreads=%d%n",
                uptime,
                totalOps.get(),
                validCases.get(),
                invalidCases.get(),
                verifyTrue.get(),
                verifyFalse.get(),
                verifyErrors.get(),
                mb(heap.getUsed()),
                mb(heap.getCommitted()),
                mb(nonHeap.getUsed()),
                mb(direct.memoryUsed),
                mb(direct.totalCapacity),
                direct.count,
                mb(mapped.memoryUsed),
                mapped.count,
                Thread.getAllStackTraces().size());
    }

    private static BufferPoolSnapshot getBufferPool(String poolName) {
        List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        for (BufferPoolMXBean pool : pools) {
            if (poolName.equals(pool.getName())) {
                return new BufferPoolSnapshot(pool.getMemoryUsed(), pool.getTotalCapacity(), pool.getCount());
            }
        }
        return new BufferPoolSnapshot(-1L, -1L, -1L);
    }

    private static String mb(long bytes) {
        if (bytes < 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.2fMB", bytes / (1024.0 * 1024.0));
    }

    private static void printUsage() {
        System.out.println("Secp256k1NativeMemoryProbe usage:");
        System.out.println("  --threads=<int>             Number of worker threads (default: " + DEFAULT_THREADS + ")");
        System.out.println("  --ops-per-thread=<int>      verify() calls per worker before hold (0 = non-stop work, default: " + DEFAULT_OPS_PER_THREAD + ")");
        System.out.println("  --hold-seconds=<int>        Hold window after work (0 = forever, default: " + DEFAULT_HOLD_SECONDS + ")");
        System.out.println("  --report-every-seconds=<int> Reporting cadence (default: " + DEFAULT_REPORT_EVERY_SECONDS + ")");
        System.out.println("  --invalid-rate-percent=<int> Percentage of intentionally invalid signatures [0..100] (default: " + DEFAULT_INVALID_RATE_PERCENT + ")");
        System.out.println("  --help                      Show help");
    }

    private static final class BufferPoolSnapshot {
        private final long memoryUsed;
        private final long totalCapacity;
        private final long count;

        private BufferPoolSnapshot(long memoryUsed, long totalCapacity, long count) {
            this.memoryUsed = memoryUsed;
            this.totalCapacity = totalCapacity;
            this.count = count;
        }
    }

    private static final class Config {
        private final int threads;
        private final int opsPerThread;
        private final int holdSeconds;
        private final int reportEverySeconds;
        private final int invalidRatePercent;
        private final boolean help;

        private Config(int threads, int opsPerThread, int holdSeconds, int reportEverySeconds, int invalidRatePercent, boolean help) {
            this.threads = threads;
            this.opsPerThread = opsPerThread;
            this.holdSeconds = holdSeconds;
            this.reportEverySeconds = reportEverySeconds;
            this.invalidRatePercent = invalidRatePercent;
            this.help = help;
        }

        private static Config parse(String[] args) {
            int threads = DEFAULT_THREADS;
            int opsPerThread = DEFAULT_OPS_PER_THREAD;
            int holdSeconds = DEFAULT_HOLD_SECONDS;
            int reportEverySeconds = DEFAULT_REPORT_EVERY_SECONDS;
            int invalidRatePercent = DEFAULT_INVALID_RATE_PERCENT;
            boolean help = false;

            for (String arg : args) {
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                    continue;
                }

                if (arg.startsWith("--threads=")) {
                    threads = positiveInt(arg, "--threads=");
                } else if (arg.startsWith("--ops-per-thread=")) {
                    opsPerThread = nonNegativeInt(arg, "--ops-per-thread=");
                } else if (arg.startsWith("--hold-seconds=")) {
                    holdSeconds = nonNegativeInt(arg, "--hold-seconds=");
                } else if (arg.startsWith("--report-every-seconds=")) {
                    reportEverySeconds = positiveInt(arg, "--report-every-seconds=");
                } else if (arg.startsWith("--invalid-rate-percent=")) {
                    invalidRatePercent = percent(arg, "--invalid-rate-percent=");
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            return new Config(threads, opsPerThread, holdSeconds, reportEverySeconds, invalidRatePercent, help);
        }

        private static int positiveInt(String arg, String prefix) {
            String value = arg.substring(prefix.length());
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(prefix + " expects > 0, got " + parsed);
            }
            return parsed;
        }

        private static int nonNegativeInt(String arg, String prefix) {
            String value = arg.substring(prefix.length());
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(prefix + " expects >= 0, got " + parsed);
            }
            return parsed;
        }

        private static int percent(String arg, String prefix) {
            String value = arg.substring(prefix.length());
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 100) {
                throw new IllegalArgumentException(prefix + " expects a value in [0..100], got " + parsed);
            }
            return parsed;
        }
    }
}


