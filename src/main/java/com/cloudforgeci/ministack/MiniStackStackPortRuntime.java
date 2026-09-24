package com.cloudforgeci.ministack;

import com.cloudforge.core.local.AbstractStackPortRuntime;
import com.cloudforge.core.local.StackPortSpec;

/**
 * StackPort resource browser wired to the MiniStack gateway on {@code cfc-network}.
 */
public final class MiniStackStackPortRuntime extends AbstractStackPortRuntime {

    public static final MiniStackStackPortRuntime INSTANCE =
        new MiniStackStackPortRuntime();

    private MiniStackStackPortRuntime() {
        super(StackPortSpec.ministack());
    }
}
