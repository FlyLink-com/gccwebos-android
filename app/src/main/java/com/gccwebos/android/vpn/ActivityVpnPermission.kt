package com.gccwebos.android.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.annotation.MainThread
import com.gccwebos.android.core.PermissionGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Activity launches consent; the service-owned request keeps its original generation. */
internal class ActivityVpnPermission(context: Context) : PermissionGateway {
    class Attachment internal constructor(internal val ticket: VpnPermissionTickets.Attachment)
    private data class Launcher(val attachment: Attachment, val launch: (Intent) -> Unit)
    private data class Pending(val ticket: VpnPermissionTickets.Ticket, val result: CompletableDeferred<Boolean>)

    private val application = context.applicationContext
    private val tickets = VpnPermissionTickets()
    // These fields and the ticket state machine are confined to Main.immediate.
    private var launcher: Launcher? = null
    private var pending: Pending? = null

    @MainThread
    fun attach(launch: (Intent) -> Unit): Attachment {
        val attachment = Attachment(tickets.attach())
        pending?.result?.complete(false)
        pending = null
        launcher = Launcher(attachment, launch)
        return attachment
    }

    @MainThread
    fun detach(attachment: Attachment) {
        if (launcher?.attachment !== attachment) return
        tickets.detach(attachment.ticket)
        launcher = null
        pending?.result?.complete(false)
        pending = null
    }

    @MainThread
    fun deliver(attachment: Attachment, resultCode: Int) {
        val ticket = tickets.deliver(attachment.ticket) ?: return
        val request = pending?.takeIf { it.ticket === ticket } ?: return
        val granted = resultCode == Activity.RESULT_OK &&
            runCatching { VpnService.prepare(application) == null }.getOrDefault(false)
        request.result.complete(granted)
    }

    override suspend fun hasVpnPermission(): Boolean = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        val intent = try { VpnService.prepare(application) } catch (_: Exception) { return@withContext false }
        if (intent == null) return@withContext true
        val ticket = tickets.begin() ?: return@withContext false
        val activity = launcher?.takeIf { it.attachment.ticket === ticket.attachment }
            ?: return@withContext false.also { tickets.cancel(ticket) }
        val request = Pending(ticket, CompletableDeferred())
        pending = request
        try {
            currentCoroutineContext().ensureActive()
            activity.launch(intent)
            request.result.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A launch failure is ambiguous: retain its result slot until delivery or detach.
            false
        } finally {
            tickets.cancel(ticket)
            if (pending === request) pending = null
            request.result.cancel()
        }
    }
}
