package com.gccwebos.android.ipcqa.b2;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Looper;

/** Dependency-free runner. A future approved run selects exactly one P7/P8/P9.
 * There is no default suite, continuation after failure or process-control shell. */
public final class OwnerDeathRunner extends Instrumentation {
    private static final String TEST_CLASS = OwnerDeathIpcTest.class.getName();
    private static final String[] METHODS = {
        "p7OwnerDeathKeepsWorkerAndQuarantine",
        "p8ExactEvidenceWaitsForCallAndDisposition",
        "p9WorkerDeathAndStaleEvidenceStayScoped"
    };
    private Bundle arguments;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : new Bundle(args);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        String method = selectedMethod();
        if (method == null || Looper.myLooper() == Looper.getMainLooper()) {
            Bundle result = new Bundle();
            result.putString("shortMsg", "Select one B2 method on the instrumentation worker");
            result.putInt("tests", 0);
            result.putInt("failures", 1);
            result.putInt("passed", 0);
            result.putString("stream", "B2 did not execute: invalid single-method selection or thread.\n");
            finish(Activity.RESULT_CANCELED, result);
            return;
        }
        sendStatus(1, status(method, ""));
        int failures = 0;
        try {
            new OwnerDeathIpcTest(this).run(method);
            sendStatus(0, status(method,
                ". " + method + " passed: synthetic B2 ownership and exact Binder evidence.\n"));
        } catch (Throwable failure) {
            failures = 1;
            Bundle status = status(method, "F " + method + " failed; B2 stopped.\n");
            status.putString("stack", safeFailure(failure));
            sendStatus(-2, status);
        }
        Bundle result = new Bundle();
        result.putInt("tests", 1);
        result.putInt("failures", failures);
        result.putInt("passed", 1 - failures);
        result.putString("stream", "B2 tests run: 1, failures: " + failures
            + ". Evidence does not establish explicit OS memory erasure or VPN cleanup.\n");
        finish(Activity.RESULT_OK, result);
    }

    private String selectedMethod() {
        String requested = arguments.getString("class");
        for (String method : METHODS) {
            if ((TEST_CLASS + "#" + method).equals(requested)) return method;
        }
        return null;
    }

    private static Bundle status(String method, String stream) {
        Bundle status = new Bundle();
        status.putString("id", "OwnerDeathRunner");
        status.putString("class", TEST_CLASS);
        status.putString("test", method);
        status.putInt("numtests", 1);
        status.putInt("current", 1);
        status.putString("stream", stream);
        return status;
    }

    private static String safeFailure(Throwable failure) {
        if (failure instanceof AssertionError && failure.getMessage() != null
                && failure.getMessage().startsWith("B2 ")) {
            return "AssertionError: " + failure.getMessage();
        }
        return failure.getClass().getSimpleName() + ": B2 call failed; no endpoint or material detail emitted";
    }
}
