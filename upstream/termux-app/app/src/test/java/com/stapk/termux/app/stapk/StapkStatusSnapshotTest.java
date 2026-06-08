package com.stapk.termux.app.stapk;

import org.junit.Assert;
import org.junit.Test;

public class StapkStatusSnapshotTest {

    @Test
    public void fromJson_shouldParseManagedRuntimeFields() {
        String json = "{\n" +
                "  \"status\": \"running\",\n" +
                "  \"runtime_managed\": true,\n" +
                "  \"runtime_pid\": \"3210\",\n" +
                "  \"node_pid\": \"6543\",\n" +
                "  \"port_listening\": true,\n" +
                "  \"sillytavern_version\": \"1.13.0\",\n" +
                "  \"sillytavern_commit\": \"abc1234\"\n" +
                "}";

        StapkStatusSnapshot snapshot = StapkStatusSnapshot.fromJson(json);

        Assert.assertEquals("running", snapshot.status);
        Assert.assertTrue(snapshot.runtimeManaged);
        Assert.assertEquals("3210", snapshot.runtimePid);
        Assert.assertEquals("6543", snapshot.nodePid);
        Assert.assertTrue(snapshot.portListening);
        Assert.assertEquals("1.13.0", snapshot.version);
        Assert.assertEquals("abc1234", snapshot.commit);
    }

    @Test
    public void fromJson_shouldFallbackToUnknownSnapshotForInvalidJson() {
        StapkStatusSnapshot snapshot = StapkStatusSnapshot.fromJson("not-json");

        Assert.assertEquals("unknown", snapshot.status);
        Assert.assertFalse(snapshot.runtimeManaged);
        Assert.assertFalse(snapshot.portListening);
    }
}
