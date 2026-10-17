package com.cloudforgeci.ministack;

import com.cloudforge.core.local.LocalEmulatorDefaults;
import com.cloudforgeci.ministack.MiniStackDatabases.Endpoint;
import com.cloudforgeci.ministack.MiniStackDatabases.Plan;
import java.util.ArrayList;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
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
    private final List<String> created = new ArrayList<>();

    MiniStackDatabaseProvisioner(String endpoint, String region) {
        rds = RdsClient.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
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
            boolean recorded = find(plan.identifier()) != null;
            if (!reusable(recorded, containerName(plan.identifier()))) {
                if (recorded) {
                    // The emulator lists the database but its container is gone, as after the
                    // container was removed by hand. An application given its address would be refused.
                    delete(List.of(plan.identifier()));
                }
                // A container left from an earlier run, whose emulator was restarted or whose deploy
                // failed, could still own the name.
                removeStaleContainers(plan.identifier());
                create(plan);
                created.add(plan.identifier());
            }
            DBInstance instance = waitUntilAvailable(plan.identifier());
            if (containerName(plan.identifier()) == null) {
                throw new IOException("MiniStack database " + plan.identifier() + " has no running container");
            }
            Endpoint endpoint = endpointOf(plan, instance);
            waitUntilReady(plan, containerName(plan.identifier()));
            endpoints.put(plan.logicalId(), endpoint);
        }
        return endpoints;
    }

    /** An existing database is reused only while its container is running. */
    static boolean reusable(boolean recorded, String runningContainer) {
        return recorded && runningContainer != null;
    }

    /** Identifiers of the databases this provisioner created, as opposed to found already running. */
    List<String> createdIdentifiers() {
        return List.copyOf(created);
    }

    /** Removes containers labelled as the database {@code identifier}, which the emulator no longer tracks. */
    private static void removeStaleContainers(String identifier) {
        String ids = run(Duration.ofSeconds(15), "docker", "ps", "-aq", "--filter", "label=db_id=" + identifier);
        if (ids == null || ids.isBlank()) {
            return;
        }
        for (String id : ids.split("\\s+")) {
            run(Duration.ofSeconds(30), "docker", "rm", "-f", id);
        }
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
     * The address the application containers use. The database runs as a container on the emulator's
     * Docker network, so its container name resolves there and, unlike its IP, survives a restart.
     */
    private Endpoint endpointOf(Plan plan, DBInstance instance) {
        String container = containerName(plan.identifier());
        String alias = container == null ? null : addNetworkAlias(container, aliasFor(plan.identifier()));
        String host = alias != null ? alias : chooseHost(container, instance.endpoint().address());
        return new Endpoint(host, instance.endpoint().port());
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

    /** Attaches the alias to the container's network; returns the alias, or null if that failed. */
    private static String addNetworkAlias(String container, String alias) {
        String network = LocalEmulatorDefaults.DOCKER_NETWORK;
        String current = run(Duration.ofSeconds(10), "docker", "inspect", container, "--format",
            "{{range $k,$v := .NetworkSettings.Networks}}{{if eq $k \"" + network + "\"}}{{range $v.Aliases}}{{.}} {{end}}{{end}}{{end}}");
        if (current != null && current.contains(alias)) {
            return alias;
        }
        run(Duration.ofSeconds(15), "docker", "network", "disconnect", network, container);
        return run(Duration.ofSeconds(15), "docker", "network", "connect", "--alias", alias, network, container) != null
            ? alias : null;
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

    private static String containerName(String identifier) {
        String out = run(Duration.ofSeconds(10), "docker", "ps", "--filter", "label=db_id=" + identifier,
            "--format", "{{.Names}}");
        return out == null || out.isBlank() ? null : out.lines().findFirst().orElse(null);
    }

    /** The container reports available before the database inside it has finished starting. */
    private void waitUntilReady(Plan plan, String container) {
        if (container == null) {
            return;
        }
        String[] probe = readinessProbe(plan.engine(), container, plan.username(),
            MiniStackDatabases.localPassword(plan.identifier()));
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (run(Duration.ofSeconds(10), probe) != null) {
                return;
            }
            sleep(POLL_INTERVAL);
        }
        LOG.warning("MiniStack database " + plan.identifier() + " did not report ready within "
            + READY_TIMEOUT.toSeconds() + "s; continuing");
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
    private static String run(Duration timeout, String... command) {
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

    private static void sleep(Duration duration) {
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
