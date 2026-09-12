package io.github.vihuynh72.brownie.core.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceCapabilitiesTest {

    @Test
    void ownerGrantsManageWorkspace() {
        assertTrue(WorkspaceCapabilities.grants(WorkspaceRole.OWNER, WorkspaceCapability.MANAGE_WORKSPACE));
    }
}
