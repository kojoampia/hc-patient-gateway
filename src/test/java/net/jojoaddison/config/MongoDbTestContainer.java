package net.jojoaddison.config;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

public class MongoDbTestContainer implements InitializingBean, DisposableBean {

    /* private final long memoryInBytes = Math.round(1024 * 1024 * 1024 * 0.6);
    private final long memorySwapInBytes = Math.round(1024 * 1024 * 1024 * 0.8);
    private final long nanoCpu = Math.round(1_000_000_000L * 0.1); */
    private static final Logger log = LoggerFactory.getLogger(MongoDbTestContainer.class);

    /**
     * How many times to try starting the container before giving up.
     *
     * <p>Testcontainers waits for the single-node replica set for 60 attempts 100 ms apart — about six
     * seconds — and {@code AWAIT_INIT_REPLICA_SET_ATTEMPTS} is a {@code private static final int} inside
     * {@code MongoDBContainer}, so nothing here can widen that window. On a loaded machine it is missed,
     * and the failure spreads: the customizer factory assigns its static bean only after the container has
     * started, so every following test class builds its own container and gets its own six seconds to miss,
     * while classes sharing a failed context report {@code ApplicationContext failure threshold (1)
     * exceeded} without starting anything. One busy moment reads as many red classes in unrelated tests.
     * Retrying the start is the only lever left. See {@code docs/backlog.md} item 9.
     */
    private static final int START_ATTEMPTS = 3;

    private static final long RETRY_BACKOFF_MILLIS = 3_000L;

    private MongoDBContainer mongodbContainer;

    @Override
    public void destroy() {
        if (null != mongodbContainer && mongodbContainer.isRunning()) {
            mongodbContainer.stop();
        }
    }

    @Override
    public void afterPropertiesSet() {
        if (null == mongodbContainer) {
            mongodbContainer = buildContainer();
        }
        startWithRetries();
    }

    public MongoDBContainer getMongoDBContainer() {
        return mongodbContainer;
    }

    /**
     * Builds a container but does not start it. Kept separate from {@link #afterPropertiesSet()} on purpose:
     * the retry loop rebuilds through here rather than by calling {@code afterPropertiesSet()} again, which
     * would nest the attempts inside each other.
     */
    private MongoDBContainer buildContainer() {
        return new MongoDBContainer("mongo:7.0.6")
            .withTmpFs(Map.of("/testtmpfs", "rw"))
            /* .withCommand(
                "--nojournal --wiredTigerCacheSizeGB 0.25 --wiredTigerCollectionBlockCompressor none --slowOpSampleRate 0 --setParameter ttlMonitorEnabled=false --setParameter diagnosticDataCollectionEnabled=false --setParameter logicalSessionRefreshMillis=6000000 --setParameter enableFlowControl=false --setParameter oplogFetcherUsesExhaust=false --setParameter disableResumableRangeDeleter=true --setParameter enableShardedIndexConsistencyCheck=false --setParameter enableFinerGrainedCatalogCacheRefresh=false --setParameter readHedgingMode=off --setParameter loadRoutingTableOnStartup=false --setParameter rangeDeleterBatchDelayMS=2000000 --setParameter skipShardingConfigurationChecks=true --setParameter syncdelay=3600"
            )
            .withCreateContainerCmdModifier(cmd ->
                cmd.getHostConfig().withMemory(memoryInBytes).withMemorySwap(memorySwapInBytes).withNanoCPUs(nanoCpu)
            ) */
            .withLogConsumer(new Slf4jLogConsumer(log))
            .withReuse(true);
    }

    private void startWithRetries() {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            try {
                if (!mongodbContainer.isRunning()) {
                    mongodbContainer.start();
                }
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt < START_ATTEMPTS) {
                    log.warn(
                        "The MongoDB test container did not start on attempt {} of {}. On this fixture that is normally a loaded machine missing the replica-set window rather than a defect in the code under test — discarding the container and retrying in {} ms.",
                        attempt,
                        START_ATTEMPTS,
                        RETRY_BACKOFF_MILLIS,
                        e
                    );
                    discardContainer();
                    backOff();
                }
            }
        }
        log.error(
            "The MongoDB test container did not start in {} attempts. If the whole suite is red with this same stack trace, read docs/backlog.md item 9 before looking for a regression; if it is only this run, check that the Docker daemon is healthy and that the machine is not saturated.",
            START_ATTEMPTS,
            lastFailure
        );
        throw lastFailure != null
            ? lastFailure
            : new IllegalStateException("The MongoDB test container did not start, and no failure was recorded");
    }

    /**
     * Stops and removes the container that failed, then builds a fresh one. The half-started container is
     * not reusable: its replica set is partly initialised, so starting it again loops on
     * {@code ReadConcernMajorityNotAvailableYet} instead of recovering.
     */
    private void discardContainer() {
        try {
            mongodbContainer.stop();
        } catch (RuntimeException e) {
            log.warn("Could not stop the MongoDB test container that failed to start; building a fresh one anyway.", e);
        }
        mongodbContainer = buildContainer();
    }

    private void backOff() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry the MongoDB test container", e);
        }
    }
}
