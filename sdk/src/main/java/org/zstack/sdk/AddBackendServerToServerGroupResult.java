package org.zstack.sdk;

import org.zstack.sdk.LoadBalancerServerGroupInventory;

public class AddBackendServerToServerGroupResult {
    public LoadBalancerServerGroupInventory inventory;
    public void setInventory(LoadBalancerServerGroupInventory inventory) {
        this.inventory = inventory;
    }
    public LoadBalancerServerGroupInventory getInventory() {
        return this.inventory;
    }

}
