package com.cloudforgeci.ministack;

import com.cloudforge.core.local.LocalEmulatorRuntime;
import com.cloudforge.core.local.LocalEmulatorRuntimeProvider;

public final class MiniStackEmulatorRuntimeProvider implements LocalEmulatorRuntimeProvider {

    @Override
    public LocalEmulatorRuntime runtime() {
        return MiniStackEmulatorRuntime.INSTANCE;
    }
}
