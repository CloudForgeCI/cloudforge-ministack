package com.cloudforgeci.ministack;

import com.cloudforge.core.local.StackPortRuntime;
import com.cloudforge.core.local.StackPortRuntimeProvider;

public final class MiniStackStackPortRuntimeProvider implements StackPortRuntimeProvider {

    @Override
    public StackPortRuntime runtime() {
        return MiniStackStackPortRuntime.INSTANCE;
    }
}
