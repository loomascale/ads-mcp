package com.loomascale.mcp.audit;

// What a write tool did to the object it names.
//
// Recorded explicitly rather than inferred from the tool's name. The original
// implementation matched against a hardcoded list of create-tool names, which cannot work
// in a library: it does not know any platform's tool names, and a new create tool would
// silently lose activation eligibility — the object would look as though this server had
// not created it, and could never be activated.
public enum WriteKind {
  CREATE,
  UPDATE,
  DELETE
}
