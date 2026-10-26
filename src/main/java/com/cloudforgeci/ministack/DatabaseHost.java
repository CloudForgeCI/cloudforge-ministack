package com.cloudforgeci.ministack;

import com.cloudforgeci.ministack.MiniStackDatabases.Endpoint;
import com.cloudforgeci.ministack.MiniStackDatabases.Plan;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Function;
import software.amazon.awssdk.services.rds.model.DBInstance;

/**
 * What {@link MiniStackDatabaseProvisioner} needs from the machine it runs on besides the RDS API.
 *
 * <p>A deploy started from a developer's machine can inspect and manage the emulator's Docker
 * containers. A deploy started by CloudForge Manager running inside the emulator cannot: it has no
 * Docker socket or CLI, so it can only use the RDS API and the network.</p>
 */
interface DatabaseHost {

    /** Environment variable that forces {@code docker} or {@code network}; anything else detects. */
    String MODE_VARIABLE = "CFC_EMULATOR_HOST_MODE";

    /** Whether the database listed by the emulator is up. {@code instance} is null when none is listed. */
    boolean running(Plan plan, DBInstance instance);

    /** Clears whatever an earlier run left behind for this database before it is created again. */
    void clearLeftovers(String identifier);

    /** The address the application containers use to reach the database. */
    Endpoint endpoint(Plan plan, DBInstance instance) throws IOException;

    /** Waits until the database accepts connections; continues with a warning after {@code timeout}. */
    void awaitReady(Plan plan, DBInstance instance, Duration timeout);

    /**
     * Picks the host for this process: Docker when the CLI can reach a daemon, the network otherwise.
     * {@link #MODE_VARIABLE} overrides the detection.
     */
    static DatabaseHost detect() {
        return detect(System::getenv, DockerDatabaseHost::daemonReachable);
    }

    static DatabaseHost detect(Function<String, String> env, java.util.function.BooleanSupplier dockerReachable) {
        String mode = env.apply(MODE_VARIABLE);
        if ("network".equalsIgnoreCase(mode)) {
            return new NetworkDatabaseHost();
        }
        if ("docker".equalsIgnoreCase(mode) || dockerReachable.getAsBoolean()) {
            return new DockerDatabaseHost();
        }
        return new NetworkDatabaseHost();
    }
}
