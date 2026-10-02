package com.gccwebos.android.ipcqa.b2;

import android.os.IBinder;
import android.os.RemoteException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** API33 death observation, bound to the original strong Binder identity. */
public final class EndpointWatch {
    private final IBinder binder;
    private final AtomicBoolean dead = new AtomicBoolean();
    private final CountDownLatch observed = new CountDownLatch(1);
    private final Runnable callback;
    private final IBinder.DeathRecipient recipient = new IBinder.DeathRecipient() {
        @Override public void binderDied() { recordDeath(); }
    };

    EndpointWatch(IBinder binder, Runnable callback) throws RemoteException {
        this.binder = binder;
        this.callback = callback;
        // Publish cannot overwrite a callback delivered during linkToDeath.
        // A failed registration is UNKNOWN, not fabricated callback evidence.
        binder.linkToDeath(recipient, 0);
    }

    private void recordDeath() {
        if (dead.compareAndSet(false, true)) {
            callback.run();
            observed.countDown();
        }
    }

    public boolean isDead() { return dead.get(); }
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return observed.await(timeout, unit);
    }
    IBinder identity() { return binder; }
    /** Only an unpublished candidate may be detached; active debt keeps its watcher. */
    void unlinkUnpublished() { binder.unlinkToDeath(recipient, 0); }
}
