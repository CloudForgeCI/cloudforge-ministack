package com.cloudforgeci.ministack;

import com.cloudforge.core.local.AbstractLocalEmulatorRuntime;
import com.cloudforge.core.local.LocalEmulatorDefaults;
import com.cloudforge.core.local.LocalEmulatorSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts and stops the MiniStack Docker container ({@code cfc-ministack}).
 */
public final class MiniStackEmulatorRuntime extends AbstractLocalEmulatorRuntime {

    public static final MiniStackEmulatorRuntime INSTANCE = new MiniStackEmulatorRuntime();

    private MiniStackEmulatorRuntime() {
        super(LocalEmulatorSpec.ministack());
    }

    @Override
    protected List<String> dockerCreateArgs() throws IOException {
        List<String> args = new ArrayList<>(baseDockerCreateArgs());
        args.addAll(List.of(
            "-p", "15432-15442:15432-15442",
            "-e", "MINISTACK_REGION=" + region(),
            "-e", "GATEWAY_PORT=4566",
            "-e", "PERSIST_STATE=0",
            "-e", "LOG_LEVEL=" + logLevel(),
            "-e", "DOCKER_NETWORK=" + LocalEmulatorDefaults.DOCKER_NETWORK,
            spec().image()));
        return args;
    }

    private static String region() {
        return System.getenv().getOrDefault("MINISTACK_REGION", "us-east-1");
    }

    private static String logLevel() {
        return System.getenv().getOrDefault("MINISTACK_LOG_LEVEL", "WARNING");
    }
}
