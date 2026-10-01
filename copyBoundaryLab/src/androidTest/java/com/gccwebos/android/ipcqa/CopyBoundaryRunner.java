package com.gccwebos.android.ipcqa;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Looper;

/** Native, dependency-free instrumentation runner for the six B1 cases only. */
public final class CopyBoundaryRunner extends Instrumentation {
    private static final String TEST_CLASS = CopyBoundaryIpcTest.class.getName();
    private static final String[] METHODS = {
        "p1RemoteIdentity", "p2OwnedCopyAndWipe", "p3BoundedActualParcels",
        "p4ClearBeforeCopyAndLateAck", "p5OriginalStopTicketAcrossGeneration",
        "p6UnknownKeepsClaimUntilCallReturns"
    };
    private Bundle arguments;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : new Bundle(args);
        start();
    }

    /** Instrumentation.start invokes onStart on its worker thread, leaving the main
     * looper available for ServiceConnection callbacks. No test RPC runs on the UI. */
    @Override public void onStart() {
        super.onStart();
        String[] selected = selection();
        if (selected == null || Looper.myLooper() == Looper.getMainLooper()) {
            Bundle result = new Bundle();
            result.putString("shortMsg", "Invalid B1 selection or instrumentation thread");
            result.putString("stream", "B1 did not execute: invalid selection or worker thread.\n");
            finish(Activity.RESULT_CANCELED, result);
            return;
        }
        int failed = 0;
        for (int index = 0; index < selected.length; index++) {
            String method = selected[index];
            sendStatus(1, status(method, selected.length, index + 1, ""));
            try {
                new CopyBoundaryIpcTest(this).run(method);
                sendStatus(0, status(method, selected.length, index + 1,
                    ". " + method + " passed: synthetic copy-only B1 assertions.\n"));
            } catch (Throwable failure) {
                failed++;
                Bundle state = status(method, selected.length, index + 1,
                    "F " + method + " failed.\n");
                // All assertions use fixed safe labels. Do not serialize a remote
                // exception message, a frame, payload, identifier or stack locals.
                state.putString("stack", safeFailure(failure));
                sendStatus(-2, state);
            }
        }
        Bundle result = new Bundle();
        result.putInt("tests", selected.length);
        result.putInt("failures", failed);
        result.putInt("passed", selected.length - failed);
        result.putString("stream", "B1 tests run: " + selected.length + ", failures: " + failed
            + ". Evidence is limited to synthetic Binder copy/clear assertions.\n");
        finish(Activity.RESULT_OK, result);
    }

    private String[] selection() {
        String requested = arguments.getString("class");
        if (requested == null || TEST_CLASS.equals(requested)) return METHODS.clone();
        for (String method : METHODS) {
            if ((TEST_CLASS + "#" + method).equals(requested)) return new String[] {method};
        }
        return null;
    }

    private static Bundle status(String method, int total, int index, String stream) {
        Bundle status = new Bundle();
        status.putString("id", "CopyBoundaryRunner");
        status.putString("class", TEST_CLASS);
        status.putString("test", method);
        status.putInt("numtests", total);
        status.putInt("current", index);
        status.putString("stream", stream);
        return status;
    }

    private static String safeFailure(Throwable failure) {
        if (failure instanceof AssertionError && failure.getMessage() != null) {
            return "AssertionError: " + failure.getMessage();
        }
        return failure.getClass().getSimpleName() + ": B1 call failed; no remote detail emitted";
    }
}
