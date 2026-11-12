package com.cloudforgeci.ministack;

import com.cloudforge.core.local.LocalEmulatorDefaults;
import java.time.Duration;

/** Docker CLI operations on the emulator's network. */
final class DockerNetwork {

    private DockerNetwork() {
    }

    /**
     * Makes {@code alias} resolve to the container on the emulator network, adding it if the container
     * does not have it yet. An alias can only be given when a container joins a network, so a container
     * without it leaves the network and joins again.
     *
     * @return the alias, or null if it could not be attached
     */
    static String ensureAlias(String container, String alias) {
        String network = LocalEmulatorDefaults.DOCKER_NETWORK;
        String current = MiniStackDatabaseProvisioner.run(Duration.ofSeconds(10), "docker", "inspect", container,
            "--format", "{{range $k,$v := .NetworkSettings.Networks}}{{if eq $k \"" + network
                + "\"}}{{range $v.Aliases}}{{.}} {{end}}{{end}}{{end}}");
        if (current != null && java.util.List.of(current.trim().split("\\s+")).contains(alias)) {
            return alias;
        }
        MiniStackDatabaseProvisioner.run(Duration.ofSeconds(15), "docker", "network", "disconnect", network, container);
        return MiniStackDatabaseProvisioner.run(
            Duration.ofSeconds(15), "docker", "network", "connect", "--alias", alias, network, container) != null
            ? alias : null;
    }
}
