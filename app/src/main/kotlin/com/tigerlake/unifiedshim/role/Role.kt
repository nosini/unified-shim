package com.tigerlake.unifiedshim.role

enum class Role {
    /** Profile that holds the external ntfy connection (user 0). */
    OWNER,

    /** Any non-owner profile (private space or work profile) that acts as the
     *  UnifiedPush distributor. */
    PRIVATE,
}
