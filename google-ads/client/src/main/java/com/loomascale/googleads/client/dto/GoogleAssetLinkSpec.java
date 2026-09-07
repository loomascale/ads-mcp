package com.loomascale.googleads.client.dto;

// One link to create or remove on an asset group. assetResourceName is what a create
// points at; assetId is what a remove needs, because an AssetGroupAsset's own resource name
// is the composite {assetGroupId}~{assetId}~{FIELD_TYPE} rather than an id of its own.
public record GoogleAssetLinkSpec(String assetResourceName, String assetId, String fieldType) {}
