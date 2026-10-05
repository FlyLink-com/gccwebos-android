package com.gccwebos.android.portal

/** Verified account/token bindings, never an email hint or a local subscription binding. */
internal class AccountUsageOwner private constructor(val accountBinding: String, val sessionBinding: String) {
    override fun equals(other: Any?) = other is AccountUsageOwner &&
        accountBinding == other.accountBinding && sessionBinding == other.sessionBinding
    override fun hashCode() = 31 * accountBinding.hashCode() + sessionBinding.hashCode()
    override fun toString() = "AccountUsageOwner([REDACTED])"
    companion object {
        fun fromSession(session: Session) = fromAccepted(session.accountId, sessionBinding(session.token))
        fun fromAccepted(accountId: String, tokenBinding: String): AccountUsageOwner {
            require(accountId.isNotBlank() && Regex("[a-f0-9]{64}").matches(tokenBinding))
            return AccountUsageOwner(sessionBinding(Secret(accountId)), tokenBinding)
        }
    }
}

internal data class AccountUsageAuthentication(val owner: AccountUsageOwner, val repositoryEpoch: Long) {
    override fun toString() = "AccountUsageAuthentication([REDACTED])"
}

internal data class AccountUsageReadContext(val owner: AccountUsageOwner, val repositoryEpoch: Long,
    val resourceRevision: Long, val readSequence: Long) {
    override fun toString() = "AccountUsageReadContext([REDACTED])"
}

internal data class BoundAccountOverview(val context: AccountUsageReadContext, val resource: Resource) {
    override fun toString() = "BoundAccountOverview([REDACTED])"
}

/** Identity of a whole server period; changing it invalidates pending publishers. */
internal data class AccountUsageResourceIdentity(val reference: String?, val state: String,
    val cycleStartedAt: java.time.Instant? = null, val cycleEndsAt: java.time.Instant? = null) {
    override fun toString() = "AccountUsageResourceIdentity([REDACTED])"
    companion object {
        fun of(resource: Resource) = when (resource) {
            is Resource.Available -> AccountUsageResourceIdentity(resource.reference, "available",
                resource.usage.cycleStartedAt, resource.usage.cycleEndsAt)
            Resource.None -> AccountUsageResourceIdentity(null, "none")
            Resource.Reconciliation -> AccountUsageResourceIdentity(null, "reconciliation")
        }
    }
}
