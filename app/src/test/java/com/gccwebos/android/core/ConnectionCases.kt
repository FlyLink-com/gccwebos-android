package com.gccwebos.android.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Identical behavioral cases are used by Gradle/JUnit and the SDK-less diagnostic runner. */
object ConnectionCases {
    data class Case(val name: String, val body: suspend CoroutineScope.() -> Unit)

    private class Rig(scope: CoroutineScope, timeout: ConnectionTimeouts = ConnectionTimeouts()) {
        val parent = SupervisorJob(scope.coroutineContext[Job])
        val ownerScope = CoroutineScope(scope.coroutineContext + parent)
        var authenticated = true
        var block: BusinessBlock? = null
        var permission = true
        var startResult = true
        var probeResult = true
        var configNull = false
        var wrongLine = false
        var failStep: Step? = null
        var waitStep: Step? = null
        var delayStep: Step? = null
        var delayMillis = 0L
        var stopFails = false
        var stopWaits = false
        var stopGate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val stopEntered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val materials = mutableListOf<AuthorizedConfig>()
        var active = false
        var starts = 0
        var stops = 0
        var gate: CompletableDeferred<Unit>? = null
        var lateConfig = false

        suspend fun touch(step: Step) {
            if (failStep == step) throw IOException("sensitive diagnostic must never escape")
            if (waitStep == step) {
                entered.complete(Unit)
                awaitCancellation()
            }
            if (delayStep == step) kotlinx.coroutines.delay(delayMillis)
        }

        val coordinator = ConnectionCoordinator(
            account = object : AccountGateway {
                override suspend fun isAuthenticated(): Boolean { touch(Step.ACCOUNT); return authenticated }
            },
            entitlement = object : EntitlementGateway {
                override suspend fun current(): AccessDecision { touch(Step.ENTITLEMENT); return AccessDecision(block) }
            },
            config = object : ConfigGateway {
                override suspend fun forLine(lineId: String?): AuthorizedConfig? {
                    touch(Step.CONFIG)
                    if (lateConfig) {
                        entered.complete(Unit)
                        withContext(NonCancellable) { gate!!.await() }
                    }
                    if (configNull) return null
                    return AuthorizedConfig(if (wrongLine) "other-line" else lineId ?: "auto-line", byteArrayOf(7, 11, 91))
                        .also { materials += it }
                }
            },
            permission = object : PermissionGateway {
                override suspend fun hasVpnPermission(): Boolean { touch(Step.PERMISSION); return permission }
            },
            tunnel = object : TunnelGateway {
                override suspend fun start(config: AuthorizedConfig): Boolean {
                    starts++
                    check(!active) { "overlapping global tunnel owners" }
                    active = true // Model allocation before a possible throw/cancellation/false.
                    events += "start:${config.lineId}"
                    config.copyMaterial().also { check(it.contentEquals(byteArrayOf(7, 11, 91))); it.fill(0) }
                    touch(Step.TUNNEL)
                    return startResult
                }
                override suspend fun stop() {
                    stops++
                    events += "stop"
                    stopEntered.complete(Unit)
                    if (stopFails) throw IOException("cleanup failed")
                    if (stopWaits) awaitCancellation()
                    stopGate?.await()
                    active = false
                }
            },
            probe = object : TrafficProbe {
                override suspend fun isUsable(): Boolean { touch(Step.TRAFFIC); return probeResult }
            },
            parentScope = ownerScope,
            timeouts = timeout,
        )

        suspend fun dispose() {
            // Never leave a deliberately non-cooperative fixture alive at test exit.
            gate?.complete(Unit)
            stopGate?.complete(Unit)
            stopFails = false
            stopWaits = false
            coordinator.close()
            parent.cancelAndJoin()
        }
    }

    private suspend fun CoroutineScope.withRig(
        timeout: ConnectionTimeouts = ConnectionTimeouts(),
        body: suspend Rig.() -> Unit,
    ) {
        val rig = Rig(this, timeout)
        try { rig.body() } finally { rig.dispose() }
    }

    private fun equal(expected: Any?, actual: Any?) {
        check(expected == actual) { "Expected $expected, received $actual" }
    }

    val all: List<Case> = buildList {
        add(Case("success_requires_probe_and_disconnect_releases") {
            withRig {
                equal(ConnectionState.Connected("line-a"), coordinator.connect("line-a"))
                equal(true, active)
                equal(ConnectionState.Disconnected, coordinator.disconnect())
                equal(false, active)
                equal(listOf("start:line-a", "stop"), events)
            }
        })
        add(Case("unauthenticated_never_starts") {
            withRig {
                authenticated = false
                equal(ConnectionState.Blocked(BusinessBlock.UNAUTHENTICATED), coordinator.connect())
                equal(0, starts)
            }
        })
        BusinessBlock.entries.forEach { reason ->
            add(Case("entitlement_block_$reason") {
                withRig { block = reason; equal(ConnectionState.Blocked(reason), coordinator.connect()); equal(0, starts) }
            })
        }
        add(Case("missing_config_never_starts") {
            withRig { configNull = true; equal(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE), coordinator.connect()); equal(0, starts) }
        })
        add(Case("wrong_authorized_line_never_starts") {
            withRig { wrongLine = true; equal(ConnectionState.Failed(Failure.NO_LINE), coordinator.connect("line-a")); equal(0, starts) }
        })
        add(Case("denied_permission_never_starts") {
            withRig { permission = false; equal(ConnectionState.Failed(Failure.PERMISSION_DENIED), coordinator.connect()); equal(0, starts) }
        })
        add(Case("false_start_cleans_partial_resources") {
            withRig {
                startResult = false
                equal(ConnectionState.Failed(Failure.TUNNEL_FAILED), coordinator.connect())
                equal(false, active); equal(1, stops)
            }
        })
        add(Case("false_probe_cleans_resources") {
            withRig {
                probeResult = false
                equal(ConnectionState.Failed(Failure.TRAFFIC_FAILED), coordinator.connect())
                equal(false, active); equal(1, stops)
            }
        })
        val mapped = mapOf(
            Step.ACCOUNT to Failure.ACCOUNT_FAILED, Step.ENTITLEMENT to Failure.ENTITLEMENT_FAILED,
            Step.CONFIG to Failure.CONFIG_UNAVAILABLE, Step.PERMISSION to Failure.PERMISSION_DENIED,
            Step.TUNNEL to Failure.TUNNEL_FAILED, Step.TRAFFIC to Failure.TRAFFIC_FAILED,
        )
        Step.entries.forEach { step ->
            add(Case("exception_$step") {
                withRig {
                    failStep = step
                    equal(ConnectionState.Failed(mapped.getValue(step)), coordinator.connect())
                    equal(false, active)
                    equal(if (step == Step.TUNNEL || step == Step.TRAFFIC) 1 else 0, stops)
                    check(!coordinator.state.value.toString().contains("sensitive"))
                }
            })
            add(Case("disconnect_cancels_pending_$step") {
                withRig {
                    waitStep = step
                    coroutineScope {
                        val old = async { coordinator.connect("line-a") }
                        entered.await()
                        equal(ConnectionState.Disconnected, withTimeout(1_000) { coordinator.disconnect() })
                        equal(ConnectionState.Disconnected, old.await())
                    }
                    equal(false, active)
                }
            })
            add(Case("caller_cancellation_cleans_$step") {
                withRig {
                    waitStep = step
                    coroutineScope {
                        val caller = launch { coordinator.connect() }
                        entered.await()
                        caller.cancelAndJoin()
                        coordinator.state.first { it !is ConnectionState.Working }
                    }
                    equal(ConnectionState.Disconnected, coordinator.state.value)
                    equal(false, active)
                }
            })
            add(Case("timeout_$step") {
                withRig(ConnectionTimeouts(40, 40, 100)) {
                    waitStep = step
                    equal(ConnectionState.Failed(Failure.TIMEOUT), coordinator.connect())
                    equal(false, active)
                }
            })
        }
        add(Case("config_step_has_its_own_longer_budget") {
            // A slow but still progressing fresh subscription read must not be cut at the ordinary step budget.
            withRig(ConnectionTimeouts(stepMillis = 40, permissionMillis = 1_000, cleanupMillis = 100, configMillis = 600)) {
                delayStep = Step.CONFIG; delayMillis = 150
                equal(ConnectionState.Connected("line-a"), coordinator.connect("line-a"))
                equal(true, active)
            }
        })
        add(Case("config_budget_defaults_to_the_step_budget_and_other_steps_keep_the_short_one") {
            withRig(ConnectionTimeouts(stepMillis = 40, permissionMillis = 1_000, cleanupMillis = 100, configMillis = 600)) {
                delayStep = Step.TUNNEL; delayMillis = 150
                equal(ConnectionState.Failed(Failure.TIMEOUT), coordinator.connect("line-a"))
                equal(false, active)
            }
            equal(40L, ConnectionTimeouts(stepMillis = 40).configMillis)
            equal(20_000L, ConnectionTimeouts().configMillis)
        })
        add(Case("switch_stops_a_before_b_and_keeps_real_line") {
            withRig {
                coordinator.connect("line-a")
                equal(ConnectionState.Connected("line-b"), coordinator.switchLine("line-b"))
                equal(listOf("start:line-a", "stop", "start:line-b"), events)
                equal(true, active)
            }
        })
        add(Case("same_connection_is_idempotent") {
            withRig {
                coordinator.connect("line-a")
                coordinator.connect("line-a")
                coordinator.connect()
                equal(1, starts); equal(ConnectionState.Connected("line-a"), coordinator.state.value)
            }
        })
        add(Case("duplicate_connect_while_pending_shares_attempt") {
            withRig {
                waitStep = Step.CONFIG
                coroutineScope {
                    val first = async { coordinator.connect("line-a") }
                    entered.await()
                    val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.connect("line-a") }
                    coordinator.disconnect()
                    first.await(); second.await()
                }
                equal(0, starts)
            }
        })
        add(Case("queued_cancellation_waits_for_previous_cleanup") {
            withRig {
                coordinator.connect("line-a")
                stopGate = CompletableDeferred()
                coroutineScope {
                    val pending = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.switchLine("line-b") }
                    stopEntered.await()
                    pending.cancel()
                    coordinator.state.first { it == ConnectionState.Disconnecting }
                    equal(true, active)
                    check(coordinator.state.value != ConnectionState.Disconnected)
                    stopGate!!.complete(Unit)
                    pending.join()
                    coordinator.state.first { it == ConnectionState.Disconnected }
                }
                equal(false, active); equal(1, starts)
            }
        })
        add(Case("obsolete_queued_cleanup_does_not_stop_new_tunnel") {
            withRig {
                coordinator.connect("line-a")
                stopGate = CompletableDeferred()
                coroutineScope {
                    val obsolete = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.switchLine("line-b") }
                    stopEntered.await()
                    obsolete.cancel()
                    val latest = async(start = CoroutineStart.UNDISPATCHED) { coordinator.switchLine("line-c") }
                    stopGate!!.complete(Unit)
                    equal(ConnectionState.Connected("line-c"), latest.await())
                    obsolete.join()
                }
                equal(ConnectionState.Connected("line-c"), coordinator.state.value)
                equal(true, active)
                equal(listOf("start:line-a", "stop", "start:line-c"), events)
            }
        })
        add(Case("reconnect_reuses_selection_after_cleanup") {
            withRig {
                coordinator.connect("line-a")
                equal(ConnectionState.Connected("line-a"), coordinator.reconnect())
                equal(listOf("start:line-a", "stop", "start:line-a"), events)
            }
        })
        add(Case("auto_reconnect_reselects_authorized_auto") {
            withRig {
                coordinator.connect()
                equal(ConnectionState.Connected("auto-line"), coordinator.reconnect())
                equal(2, starts)
            }
        })
        add(Case("failed_switch_does_not_claim_old_or_new_connected") {
            withRig {
                coordinator.connect("line-a")
                configNull = true
                equal(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE), coordinator.switchLine("line-b"))
                equal(false, active); equal(1, starts)
            }
        })
        add(Case("owner_cancellation_stops_already_connected_tunnel") {
            withRig {
                coordinator.connect("line-a")
                parent.cancelAndJoin()
                equal(false, active); equal(ConnectionState.Disconnected, coordinator.state.value)
            }
        })
        add(Case("cancelled_owner_rejects_new_commands") {
            withRig {
                parent.cancelAndJoin()
                var rejected = false
                try { coordinator.connect() } catch (_: IllegalStateException) { rejected = true }
                check(rejected)
            }
        })
        add(Case("late_config_cannot_restore_disconnected_state") {
            withRig {
                lateConfig = true; gate = CompletableDeferred()
                coroutineScope {
                    val pending = async { coordinator.connect() }
                    entered.await()
                    val stopping = async { coordinator.disconnect() }
                    coordinator.state.first { it == ConnectionState.Disconnecting }
                    gate!!.complete(Unit)
                    stopping.await(); pending.await()
                }
                equal(ConnectionState.Disconnected, coordinator.state.value)
                equal(0, starts)
                materials.forEach { material ->
                    var closed = false
                    try { material.copyMaterial() } catch (_: IllegalStateException) { closed = true }
                    check(closed)
                }
            }
        })
        add(Case("failed_cleanup_blocks_new_start_until_recovered") {
            withRig {
                coordinator.connect("line-a")
                stopFails = true
                equal(ConnectionState.Failed(Failure.CLEANUP_FAILED), coordinator.switchLine("line-b"))
                equal(1, starts)
                equal(ConnectionState.Failed(Failure.CLEANUP_FAILED), coordinator.connect("line-c"))
                equal(1, starts)
                stopFails = false
                equal(ConnectionState.Disconnected, coordinator.disconnect())
                equal(false, active)
                equal(ConnectionState.Connected("line-c"), coordinator.connect("line-c"))
            }
        })
        add(Case("cleanup_timeout_reports_uncertain_not_disconnected") {
            withRig(ConnectionTimeouts(1_000, 1_000, 40)) {
                coordinator.connect()
                stopWaits = true
                equal(ConnectionState.Failed(Failure.CLEANUP_FAILED), coordinator.disconnect())
                equal(true, active) // Deliberately modeled adapter failure, not a real VPN.
                stopWaits = false
                equal(ConnectionState.Disconnected, coordinator.disconnect())
                equal(false, active)
            }
        })
        add(Case("close_is_idempotent_and_rejects_commands") {
            withRig {
                coordinator.connect()
                equal(ConnectionState.Disconnected, coordinator.close())
                equal(ConnectionState.Disconnected, coordinator.close())
                var rejected = false
                try { coordinator.connect() } catch (_: IllegalStateException) { rejected = true }
                check(rejected); equal(false, active)
            }
        })
        add(Case("secret_material_is_defensive_redacted_and_closed") {
            val source = byteArrayOf(7, 11, 91)
            val item = AuthorizedConfig("private-line-label", source)
            source.fill(0)
            val copy = item.copyMaterial()
            check(copy.contentEquals(byteArrayOf(7, 11, 91)))
            copy.fill(0)
            check(item.copyMaterial().contentEquals(byteArrayOf(7, 11, 91)))
            equal("AuthorizedConfig(material=<redacted>)", item.toString())
            item.close(); item.close()
            var rejected = false
            try { item.copyMaterial() } catch (_: IllegalStateException) { rejected = true }
            check(rejected)
        })
        add(Case("concurrent_latest_commands_never_overlap_tunnel_owners") {
            withRig {
                coroutineScope {
                    val jobs = (0 until 120).map { index ->
                        launch(Dispatchers.Default) {
                            when (index % 4) {
                                0 -> coordinator.connect("line-a")
                                1 -> coordinator.switchLine("line-b")
                                2 -> coordinator.reconnect()
                                else -> coordinator.disconnect()
                            }
                        }
                    }
                    jobs.joinAll()
                }
                equal(ConnectionState.Disconnected, coordinator.disconnect())
                equal(false, active)
                // Each start must have an exclusive matching stop before another start.
                var owned = false
                events.forEach {
                    if (it.startsWith("start:")) { check(!owned); owned = true }
                    else if (it == "stop") owned = false
                }
                equal(false, owned)
            }
        })
        add(Case("all_delivered_materials_closed_after_start") {
            withRig {
                coordinator.connect()
                materials.forEach {
                    var rejected = false
                    try { it.copyMaterial() } catch (_: IllegalStateException) { rejected = true }
                    check(rejected)
                }
            }
        })
    }
}
