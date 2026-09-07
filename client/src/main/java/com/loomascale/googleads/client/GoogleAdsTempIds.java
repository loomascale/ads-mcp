package com.loomascale.googleads.client;

// Temporary resource names for a bulk googleAds:mutate. Google lets one operation refer to
// a resource another operation in the same request creates, by giving the not-yet-existing
// resource a negative id: an asset group created as
// customers/123/assetGroups/-100 is what the assetGroupAsset links point at, and Google
// swaps in the real ids when it commits.
//
// Two rules Google enforces and this class makes structural: an id must be unique within the
// request, and the operation that defines it must come before any operation that references
// it. A single monotonic counter shared by every service in one request guarantees the
// first, and callers appending operations in order guarantee the second.
//
// Not thread-safe and deliberately not shared: one instance belongs to one request.
public final class GoogleAdsTempIds {

  private long previous;

  public long next() {
    previous--;
    return previous;
  }

  // A resource name for something this request has not created yet, e.g.
  // customers/1234567890/assetGroups/-1.
  public String tempResourceName(String customerId, String service) {
    return "customers/" + customerId + "/" + service + "/" + next();
  }
}
