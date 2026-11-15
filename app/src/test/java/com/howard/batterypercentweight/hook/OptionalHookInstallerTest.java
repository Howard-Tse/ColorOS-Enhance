package com.howard.batterypercentweight.hook;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class OptionalHookInstallerTest {
    @Test public void optionalHookFailureRetainsPreviouslyInstalledHandles() {
        List<String> installed = new ArrayList<>(List.of("constructor"));
        List<Throwable> failures = new ArrayList<>();

        OptionalHookInstaller.<String>install(
                () -> {
                    throw new IllegalStateException("offset unavailable");
                },
                installed::add,
                failures::add);

        assertEquals(List.of("constructor"), installed);
        assertEquals(1, failures.size());
    }

    @Test public void optionalHookSuccessRetainsReturnedHandle() {
        List<String> installed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        OptionalHookInstaller.install(
                () -> "offset", installed::add, failures::add);

        assertEquals(List.of("offset"), installed);
        assertEquals(List.of(), failures);
    }
}
