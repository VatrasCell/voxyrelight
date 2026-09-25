package de.vatrascell.voxyrelight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Holds at most one active job, including its worker thread. */
public final class RelightManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxyrelight");

    private static RelightJob currentJob;
    private static Thread currentThread;

    private RelightManager() {}

    static synchronized boolean isRunning() {
        return currentThread != null && currentThread.isAlive();
    }

    static synchronized String currentDescription() {
        return isRunning() ? currentJob.description() : null;
    }

    static synchronized boolean start(RelightJob job) {
        if (isRunning()) {
            return false;
        }
        Thread thread = new Thread(job, "VoxyRelight worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        currentJob = job;
        currentThread = thread;
        thread.start();
        return true;
    }

    static synchronized boolean cancel() {
        if (!isRunning()) {
            return false;
        }
        currentJob.cancel();
        return true;
    }

    /**
     * Cancels a running job and waits for it to end. Called before Voxy's own shutdown when
     * leaving the world, so that no write accesses are still in flight.
     */
    public static void cancelAndWait() {
        Thread thread;
        synchronized (RelightManager.class) {
            if (!cancel()) {
                return;
            }
            thread = currentThread;
        }
        try {
            thread.join(30_000);
            if (thread.isAlive()) {
                LOGGER.warn("VoxyRelight worker did not terminate within 30 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
