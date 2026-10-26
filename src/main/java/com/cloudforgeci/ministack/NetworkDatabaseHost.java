package com.cloudforgeci.ministack;

import com.cloudforgeci.ministack.MiniStackDatabases.Endpoint;
import com.cloudforgeci.ministack.MiniStackDatabases.Plan;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import software.amazon.awssdk.services.rds.model.DBInstance;

/**
 * Reaches the emulator's databases only through the address its RDS API reports. For a deploy started
 * by a process that has no Docker CLI or socket, such as CloudForge Manager running as a container
 * inside the emulator.
 *
 * <p>The emulator publishes each database on a port of the machine running Docker. The application
 * containers reach it there through {@code host.docker.internal}, so no network alias is attached.
 * Readiness is a protocol greeting, not a bare connection: Docker's port proxy accepts connections
 * before the database behind it has started.</p>
 */
final class NetworkDatabaseHost implements DatabaseHost {
    private static final Logger LOG = Logger.getLogger(NetworkDatabaseHost.class.getName());
    private static final String CONTAINER_HOST_ALIAS = "host.docker.internal";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    @Override
    public boolean running(Plan plan, DBInstance instance) {
        return instance != null && instance.endpoint() != null && reachableAddress(plan, instance) != null;
    }

    /** The emulator removes a database's container when its instance is deleted, so nothing is left over. */
    @Override
    public void clearLeftovers(String identifier) {
    }

    @Override
    public Endpoint endpoint(Plan plan, DBInstance instance) throws IOException {
        if (instance == null || instance.endpoint() == null || instance.endpoint().address() == null) {
            throw new IOException("MiniStack database " + plan.identifier() + " reports no endpoint");
        }
        return new Endpoint(MiniStackDatabaseProvisioner.chooseHost(null, instance.endpoint().address()),
            instance.endpoint().port());
    }

    @Override
    public void awaitReady(Plan plan, DBInstance instance, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (reachableAddress(plan, instance) != null) {
                return;
            }
            MiniStackDatabaseProvisioner.sleep(POLL_INTERVAL);
        }
        LOG.warning("MiniStack database " + plan.identifier() + " did not answer within "
            + timeout.toSeconds() + "s; continuing");
    }

    /**
     * The first address this process can reach the database at, or null. The address the emulator
     * reports is {@code localhost} for a process on the Docker host and means nothing to a container,
     * which reaches the host as {@code host.docker.internal}.
     */
    private static String reachableAddress(Plan plan, DBInstance instance) {
        if (instance == null || instance.endpoint() == null || instance.endpoint().address() == null) {
            return null;
        }
        for (String address : candidates(instance.endpoint().address())) {
            if (answers(plan.engine(), address, instance.endpoint().port(), CONNECT_TIMEOUT)) {
                return address;
            }
        }
        return null;
    }

    static List<String> candidates(String reported) {
        List<String> addresses = new ArrayList<>();
        addresses.add(reported);
        if (("localhost".equals(reported) || "127.0.0.1".equals(reported))
                && !addresses.contains(CONTAINER_HOST_ALIAS)) {
            addresses.add(CONTAINER_HOST_ALIAS);
        }
        return addresses;
    }

    /**
     * Whether a database of this engine answers its opening exchange: MySQL and MariaDB send a
     * greeting as soon as a client connects, and PostgreSQL answers an SSL request with one byte.
     * A proxy with nothing behind it accepts the connection and closes it without a byte.
     */
    static boolean answers(String engine, String host, int port, Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            if ("postgres".equals(engine)) {
                OutputStream out = socket.getOutputStream();
                out.write(new byte[] {0, 0, 0, 8, 4, (byte) 0xd2, 0x16, 0x2f});
                out.flush();
            }
            InputStream in = socket.getInputStream();
            return in.read() >= 0;
        } catch (IOException e) {
            return false;
        }
    }
}
