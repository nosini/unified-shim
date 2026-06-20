package com.tigerlake.unifiedshim.role

enum class Role {
    /** Profile that holds the external ntfy connection (user 0). */
    OWNER,

    /** Profile that acts as the UnifiedPush distributor (private space). */
    PRIVATE,
}
