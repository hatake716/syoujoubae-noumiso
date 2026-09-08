plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("atlas_models")
    dynamicDelivery { deliveryType.set("install-time") }
}
