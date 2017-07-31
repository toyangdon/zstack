package org.zstack.test.integration.storage.primary.ceph

import org.springframework.http.HttpEntity
import org.zstack.header.volume.VolumeConstant
import org.zstack.sdk.AddCephPrimaryStorageAction
import org.zstack.sdk.ImageInventory
import org.zstack.sdk.VmInstanceInventory
import org.zstack.storage.ceph.primary.CephPrimaryStorageBase
import org.zstack.test.integration.storage.CephEnv
import org.zstack.test.integration.storage.StorageTest
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.SubCase
import org.zstack.utils.gson.JSONObjectUtil

/**
 * Created by heathhose on 17-3-22.
 */
class CephStorageOneVmAndImageCase extends SubCase{
    def description = """
        1. use ceph for primary storage and backup storage
        2. create a vm
        3. create an image from the vm's root volume
        confirm the volume created successfully
    """

    EnvSpec env

    @Override
    void setup() {
        useSpring(StorageTest.springSpec)
    }

    @Override
    void environment() {
        env = CephEnv.CephStorageOneVmEnv()
    }

    @Override
    void test() {
        env.create {
            createImageFromRootVolume()
            testCreateWithNoCephx()
        }
    }

    void createImageFromRootVolume(){
        VmInstanceInventory vm = env.inventoryByName("test-vm")
        stopVmInstance {
            uuid = vm.uuid
            sessionId = loginAsAdmin().uuid
        }

        ImageInventory img = createRootVolumeTemplateFromRootVolume {
            name = "template"
            rootVolumeUuid = vm.getRootVolumeUuid()
            sessionId = loginAsAdmin().uuid
        }

        assert VolumeConstant.VOLUME_FORMAT_RAW == img.getFormat()
    }

    void testCreateWithNoCephx() {
        def fsid = "8ff218d9-f525-435f-8a40-3618d1772a64"
        def nocephx = false
        env.simulator(CephPrimaryStorageBase.GET_FACTS) { HttpEntity<String> e, EnvSpec spec ->
            def rsp = new CephPrimaryStorageBase.GetFactsRsp()
            rsp.fsid = fsid
            rsp.monAddr = "127.0.0.2"
            rsp.success = true
            return rsp
        }

        env.simulator(CephPrimaryStorageBase.INIT_PATH) { HttpEntity<String> e, EnvSpec spec ->
            def cmd = JSONObjectUtil.toObject(e.body, CephPrimaryStorageBase.InitCmd.class)
            nocephx = cmd.nocephx

            def rsp = new CephPrimaryStorageBase.InitRsp()
            rsp.success = true
            rsp.fsid = fsid
            rsp.totalCapacity = 400000000
            rsp.availableCapacity = 400000000
            return rsp
        }

        AddCephPrimaryStorageAction action = new AddCephPrimaryStorageAction()
        action.name = "ceph-primary-new"
        action.monUrls = ["root:password@127.0.0.2"]
        action.rootVolumePoolName = "rootPool"
        action.dataVolumePoolName = "dataPool"
        action.imageCachePoolName = "cachePool"
        action.systemTags = [ "ceph::nocephx" ]
        action.zoneUuid = env.inventoryByName("zone").uuid
        action.sessionId = adminSession()
        AddCephPrimaryStorageAction.Result res = action.call()
        assert res != null
        assert nocephx
        assert res.error == null
    }
    
    @Override
    void clean() {
        env.delete()
    }
}
