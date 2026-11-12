package com.cloudforgeci.ministack;

import com.cloudforgeci.ministack.MiniStackDatabases.Endpoint;
import com.cloudforgeci.ministack.MiniStackDatabases.Plan;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;
import software.amazon.awssdk.services.rds.model.DBInstance;

/**
 * Manages the emulator's database containers through the Docker CLI. For a deploy started on the same
 * machine as the emulator's Docker daemon.
 */
final class DockerDatabaseHost implements DatabaseHost {
    private static final Logger LOG = Logger.getLogger(DockerDatabaseHost.class.getName());
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /** Whether the Docker CLI exists and its daemon answers. */
    static boolean daemonReachable() {
        return MiniStackDatabaseProvisioner.run(
            Duration.ofSeconds(5), "docker", "version", "--format", "{{.Server.Version}}") != null;
    }

    @Override
    public boolean running(Plan plan, DBInstance instance) {
        return instance != null && containerName(plan.identifier()) != null;
    }

    /** Removes containers labelled as the database {@code identifier}, which the emulator no longer tracks. */
    @Override
    public void clearLeftovers(String identifier) {
        String ids = MiniStackDatabaseProvisioner.run(
            Duration.ofSeconds(15), "docker", "ps", "-aq", "--filter", "label=db_id=" + identifier);
        if (ids == null || ids.isBlank()) {
            return;
        }
        for (String id : ids.split("\\s+")) {
            MiniStackDatabaseProvisioner.run(Duration.ofSeconds(30), "docker", "rm", "-f", id);
        }
    }

    /**
     * The address the application containers use. The database runs as a container on the emulator's
     * Docker network, so its container name resolves there and, unlike its IP, survives a restart.
     */
    @Override
    public Endpoint endpoint(Plan plan, DBInstance instance) throws IOException {
        String container = containerName(plan.identifier());
        if (container == null) {
            throw new IOException("MiniStack database " + plan.identifier() + " has no running container");
        }
        String alias = DockerNetwork.ensureAlias(container, MiniStackDatabaseProvisioner.aliasFor(plan.identifier()));
        String host = alias != null
            ? alias
            : MiniStackDatabaseProvisioner.chooseHost(container, instance.endpoint().address());
        return new Endpoint(host, instance.endpoint().port());
    }

    /** The container reports available before the database inside it has finished starting. */
    @Override
    public void awaitReady(Plan plan, DBInstance instance, Duration timeout) {
        String container = containerName(plan.identifier());
        if (container == null) {
            return;
        }
        String[] probe = MiniStackDatabaseProvisioner.readinessProbe(plan.engine(), container, plan.username(),
            MiniStackDatabases.localPassword(plan.identifier()));
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (MiniStackDatabaseProvisioner.run(Duration.ofSeconds(10), probe) != null) {
                return;
            }
            MiniStackDatabaseProvisioner.sleep(POLL_INTERVAL);
        }
        LOG.warning("MiniStack database " + plan.identifier() + " did not report ready within "
            + timeout.toSeconds() + "s; continuing");
    }

    private static String containerName(String identifier) {
        String out = MiniStackDatabaseProvisioner.run(Duration.ofSeconds(10), "docker", "ps",
            "--filter", "label=db_id=" + identifier, "--format", "{{.Names}}");
        return out == null || out.isBlank() ? null : out.lines().findFirst().orElse(null);
    }
}
