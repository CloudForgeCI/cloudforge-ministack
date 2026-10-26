package com.cloudforgeci.ministack;

import com.cloudforgeci.ministack.MiniStackDatabases.Endpoint;
import com.cloudforgeci.ministack.MiniStackDatabases.Plan;
import java.util.ArrayList;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Creates the databases a template asks for through MiniStack's direct RDS API, which starts a real
 * database container, and removes them again. See {@link MiniStackDatabases} for why.
 */
final class MiniStackDatabaseProvisioner implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(MiniStackDatabaseProvisioner.class.getName());
    private static final Duration AVAILABLE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final RdsClient rds;
    private final DatabaseHost host;
    private final List<String> created = new ArrayList<>();

    MiniStackDatabaseProvisioner(String endpoint, String region, AwsCredentialsProvider credentials) {
        this(endpoint, region, credentials, DatabaseHost.detect());
    }

    MiniStackDatabaseProvisioner(
            String endpoint, String region, AwsCredentialsProvider credentials, DatabaseHost host) {
        this.host = host;
        rds = RdsClient.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .build();
    }

    /** Creates each database that does not exist yet and waits until it accepts connections. */
    Map<String, Endpoint> provision(List<Plan> plans) throws IOException {
        try {
            return provisionAll(plans);
        } catch (IOException | RuntimeException e) {
            // Whatever this call created is useless without the stack that would use it.
            delete(createdIdentifiers());
            throw e;
        }
    }

    private Map<String, Endpoint> provisionAll(List<Plan> plans) throws IOException {
        Map<String, Endpoint> endpoints = new LinkedHashMap<>();
        for (Plan plan : plans) {
            DBInstance existing = find(plan.identifier());
            boolean recorded = existing != null;
            if (!reusable(recorded, host.running(plan, existing))) {
                if (recorded) {
                    // The emulator lists the database but it is not up, as after its container was
                    // removed by hand. An application given its address would be refused.
                    delete(List.of(plan.identifier()));
                }
                // Something left from an earlier run, whose emulator was restarted or whose deploy
                // failed, could still own the name.
                host.clearLeftovers(plan.identifier());
                create(plan);
                created.add(plan.identifier());
            }
            DBInstance instance = waitUntilAvailable(plan.identifier());
            Endpoint endpoint = host.endpoint(plan, instance);
            host.awaitReady(plan, instance, READY_TIMEOUT);
            endpoints.put(plan.logicalId(), endpoint);
        }
        return endpoints;
    }

    /** An existing database is reused only while its container is running. */
    static boolean reusable(boolean recorded, String runningContainer) {
        return reusable(recorded, runningContainer != null);
    }

    static boolean reusable(boolean recorded, boolean running) {
        return recorded && running;
    }

    /** Identifiers of the databases this provisioner created, as opposed to found already running. */
    List<String> createdIdentifiers() {
        return List.copyOf(created);
    }

    /** Removes the databases with these identifiers, ignoring any that are already gone. */
    void delete(List<String> identifiers) {
        for (String identifier : identifiers) {
            try {
                rds.deleteDBInstance(r -> r.dbInstanceIdentifier(identifier).skipFinalSnapshot(true));
            } catch (DbInstanceNotFoundException e) {
                // already gone
            } catch (Exception e) {
                LOG.warning("Could not delete MiniStack database " + identifier + ": " + e.getMessage());
            }
        }
    }

    private void create(Plan plan) {
        rds.createDBInstance(r -> {
            r.dbInstanceIdentifier(plan.identifier())
                .engine(plan.engine())
                .engineVersion(plan.version())
                .dbInstanceClass(plan.instanceClass())
                .allocatedStorage(plan.allocatedStorageGb())
                .masterUsername(plan.username())
                .masterUserPassword(MiniStackDatabases.localPassword(plan.identifier()));
            if (plan.databaseName() != null && !plan.databaseName().isBlank()) {
                r.dbName(plan.databaseName());
            }
        });
    }

    private DBInstance find(String identifier) {
        try {
            return rds.describeDBInstances(r -> r.dbInstanceIdentifier(identifier)).dbInstances().getFirst();
        } catch (DbInstanceNotFoundException e) {
            return null;
        }
    }

    private DBInstance waitUntilAvailable(String identifier) throws IOException {
        Instant deadline = Instant.now().plus(AVAILABLE_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            DBInstance instance = find(identifier);
            if (instance != null && "available".equals(instance.dbInstanceStatus())) {
                return instance;
            }
            sleep(POLL_INTERVAL);
        }
        throw new IOException("MiniStack database " + identifier + " did not become available");
    }

    /**
     * A short, stable name for the database on the emulator network. The container name MiniStack
     * gives it can exceed the 63-character limit of a DNS label, and MiniStack's ECS translates a
     * database's address back into that long name, so neither can be handed to an application. An
     * alias is neither.
     */
    static String aliasFor(String identifier) {
        return "db-" + MiniStackDatabases.localPassword(identifier).substring(3, 15);
    }

    /** DNS labels are limited to 63 characters, so a longer container name cannot be resolved. */
    private static final int MAX_DNS_LABEL = 63;

    /**
     * Picks the host application containers connect to: the container name when it resolves,
     * otherwise the address the emulator reports.
     */
    static String chooseHost(String containerName, String reportedAddress) {
        if (containerName != null && containerName.length() <= MAX_DNS_LABEL) {
            return containerName;
        }
        if ("localhost".equals(reportedAddress) || "127.0.0.1".equals(reportedAddress)) {
            return "host.docker.internal";
        }
        return reportedAddress;
    }

    /**
     * The command that asks the database container whether it accepts connections. The MariaDB image
     * ships {@code mariadb-admin} and no {@code mysqladmin}; the MySQL image is the other way round.
     */
    static String[] readinessProbe(String engine, String container, String username, String password) {
        if ("postgres".equals(engine)) {
            return new String[] {"docker", "exec", container, "pg_isready", "-U", username};
        }
        String admin = "mariadb".equals(engine) ? "mariadb-admin" : "mysqladmin";
        return new String[] {"docker", "exec", "-e", "MYSQL_PWD=" + password, container,
            admin, "ping", "-h", "127.0.0.1", "-u", username, "--silent"};
    }

    /** The command's output, or null when it fails or times out. */
    static String run(Duration timeout, String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String out = new String(process.getInputStream().readAllBytes());
            return process.exitValue() == 0 ? out.trim() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        rds.close();
    }
}
